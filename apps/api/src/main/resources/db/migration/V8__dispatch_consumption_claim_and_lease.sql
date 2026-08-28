-- V8 lets published work be received and authoritatively claimed, and gives claimed work a way back out.
--
-- Three new transitions:
--     QUEUED   -> CLAIMED    a consumer won the compare-and-set for an unclaimed attempt
--     CLAIMED  -> STOPPING   the tenant cancelled, or the lease was lost and the assignment was fenced
--     STOPPING -> COMPLETED  the reconciler settled it
--
-- A broker message is transport, not authority. Nothing here lets a message decide anything: the claim is a
-- compare-and-set against authoritative state, the worker identity is server-controlled, and the assignment epoch
-- is the fencing token that makes a partitioned worker unable to act after it has been replaced.
--
-- No execution authority is granted. There is no ExecutionCommand, no source capability, and no secret
-- capability, and CLAIMED does not advance to PROVISIONING on its own. Everything past STOPPING still fails
-- closed.

-- OPERATIONAL NOTE, because none of this is online. Flyway wraps the file in one transaction, so every lock
-- below is held until it commits: ACCESS EXCLUSIVE on test_runs, execution_attempts, and run_lifecycle_events,
-- plus SHARE for three non-concurrent CREATE INDEX statements. The eight validating ADD CONSTRAINT ... CHECK
-- statements each full-scan their table under that lock. On a large deployment this is a maintenance window,
-- not a rolling upgrade. The lock_timeout below makes the migration fail fast rather than form a queue behind a
-- long-running reader and stall every writer behind it in turn.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------
-- Run state
-- ---------------------------------------------------------------------------------------------------------

-- Why a run is stopping, recorded when it enters STOPPING and retained through terminalization.
--
-- It is a column rather than an inference from `cancellation_status`, because "stopping without a cancellation
-- request" would otherwise be the only evidence that a lease was lost — a fact stored as the absence of another
-- fact. The reconciler that terminalizes STOPPING has to know which outcome it owes, and reading that from a
-- silence is how the wrong outcome gets written.
ALTER TABLE test_runs ADD COLUMN stop_reason varchar(64);

ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_stop_reason
        CHECK (stop_reason IS NULL OR stop_reason IN ('USER_REQUESTED', 'LEASE_LOST')),
    -- A run that is stopping must say why. A run that never stopped must not claim to have.
    ADD CONSTRAINT ck_test_runs_stopping_reason
        CHECK ((lifecycle_state = 'STOPPING') <= (stop_reason IS NOT NULL)
               AND (stop_reason IS NULL OR lifecycle_state IN ('STOPPING', 'COMPLETED'))),
    -- Terminalizing a stopping run may not change its mind about why it stopped.
    ADD CONSTRAINT ck_test_runs_stop_reason_agrees
        CHECK (stop_reason IS NULL OR termination_reason IS NULL OR termination_reason = stop_reason),
    -- An assignment belongs to an attempt, so every state that can hold one needs an attempt to hold it.
    ADD CONSTRAINT ck_test_runs_owned_states_have_attempt
        CHECK (lifecycle_state NOT IN ('CLAIMED', 'STOPPING') OR current_attempt_id IS NOT NULL);

-- Lease loss is an infrastructure failure in the claim phase. It is deliberately not TIMED_OUT: a queue deadline
-- means the platform never got to the run, while a lost lease means it did, and then lost the worker that had it.
ALTER TABLE test_runs DROP CONSTRAINT ck_test_runs_termination_vocabulary;
ALTER TABLE test_runs DROP CONSTRAINT ck_test_runs_terminal_reason_outcome;
ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_termination_vocabulary
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND termination_phase = 'CANCELLATION')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND termination_phase = 'QUEUE')
                      OR (termination_reason = 'LEASE_LOST' AND termination_phase = 'CLAIM'),
                      false)),
    ADD CONSTRAINT ck_test_runs_terminal_reason_outcome
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND infrastructure_outcome = 'CANCELLED')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT')
                      OR (termination_reason = 'LEASE_LOST' AND infrastructure_outcome = 'FAILED'),
                      false));

-- The reconciler's selection: runs stuck in STOPPING with nothing left to wait for.
CREATE INDEX ix_test_runs_stopping
    ON test_runs (updated_at, run_id)
    WHERE lifecycle_state = 'STOPPING';

-- ---------------------------------------------------------------------------------------------------------
-- Assignment: who owns an attempt, under what fencing token, and until when
-- ---------------------------------------------------------------------------------------------------------

ALTER TABLE execution_attempts
    -- The fencing token. Absent until the first claim, then strictly increasing and never restored, so a worker
    -- that was replaced cannot act on the strength of the epoch it used to hold.
    ADD COLUMN assignment_epoch integer,
    -- Audit identity, not authorization. It records which worker instance the control plane handed the
    -- assignment to; it never decides whether that instance may do anything.
    ADD COLUMN assigned_worker_id varchar(255),
    ADD COLUMN lease_started_at timestamptz,
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN last_heartbeat_at timestamptz,
    ADD COLUMN fenced_at timestamptz;

ALTER TABLE execution_attempts DROP CONSTRAINT ck_execution_attempts_initial_state;
ALTER TABLE execution_attempts
    ADD CONSTRAINT ck_execution_attempts_state
        CHECK (attempt_state IN ('WAITING_FOR_CLAIM', 'CLAIMED', 'FENCED')),
    -- The assignment is all-or-nothing, stated positively on BOTH sides.
    --
    -- A biconditional against "all five are NULL" only pins the unassigned side: its negation is "at least one is
    -- non-NULL", which admits a CLAIMED row carrying a worker and nothing else. That row then satisfies the window
    -- constraints vacuously (every comparison against a NULL lease_started_at is NULL, and a CHECK admits NULL),
    -- is invisible to the reconciler's index, and crashes the row mapper on read — a run owned by nobody that
    -- nothing can release. Naming all five on each side is what makes the two states genuinely exclusive.
    ADD CONSTRAINT ck_execution_attempts_assignment_shape
        CHECK ((attempt_state = 'WAITING_FOR_CLAIM')
               = (assignment_epoch IS NULL AND assigned_worker_id IS NULL AND lease_started_at IS NULL
                  AND lease_expires_at IS NULL AND last_heartbeat_at IS NULL)
               AND (attempt_state <> 'WAITING_FOR_CLAIM')
               = (assignment_epoch IS NOT NULL AND assigned_worker_id IS NOT NULL
                  AND lease_started_at IS NOT NULL AND lease_expires_at IS NOT NULL
                  AND last_heartbeat_at IS NOT NULL)),
    ADD CONSTRAINT ck_execution_attempts_epoch
        CHECK (assignment_epoch IS NULL OR assignment_epoch BETWEEN 1 AND 1000),
    -- Each window requires its own anchor rather than assuming one. Without the explicit non-NULL these evaluate
    -- to NULL when lease_started_at is absent, and a CHECK rejects only FALSE.
    ADD CONSTRAINT ck_execution_attempts_lease_window
        CHECK (lease_expires_at IS NULL
               OR (lease_started_at IS NOT NULL AND lease_expires_at > lease_started_at
                   -- The one magnitude that decides whether recovery can ever happen. Every other bound in this
                   -- migration is closed; leaving this one open means a single accepted write pins a run in
                   -- CLAIMED forever, which is the availability defect this slice exists to remove.
                   AND lease_expires_at <= lease_started_at + interval '30 minutes')),
    ADD CONSTRAINT ck_execution_attempts_heartbeat_window
        CHECK (last_heartbeat_at IS NULL
               OR (lease_started_at IS NOT NULL AND last_heartbeat_at >= lease_started_at)),
    -- Fenced means the assignment is over. It is the only state that carries a fencing instant, and it can only
    -- be reached from an assignment that existed.
    ADD CONSTRAINT ck_execution_attempts_fenced
        CHECK ((attempt_state = 'FENCED') = (fenced_at IS NOT NULL)
               AND (fenced_at IS NULL OR assignment_epoch IS NOT NULL));

-- The lease reconciler's selection. Restricted to live assignments so its cost tracks claimed work rather than
-- every attempt the platform has ever created.
CREATE INDEX ix_execution_attempts_lease
    ON execution_attempts (lease_expires_at, attempt_id)
    WHERE attempt_state = 'CLAIMED';

-- ---------------------------------------------------------------------------------------------------------
-- Consumer inbox
-- ---------------------------------------------------------------------------------------------------------

-- RabbitMQ delivery is at least once, so the same message will arrive again — after a redelivery, after a
-- consumer crash between the database commit and the broker acknowledgement, or after a broker restart. This
-- table is what makes the second arrival a decided no-op rather than a second claim.
--
-- It is keyed by application message identity, never by delivery tag: a delivery tag is channel-local transport
-- metadata that changes on every redelivery and means nothing across connections. The `redelivered` flag is
-- likewise a hint, not truth.
CREATE TABLE dispatch_inbox (
    inbox_id uuid PRIMARY KEY,
    -- Which logical consumer decided this. Two consumer groups may legitimately both process one message.
    consumer varchar(64) NOT NULL,
    message_id uuid NOT NULL,
    -- The digest of what was actually delivered, tagged by domain, so a second delivery carrying different bytes
    -- under the same identity is detectable rather than silently deduplicated.
    --
    -- The tag matters. A message that parsed contributes the semantic digest the contract defines; one that did
    -- not can only contribute a hash of its raw bytes. Comparing across those two domains would mean garbage
    -- published first under a chosen identity could permanently poison the genuine message behind it.
    payload_digest varchar(80) NOT NULL,
    organization_id uuid,
    project_id uuid,
    run_id uuid,
    disposition varchar(32) NOT NULL,
    reason varchar(64) NOT NULL,
    first_received_at timestamptz NOT NULL,
    last_received_at timestamptz NOT NULL,
    decided_at timestamptz NOT NULL,
    -- Redelivery is counted, not recorded as a separate disposition: the message's fate does not change when it
    -- arrives again, only the number of times the broker has offered it.
    delivery_count integer NOT NULL,
    CONSTRAINT uq_dispatch_inbox_message UNIQUE (consumer, message_id),
    CONSTRAINT ck_dispatch_inbox_disposition
        CHECK (disposition IN ('CLAIMED', 'STALE', 'REJECTED', 'CONFLICT')),
    CONSTRAINT ck_dispatch_inbox_digest CHECK (payload_digest ~ '^(semantic|raw):[a-f0-9]{64}$'),
    CONSTRAINT ck_dispatch_inbox_deliveries CHECK (delivery_count BETWEEN 1 AND 1000000),
    CONSTRAINT ck_dispatch_inbox_chronology
        CHECK (last_received_at >= first_received_at AND decided_at >= first_received_at),
    -- A message the consumer could not parse or trust has no tenant identity worth recording, because the only
    -- place that identity could have come from is the payload it just refused to believe.
    CONSTRAINT ck_dispatch_inbox_identity
        CHECK (disposition IN ('REJECTED', 'CONFLICT')
               OR (organization_id IS NOT NULL AND project_id IS NOT NULL AND run_id IS NOT NULL))
);

-- Retention is bounded but not implemented here: rows must outlive the broker's maximum plausible redelivery
-- window, because deleting one turns the next redelivery back into an undecided message. Nothing prunes this
-- table yet, and it is deliberately not claimed that infinite retention is required.
CREATE INDEX ix_dispatch_inbox_decided ON dispatch_inbox (decided_at, inbox_id);
CREATE INDEX ix_dispatch_inbox_run ON dispatch_inbox (organization_id, run_id) WHERE run_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------
-- The lifecycle guard set, rewritten together for the transitions this slice adds.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION guard_supported_test_run_update()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unchanged_except_scheduling boolean;
DECLARE unchanged_except_claim boolean;
DECLARE unchanged_except_stopping boolean;
DECLARE unchanged_except_terminal boolean;
BEGIN
    -- Columns a scheduling transition may move.
    unchanged_except_scheduling :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
         - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
           - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at');

    -- Columns a claim may move: the lifecycle, the version, and the audit stamps. Nothing else. The attempt
    -- reference is deliberately absent — a claim takes ownership of the attempt the run already names, and being
    -- able to point the run at a different attempt while claiming is precisely the substitution fencing exists
    -- to prevent. The assignment itself lives on execution_attempts and is guarded there.
    unchanged_except_claim :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'updated_by' - 'updated_at');

    -- Columns entering STOPPING may move. No outcome is written here: the run has not finished, and writing an
    -- outcome before it has would let a reconciler crash leave a run that claims a result it never reached.
    unchanged_except_stopping :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'stop_reason' - 'cancellation_status'
         - 'cancellation_requested_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'stop_reason' - 'cancellation_status'
           - 'cancellation_requested_at' - 'updated_by' - 'updated_at');

    -- Columns a terminal transition may move. Queue timing, the attempt reference, the snapshot digest, the stop
    -- reason and the quality gate are deliberately absent: terminalization ends a run's history, it does not
    -- rewrite it, and no test was evaluated so the gate must stay NOT_EVALUATED.
    unchanged_except_terminal :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
         - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
         - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
           - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
           - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'updated_by' - 'updated_at');

    -- SCHEDULE: CREATED -> QUEUED.
    IF coalesce(unchanged_except_scheduling, false)
       AND OLD.lifecycle_state = 'CREATED' AND NEW.lifecycle_state = 'QUEUED'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.current_attempt_id IS NULL AND NEW.current_attempt_id IS NOT NULL
       AND OLD.queued_at IS NULL AND OLD.queue_deadline_at IS NULL
       AND NEW.queued_at IS NOT NULL AND NEW.queue_deadline_at > NEW.queued_at
       AND NEW.updated_at = NEW.queued_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.updated_by = 'kaas.scheduler' THEN
        RETURN NEW;
    END IF;

    -- CLAIM: QUEUED -> CLAIMED. The run must still be inside the window it was promised — claiming after the
    -- queue deadline would take ownership of work the reaper is entitled to end, and the two would disagree
    -- about which of them won.
    IF coalesce(unchanged_except_claim, false)
       AND OLD.lifecycle_state = 'QUEUED' AND NEW.lifecycle_state = 'CLAIMED'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.current_attempt_id IS NOT NULL
       AND NEW.updated_at >= OLD.updated_at
       -- greatest(...), not clock_timestamp() alone, for the reason the TERMINATE branch below spells out: the
       -- run's own audit stamps come from the application clock and the claim instant is clamped up to them, so
       -- a bare upper bound contradicts the lower one whenever that clock leads the database's.
       AND NEW.updated_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.updated_at <= OLD.queue_deadline_at
       AND NEW.updated_by = 'kaas.dispatch-consumer' THEN
        RETURN NEW;
    END IF;

    -- STOP: CLAIMED -> STOPPING. Two causes, and each is pinned to the actor entitled to it.
    IF coalesce(unchanged_except_stopping, false)
       AND OLD.lifecycle_state = 'CLAIMED' AND NEW.lifecycle_state = 'STOPPING'
       AND NEW.run_version = OLD.run_version + 1
       AND NEW.updated_at >= OLD.updated_at
       -- The request instant belongs in the bound too: it comes from the application clock and the stop instant
       -- is clamped up to it, so omitting it makes cancelling an owned run fail outright under forward skew.
       AND NEW.updated_at <= greatest(
               clock_timestamp(), OLD.updated_at,
               coalesce(NEW.cancellation_requested_at, timestamptz '-infinity'))
       AND OLD.stop_reason IS NULL AND NEW.stop_reason IS NOT NULL
       AND NEW.completed_at IS NULL AND NEW.termination_reason IS NULL
       AND (
            -- A tenant asked. Unlike unowned work this cannot complete in one step, because an assignment
            -- exists and has to be fenced first, so the request is recorded and acknowledged later.
            (NEW.stop_reason = 'USER_REQUESTED'
             AND OLD.cancellation_status = 'NOT_REQUESTED'
             AND NEW.cancellation_status = 'REQUESTED'
             AND NEW.cancellation_requested_at IS NOT NULL
             AND NEW.cancellation_requested_at >= OLD.created_at
             AND NEW.updated_by NOT LIKE 'kaas.%')
         OR -- The lease was lost. Nobody asked for this, so nothing about it may say anyone did.
            (NEW.stop_reason = 'LEASE_LOST'
             AND NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at
             AND NEW.updated_by = 'kaas.lease-reconciler')
       ) THEN
        RETURN NEW;
    END IF;

    -- TERMINATE: CREATED or QUEUED -> COMPLETED, for work no worker ever took.
    IF coalesce(unchanged_except_terminal, false)
       AND OLD.lifecycle_state IN ('CREATED', 'QUEUED') AND NEW.lifecycle_state = 'COMPLETED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
       AND NEW.updated_at = NEW.completed_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.test_outcome = 'NOT_AVAILABLE'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.stop_reason IS NULL
       -- A termination cannot be dated in the future. Two subtleties make this its exact shape:
       --
       -- clock_timestamp(), not now(): now() is transaction start, and the instant being written was read with
       -- clock_timestamp() after the transaction had already begun, so now() would reject every legitimate
       -- termination. That is not hypothetical — an earlier draft of this guard did exactly that.
       --
       -- greatest(...), not clock_timestamp() alone: the run's own audit stamps come from the application clock,
       -- and the terminal instant is clamped to never precede them. If that clock runs ahead of the database's,
       -- a bare upper bound would contradict the lower bound and make the run permanently uncancellable. The
       -- bound is therefore exactly what the clamp can produce, and nothing beyond it.
       AND NEW.completed_at <= greatest(
               clock_timestamp(), OLD.updated_at,
               coalesce(NEW.cancellation_requested_at, timestamptz '-infinity'))
       AND (
            -- User cancellation. Unowned work stops immediately, so the request and its acknowledgement are the
            -- same instant; a REQUESTED row that outlives its transaction is not a state this branch produces.
            --
            -- The actor may not be a reserved platform identity. Without this, a tenant cancellation can be
            -- recorded as the scheduler's or the reaper's act — and guard_run_lifecycle_event only checks that
            -- the event agrees with the row, so it would faithfully record the forgery.
            (NEW.termination_reason = 'USER_REQUESTED'
             AND NEW.cancellation_status = 'ACKNOWLEDGED'
             AND NEW.cancellation_requested_at IS NOT NULL
             AND NEW.cancellation_requested_at >= OLD.created_at
             AND NEW.cancellation_acknowledged_at = NEW.completed_at
             AND NEW.updated_by NOT LIKE 'kaas.%')
         OR -- Queue deadline. Not a cancellation, and only reachable from QUEUED, where a deadline exists at all.
            -- Pinned to the reaper's identity exactly as the schedule branch pins the scheduler's, so a system
            -- expiry can never be attributed to a named tenant.
            (NEW.termination_reason = 'QUEUE_DEADLINE'
             AND NEW.cancellation_status = 'NOT_REQUESTED'
             AND NEW.cancellation_requested_at IS NULL
             AND OLD.lifecycle_state = 'QUEUED'
             AND OLD.queue_deadline_at IS NOT NULL
             AND NEW.completed_at >= OLD.queue_deadline_at
             AND NEW.updated_by = 'kaas.queue-reaper')
       ) THEN
        RETURN NEW;
    END IF;

    -- SETTLE: STOPPING -> COMPLETED. The outcome is fixed by the reason the run entered STOPPING, decided then
    -- and not revisited now, so a reconciler cannot convert a lost lease into a cancellation or the reverse.
    IF coalesce(unchanged_except_terminal, false)
       AND OLD.lifecycle_state = 'STOPPING' AND NEW.lifecycle_state = 'COMPLETED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
       AND NEW.updated_at = NEW.completed_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.completed_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.test_outcome = 'NOT_AVAILABLE'
       AND NEW.termination_reason = OLD.stop_reason
       AND NEW.updated_by = 'kaas.lease-reconciler'
       AND (
            (OLD.stop_reason = 'USER_REQUESTED'
             AND OLD.cancellation_status = 'REQUESTED'
             AND NEW.cancellation_status = 'ACKNOWLEDGED'
             AND NEW.cancellation_acknowledged_at = NEW.completed_at
             AND NEW.cancellation_requested_at = OLD.cancellation_requested_at)
         OR (OLD.stop_reason = 'LEASE_LOST'
             AND NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_acknowledged_at IS NOT DISTINCT FROM OLD.cancellation_acknowledged_at
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at)
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only scheduling, claim, stop, and terminal transitions are supported' USING ERRCODE = '23514';
END;
$$;

-- ---------------------------------------------------------------------------------------------------------
-- The attempt guard, rewritten from insert-only to claim, heartbeat, and fence.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION guard_execution_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_run integer;
DECLARE identity_unchanged boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'execution attempts are retained as assignment evidence' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Unchanged from V4: an attempt may only be born beside the exact QUEUED run that owns it, and it is
        -- born unassigned. Infrastructure retry, which is what would create a second attempt, is still absent.
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = 'QUEUED' AND current_attempt_id = NEW.attempt_id
           AND queued_at = NEW.created_at;
        IF matching_run <> 1 THEN
            RAISE EXCEPTION 'initial execution attempt requires its exact QUEUED run' USING ERRCODE = '23514';
        END IF;
        IF NEW.attempt_state <> 'WAITING_FOR_CLAIM' THEN
            RAISE EXCEPTION 'a new execution attempt is unassigned and waiting for claim' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- Identity, ownership, and history are immutable. Only the assignment may move.
    identity_unchanged := (to_jsonb(NEW) - 'attempt_state' - 'assignment_epoch' - 'assigned_worker_id'
                           - 'lease_started_at' - 'lease_expires_at' - 'last_heartbeat_at' - 'fenced_at')
                        = (to_jsonb(OLD) - 'attempt_state' - 'assignment_epoch' - 'assigned_worker_id'
                           - 'lease_started_at' - 'lease_expires_at' - 'last_heartbeat_at' - 'fenced_at');

    -- CLAIM: the first and only assignment this slice can create. The epoch starts at 1 and is the fencing
    -- token; reassignment would need a strictly higher one, and infrastructure retry a new attempt entirely.
    IF coalesce(identity_unchanged, false)
       AND OLD.attempt_state = 'WAITING_FOR_CLAIM' AND NEW.attempt_state = 'CLAIMED'
       AND OLD.assignment_epoch IS NULL AND NEW.assignment_epoch = 1
       AND NEW.assigned_worker_id IS NOT NULL
       AND NEW.lease_started_at IS NOT NULL AND NEW.lease_expires_at > NEW.lease_started_at
       AND NEW.last_heartbeat_at = NEW.lease_started_at
       AND NEW.fenced_at IS NULL
       -- greatest(...) for the same reason the lifecycle guard needs it: the claim instant is clamped up to the
       -- run's own audit stamp, which comes from the application clock.
       AND NEW.lease_started_at <= greatest(clock_timestamp(), OLD.created_at) THEN
        RETURN NEW;
    END IF;

    -- HEARTBEAT: renews exactly the live assignment and nothing else. The epoch and the worker cannot change,
    -- so a heartbeat from a replaced worker or a superseded epoch cannot match this shape at all.
    IF coalesce(identity_unchanged, false)
       AND OLD.attempt_state = 'CLAIMED' AND NEW.attempt_state = 'CLAIMED'
       AND NEW.assignment_epoch = OLD.assignment_epoch
       AND NEW.assigned_worker_id = OLD.assigned_worker_id
       AND NEW.lease_started_at = OLD.lease_started_at
       AND NEW.fenced_at IS NULL AND OLD.fenced_at IS NULL
       -- A lease only ever moves forward, and only while it is still alive. Renewing an expired lease here would
       -- undo the reconciler's whole basis for fencing.
       AND NEW.last_heartbeat_at > OLD.last_heartbeat_at
       AND NEW.last_heartbeat_at <= clock_timestamp()
       AND NEW.lease_expires_at > OLD.lease_expires_at
       -- Against the server's clock, not against a value the writer supplied. Comparing the old expiry to
       -- NEW.last_heartbeat_at alone lets a caller pick any instant inside the dead window and ratchet an expired
       -- lease back to life one accepted UPDATE at a time — which is precisely what fencing is supposed to make
       -- impossible without having to reach the worker.
       AND OLD.lease_expires_at > clock_timestamp()
       AND OLD.lease_expires_at > NEW.last_heartbeat_at
       -- A renewal cannot buy more than a lease is allowed to be worth.
       AND NEW.lease_expires_at <= clock_timestamp() + interval '30 minutes' THEN
        RETURN NEW;
    END IF;

    -- FENCE: the assignment ends. The epoch is kept rather than cleared, because it is the record of which
    -- assignment was fenced and a later one must be strictly greater than it.
    IF coalesce(identity_unchanged, false)
       AND OLD.attempt_state = 'CLAIMED' AND NEW.attempt_state = 'FENCED'
       AND NEW.assignment_epoch = OLD.assignment_epoch
       AND NEW.assigned_worker_id = OLD.assigned_worker_id
       AND NEW.lease_started_at = OLD.lease_started_at
       AND NEW.lease_expires_at = OLD.lease_expires_at
       AND NEW.last_heartbeat_at = OLD.last_heartbeat_at
       AND OLD.fenced_at IS NULL AND NEW.fenced_at IS NOT NULL
       AND NEW.fenced_at >= OLD.last_heartbeat_at
       -- Bounded by the lease it is ending, not by the database clock alone.
       --
       -- A cancellation fences at the instant the run stops, and that instant is clamped up to the tenant's
       -- request time, which comes from the application clock. A bare clock_timestamp() bound therefore rejects
       -- a perfectly ordinary cancellation whenever the API host leads the database by even a few milliseconds —
       -- and the rejection surfaces as a generic 409 on a run that is plainly still CLAIMED. Fencing early is
       -- exactly what cancellation does, so the honest ceiling is the end of the lease being revoked.
       AND NEW.fenced_at <= greatest(clock_timestamp(), OLD.lease_expires_at) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only claim, heartbeat, and fence assignment transitions are supported'
        USING ERRCODE = '23514';
END;
$$;

DROP TRIGGER execution_attempts_guard ON execution_attempts;
DROP FUNCTION guard_initial_execution_attempt();
CREATE TRIGGER execution_attempts_guard
BEFORE INSERT OR UPDATE OR DELETE ON execution_attempts
FOR EACH ROW EXECUTE FUNCTION guard_execution_attempt();

-- ---------------------------------------------------------------------------------------------------------
-- Lifecycle events and the cross-row bundle invariant
-- ---------------------------------------------------------------------------------------------------------

ALTER TABLE run_lifecycle_events DROP CONSTRAINT ck_run_lifecycle_events_transition;
ALTER TABLE run_lifecycle_events
    ADD CONSTRAINT ck_run_lifecycle_events_transition CHECK (
        event_type = 'RUN_STATE_CHANGED'
        -- Every implemented transition bumps the version by one and appends one event, so the two counters are
        -- the same fact written twice. Binding them here stops an event from claiming a version it did not cause.
        AND run_version = sequence + 1
        AND ((sequence = 1 AND previous_state = 'CREATED' AND lifecycle_state = 'QUEUED'
              AND attempt_id IS NOT NULL AND actor = 'kaas.scheduler')
             OR (sequence = 1 AND previous_state = 'CREATED' AND lifecycle_state = 'COMPLETED'
                 AND attempt_id IS NULL)
             OR (sequence = 2 AND previous_state = 'QUEUED' AND lifecycle_state = 'COMPLETED'
                 AND attempt_id IS NOT NULL)
             OR (sequence = 2 AND previous_state = 'QUEUED' AND lifecycle_state = 'CLAIMED'
                 AND attempt_id IS NOT NULL AND actor = 'kaas.dispatch-consumer')
             OR (sequence = 3 AND previous_state = 'CLAIMED' AND lifecycle_state = 'STOPPING'
                 AND attempt_id IS NOT NULL)
             OR (sequence = 4 AND previous_state = 'STOPPING' AND lifecycle_state = 'COMPLETED'
                 AND attempt_id IS NOT NULL AND actor = 'kaas.lease-reconciler')));

CREATE OR REPLACE FUNCTION require_complete_scheduling_bundle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_record test_runs%ROWTYPE;
DECLARE complete_bundles integer;
DECLARE scheduling_children integer;
DECLARE live_assignments integer;
BEGIN
    SELECT * INTO run_record FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id;
    SELECT count(*) INTO scheduling_children FROM execution_attempts
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id;
    SELECT count(*) INTO live_assignments FROM execution_attempts
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
       AND attempt_state = 'CLAIMED';

    IF run_record.lifecycle_state = 'QUEUED' THEN
        SELECT count(*) INTO complete_bundles
          FROM execution_attempts a
          JOIN execution_dispatches d ON d.organization_id = a.organization_id
           AND d.project_id = a.project_id AND d.run_id = a.run_id AND d.attempt_id = a.attempt_id
          JOIN outbox_messages o ON o.dispatch_id = d.dispatch_id AND o.message_id = d.message_id
           AND o.organization_id = d.organization_id AND o.project_id = d.project_id AND o.run_id = d.run_id
           AND o.payload_sha256 = d.payload_sha256 AND o.occurred_at = d.occurred_at
           AND o.payload = d.payload
           AND o.message_type = 'EXECUTION_DISPATCH'
          JOIN run_lifecycle_events e ON e.organization_id = a.organization_id
           AND e.project_id = a.project_id AND e.run_id = a.run_id AND e.attempt_id = a.attempt_id
         WHERE a.organization_id = run_record.organization_id AND a.project_id = run_record.project_id
           AND a.run_id = run_record.run_id AND a.attempt_id = run_record.current_attempt_id
           AND a.attempt_number = 1 AND a.attempt_state = 'WAITING_FOR_CLAIM'
           AND d.run_version = run_record.run_version AND d.run_snapshot_id = run_record.run_id
           AND d.run_snapshot_sha256 = run_record.snapshot_sha256
           AND d.occurred_at = run_record.queued_at AND d.queue_deadline_at = run_record.queue_deadline_at
           AND e.run_version = run_record.run_version AND e.sequence = 1
           AND e.occurred_at = run_record.queued_at;
        IF complete_bundles <> 1 OR scheduling_children <> 1 THEN
            RAISE EXCEPTION 'QUEUED run requires exactly one complete attempt dispatch event outbox bundle'
                USING ERRCODE = '23514';
        END IF;
    ELSIF run_record.lifecycle_state = 'CLAIMED' THEN
        -- A claimed run owns exactly the attempt it names, and that attempt is the one holding the assignment.
        IF scheduling_children <> 1 OR live_assignments <> 1 THEN
            RAISE EXCEPTION 'CLAIMED run requires exactly one attempt holding the active assignment'
                USING ERRCODE = '23514';
        END IF;
    ELSIF run_record.lifecycle_state = 'STOPPING' THEN
        -- Entering STOPPING is what fences the assignment. If the attempt is still live at commit, the fence did
        -- not happen and a worker still believes it owns work the control plane has decided to end.
        IF scheduling_children <> 1 OR live_assignments <> 0 THEN
            RAISE EXCEPTION 'a stopping run must have its assignment fenced' USING ERRCODE = '23514';
        END IF;
    ELSIF run_record.lifecycle_state = 'COMPLETED' THEN
        -- A run terminalized from CREATED has no children; one terminalized later keeps exactly the bundle it
        -- earned. Both are legitimate; gaining a new attempt is not, and neither is keeping a live assignment.
        IF scheduling_children > 1 THEN
            RAISE EXCEPTION 'a terminal run cannot gain additional scheduling children' USING ERRCODE = '23514';
        END IF;
        IF live_assignments <> 0 THEN
            RAISE EXCEPTION 'a terminal run cannot retain a live assignment' USING ERRCODE = '23514';
        END IF;
        -- And a run may not end without saying so. Scheduling's event is mandatory because the QUEUED branch
        -- joins it; terminalization's was enforced only by application code, so any second writer or repair
        -- script could silently commit a run whose history has a gap where its ending should be.
        IF NOT EXISTS (SELECT 1 FROM run_lifecycle_events e
                        WHERE e.organization_id = run_record.organization_id
                          AND e.project_id = run_record.project_id AND e.run_id = run_record.run_id
                          AND e.run_version = run_record.run_version
                          AND e.lifecycle_state = 'COMPLETED'
                          AND e.occurred_at = run_record.completed_at) THEN
            RAISE EXCEPTION 'a terminal run requires its own lifecycle event' USING ERRCODE = '23514';
        END IF;
    ELSIF scheduling_children <> 0 THEN
        -- CREATED legitimately has no children. Everything else that lands here is a state no implemented
        -- transition can produce, so failing closed is the point: a run that reached PROVISIONING or RUNNING by
        -- some route this guard does not know about must not be able to keep its scheduling bundle quietly.
        RAISE EXCEPTION 'scheduling children require a run in an implemented state' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- The assignment is half the invariant, so the attempt table has to trip the same check.
CREATE CONSTRAINT TRIGGER execution_attempt_assignment_consistent
AFTER UPDATE ON execution_attempts DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();

CREATE OR REPLACE FUNCTION guard_run_lifecycle_event()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_run integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'run lifecycle events are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state = 'QUEUED' THEN
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = 'QUEUED' AND run_version = NEW.run_version
           AND current_attempt_id = NEW.attempt_id AND queued_at = NEW.occurred_at
           AND updated_by = NEW.actor;
    ELSIF NEW.lifecycle_state = 'COMPLETED' THEN
        -- A terminal event must match the terminal transition that produced it: same version, same instant, same
        -- actor, and an attempt reference that agrees with whether the run ever had one.
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = 'COMPLETED' AND run_version = NEW.run_version
           AND completed_at = NEW.occurred_at AND updated_by = NEW.actor
           AND current_attempt_id IS NOT DISTINCT FROM NEW.attempt_id;
    ELSE
        -- CLAIMED and STOPPING are not terminal and have no completion instant, so the event is bound to the
        -- transition's own audit stamp instead.
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = NEW.lifecycle_state AND run_version = NEW.run_version
           AND updated_at = NEW.occurred_at AND updated_by = NEW.actor
           AND current_attempt_id = NEW.attempt_id;
    END IF;
    IF matching_run <> 1 THEN
        RAISE EXCEPTION 'run lifecycle event must match the authoritative transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Inbox immutability: a decision, once made, is evidence.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION guard_dispatch_inbox()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Deleting a row turns the next redelivery of that message back into an undecided one. Retention is
        -- bounded by an operator policy that does not exist yet, not by ad-hoc deletion.
        RAISE EXCEPTION 'inbox decisions are retained as consumption evidence' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.delivery_count <> 1 OR NEW.first_received_at <> NEW.last_received_at THEN
            RAISE EXCEPTION 'a new inbox record is its own first delivery' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    -- Only the redelivery counters move. The decision, the digest, and the identity it was made about are fixed:
    -- if a later delivery disagrees with any of them it is a different message wearing the same name, which is
    -- an integrity conflict to be recorded, not an update to be applied.
    IF (to_jsonb(NEW) - 'last_received_at' - 'delivery_count')
        = (to_jsonb(OLD) - 'last_received_at' - 'delivery_count')
       -- Saturating at the ceiling is a legal redelivery too. Requiring a strict increment would make every
       -- delivery past the bound violate the CHECK forever, with no statement able to lower it again.
       AND (NEW.delivery_count = OLD.delivery_count + 1
            OR (NEW.delivery_count = OLD.delivery_count AND OLD.delivery_count = 1000000))
       AND NEW.last_received_at >= OLD.last_received_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'only redelivery accounting may change an inbox decision' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER dispatch_inbox_guard
BEFORE INSERT OR UPDATE OR DELETE ON dispatch_inbox
FOR EACH ROW EXECUTE FUNCTION guard_dispatch_inbox();

-- Row triggers do not fire for TRUNCATE, so the inbox joins the other evidence tables at statement level.
CREATE TRIGGER dispatch_inbox_no_truncate
BEFORE TRUNCATE ON dispatch_inbox FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
