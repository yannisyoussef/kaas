-- V7 gives the lifecycle its first terminal transitions, and rewrites the scheduling-only guards as one set.
--
-- Admission counts every run that is not COMPLETED, but until now nothing could reach COMPLETED, so the ceiling
-- was an availability ceiling: an organization that legitimately created its quota could never create again, and
-- a run whose queue deadline had passed stayed QUEUED forever holding capacity it would never use.
--
-- Exactly three new transitions become possible here:
--     CREATED -> COMPLETED  (user cancellation)
--     QUEUED  -> COMPLETED  (user cancellation)
--     QUEUED  -> COMPLETED  (queue deadline)
-- Nothing else. QUEUED -> CLAIMED and everything past it must still fail closed. No worker owns CREATED or
-- QUEUED work, so there is no STOPPING phase: cancelling unowned work is immediate, and the request and its
-- acknowledgement are the same transaction.

ALTER TABLE test_runs
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN cancellation_requested_at timestamptz,
    ADD COLUMN cancellation_acknowledged_at timestamptz,
    -- Why the run ended, and in which phase. The phase vocabulary is the one the runner result contract already
    -- defines for structured errors, rather than a second private enum that would immediately need reconciling.
    ADD COLUMN termination_reason varchar(64),
    ADD COLUMN termination_phase varchar(32);

ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_terminal_timestamp
        CHECK ((lifecycle_state = 'COMPLETED') = (completed_at IS NOT NULL)),
    ADD CONSTRAINT ck_test_runs_terminal_reason
        CHECK ((completed_at IS NULL AND termination_reason IS NULL AND termination_phase IS NULL)
               OR (completed_at IS NOT NULL AND termination_reason IS NOT NULL
                   AND termination_phase IS NOT NULL)),
    -- Only the two terminations this slice implements. A later slice that adds execution outcomes extends these
    -- deliberately rather than inheriting an open vocabulary.
    --
    -- Both are wrapped in coalesce because a CHECK rejects only FALSE: with a NULL phase or outcome each
    -- disjunction evaluates to NULL and the row is admitted. ck_test_runs_terminal_reason happens to force all
    -- three non-NULL today, so nothing exploits it — but that rescue is the constraint most likely to be relaxed
    -- when execution outcomes land, and defence that only works by accident is not defence.
    ADD CONSTRAINT ck_test_runs_termination_vocabulary
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND termination_phase = 'CANCELLATION')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND termination_phase = 'QUEUE'),
                      false)),
    ADD CONSTRAINT ck_test_runs_terminal_reason_outcome
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND infrastructure_outcome = 'CANCELLED')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT'),
                      false)),
    -- An acknowledgement must say when, and cannot exist without a request. V3 already requires that a CANCELLED
    -- outcome be acknowledged; this pins the timestamps to the status so the two cannot drift.
    ADD CONSTRAINT ck_test_runs_cancellation_timing
        CHECK ((cancellation_status = 'ACKNOWLEDGED') = (cancellation_acknowledged_at IS NOT NULL)
               AND (cancellation_status = 'NOT_REQUESTED') = (cancellation_requested_at IS NULL)
               AND (cancellation_acknowledged_at IS NULL
                    OR cancellation_acknowledged_at >= cancellation_requested_at)),
    -- A queue timeout is not a cancellation, and must never be reported as one.
    --
    -- Scoped to the queue deadline rather than to TIMED_OUT in general, because the obvious next case is a
    -- cancellation of a RUNNING run that the worker never acknowledges and that then times out. That run really
    -- is TIMED_OUT with an outstanding cancellation request, and a constraint written over every TIMED_OUT row
    -- for all time would foreclose it.
    ADD CONSTRAINT ck_test_runs_timeout_not_cancelled
        CHECK (termination_reason IS DISTINCT FROM 'QUEUE_DEADLINE'
               OR cancellation_status = 'NOT_REQUESTED');

-- The reaper's selection. Ordered by deadline so the most overdue run is reaped first, and restricted to QUEUED
-- so its cost tracks the live queue rather than every run the platform has ever accepted.
CREATE INDEX ix_test_runs_queue_deadline
    ON test_runs (queue_deadline_at, run_id)
    WHERE lifecycle_state = 'QUEUED';

-- ---------------------------------------------------------------------------------------------------------
-- Lifecycle events: a run cancelled while still CREATED never had an attempt, so the event cannot require one.
-- ---------------------------------------------------------------------------------------------------------

ALTER TABLE run_lifecycle_events ALTER COLUMN attempt_id DROP NOT NULL;

-- fk_run_lifecycle_events_attempt is composite over (organization_id, project_id, run_id, attempt_id) and is
-- MATCH SIMPLE, so a NULL attempt_id skips the WHOLE constraint — including the tenancy and run binding, not
-- just the attempt. For a CREATED -> COMPLETED event that binding therefore rests on guard_run_lifecycle_event
-- below, which matches every event against the authoritative run row. The attempt's presence is tied to the
-- transition shape here.
ALTER TABLE run_lifecycle_events DROP CONSTRAINT ck_run_lifecycle_events_schedule;
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
                 AND attempt_id IS NOT NULL)));

-- ---------------------------------------------------------------------------------------------------------
-- Outbox: suppression is not a delivery failure.
-- ---------------------------------------------------------------------------------------------------------

ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_terminal_disposition;
ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_terminal_reason;
ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_attempt_accounting;
ALTER TABLE outbox_messages
    ADD CONSTRAINT ck_outbox_terminal_disposition
        CHECK (terminal_disposition IS NULL
               OR terminal_disposition IN ('RETRIES_EXHAUSTED', 'PERMANENT_FAILURE',
                                           'SUPPRESSED_CANCELLED', 'SUPPRESSED_QUEUE_TIMEOUT')),
    -- A delivery failure must say why it failed. A withdrawal need not: nothing failed, so a required failure
    -- code would be a lie. It may still carry one, because a message that failed twice and was then withdrawn
    -- has a real history and erasing it would be a second lie.
    ADD CONSTRAINT ck_outbox_terminal_reason
        CHECK (terminal_disposition IS NULL
               OR terminal_disposition IN ('SUPPRESSED_CANCELLED', 'SUPPRESSED_QUEUE_TIMEOUT')
               OR last_failure_code IS NOT NULL),
    -- Attempt accounting is history, not a claim about the disposition. "Suppressed" means withdrawn before
    -- publication, NOT never attempted: a dispatch in retry backoff when its run is cancelled has already spent
    -- attempts, and requiring zero here would make cancelling that run impossible — platform-wide during a
    -- broker outage, which is exactly when the queue backs up and the admission ceiling binds.
    ADD CONSTRAINT ck_outbox_attempt_accounting
        CHECK (CASE
                   WHEN terminal_disposition IN ('SUPPRESSED_CANCELLED', 'SUPPRESSED_QUEUE_TIMEOUT')
                       THEN published_at IS NULL
                   WHEN published_at IS NULL AND terminal_disposition IS NULL THEN true
                   ELSE publish_attempts >= 1 AND last_attempt_at IS NOT NULL
               END);

-- countTerminal() reads only the delivery failures, so the dead-letter index must carry the same predicate.
-- ix_outbox_terminal's "IS NOT NULL" is implied by that predicate rather than equal to it, which would turn an
-- index-only count into a heap scan with a per-row recheck — on every health probe and every metrics scrape, over
-- a table that is never pruned and to which every cancelled and every expired run now adds a row.
CREATE INDEX ix_outbox_dead_letters
    ON outbox_messages (occurred_at, message_id)
    WHERE terminal_disposition IN ('RETRIES_EXHAUSTED', 'PERMANENT_FAILURE');

-- ---------------------------------------------------------------------------------------------------------
-- The scheduling-only guard set, rewritten together.
--
-- These were written when CREATED -> QUEUED was the only mutation that could exist, and each encodes that
-- assumption in a different place. Patching them one at a time would leave the combined invariant inconsistent,
-- so they move as a unit to exactly the transitions implemented today, and no further.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION guard_supported_test_run_update()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unchanged_except_scheduling boolean;
DECLARE unchanged_except_terminal boolean;
BEGIN
    -- Columns a scheduling transition may move.
    unchanged_except_scheduling :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
         - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
           - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at');

    -- Columns a terminal transition may move. Queue timing, the attempt reference, the snapshot digest and the
    -- quality gate are deliberately absent: terminalization ends a run's history, it does not rewrite it, and no
    -- test was evaluated so the gate must stay NOT_EVALUATED.
    unchanged_except_terminal :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
         - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
         - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
           - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
           - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'updated_by' - 'updated_at');

    -- SCHEDULE: CREATED -> QUEUED, unchanged from V4 except that it is now one branch among several.
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

    -- TERMINATE: CREATED or QUEUED -> COMPLETED. The outcome columns are already constrained by the table's own
    -- CHECKs; what this guard fixes is the *shape* of the transition and which prior states may reach it.
    IF coalesce(unchanged_except_terminal, false)
       AND OLD.lifecycle_state IN ('CREATED', 'QUEUED') AND NEW.lifecycle_state = 'COMPLETED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
       AND NEW.updated_at = NEW.completed_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.test_outcome = 'NOT_AVAILABLE'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
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
            -- same instant; a REQUESTED row that outlives its transaction is not a state this slice can produce.
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

    RAISE EXCEPTION 'only scheduling and early terminal transitions are supported' USING ERRCODE = '23514';
END;
$$;

-- A terminal run keeps whatever scheduling children it had: they are delivery and audit evidence, not live
-- state, and the version they were stamped with is the version they described. What must still hold is that a
-- QUEUED run has exactly one complete bundle, that a CREATED run has none, and that terminalization adds none.
CREATE OR REPLACE FUNCTION require_complete_scheduling_bundle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_record test_runs%ROWTYPE;
DECLARE complete_bundles integer;
DECLARE scheduling_children integer;
BEGIN
    SELECT * INTO run_record FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id;
    SELECT count(*) INTO scheduling_children FROM execution_attempts
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id;
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
    ELSIF run_record.lifecycle_state = 'COMPLETED' THEN
        -- A run terminalized from CREATED has no children; one terminalized from QUEUED keeps exactly the bundle
        -- that was validated when it entered QUEUED. Both are legitimate; gaining a new attempt is not.
        IF scheduling_children > 1 THEN
            RAISE EXCEPTION 'a terminal run cannot gain additional scheduling children' USING ERRCODE = '23514';
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
        RAISE EXCEPTION 'scheduling children require a QUEUED run' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- Lifecycle events now record terminalization as well as scheduling. Each event must match the authoritative row
-- it claims to describe, including the actor, so an event cannot attribute a transition to someone who did not
-- perform it.
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
    ELSE
        -- A terminal event must match the terminal transition that produced it: same version, same instant, same
        -- actor, and an attempt reference that agrees with whether the run ever had one.
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = 'COMPLETED' AND run_version = NEW.run_version
           AND completed_at = NEW.occurred_at AND updated_by = NEW.actor
           AND current_attempt_id IS NOT DISTINCT FROM NEW.attempt_id;
    END IF;
    IF matching_run <> 1 THEN
        RAISE EXCEPTION 'run lifecycle event must match the authoritative transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Suppression: a pending dispatch for a run that will never execute must not be published.
--
-- It is only offered for a row no relay holds. A claimed row is already mid-publication, and suppressing it would
-- be pretending the control plane can recall a message it may already have handed to the broker. That row is
-- allowed to publish and become stale instead, which is a duplicate-delivery case the consumer slice must reject
-- on its own terms regardless.
CREATE OR REPLACE FUNCTION guard_outbox_message()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE existing_digest varchar(64);
DECLARE immutable_unchanged boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox messages are retained as delivery evidence' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT payload_sha256 INTO existing_digest FROM outbox_messages WHERE message_id = NEW.message_id;
        IF FOUND AND existing_digest IS DISTINCT FROM NEW.payload_sha256 THEN
            RAISE EXCEPTION 'outbox message identity has a conflicting payload digest' USING ERRCODE = '23514';
        END IF;
        IF NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL AND NEW.publish_attempts = 0
           AND NEW.last_attempt_at IS NULL AND NEW.last_failure_code IS NULL AND NEW.relay_claim_id IS NULL
           AND NEW.available_at = NEW.occurred_at THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a new outbox message must be unpublished, unclaimed, and immediately available'
            USING ERRCODE = '23514';
    END IF;

    immutable_unchanged := (to_jsonb(NEW) - 'available_at' - 'last_attempt_at' - 'published_at'
                            - 'publish_attempts' - 'last_failure_code' - 'terminal_disposition'
                            - 'relay_claim_id' - 'relay_claimed_at' - 'relay_claim_expires_at')
                         = (to_jsonb(OLD) - 'available_at' - 'last_attempt_at' - 'published_at'
                            - 'publish_attempts' - 'last_failure_code' - 'terminal_disposition'
                            - 'relay_claim_id' - 'relay_claimed_at' - 'relay_claim_expires_at');

    -- SUPPRESS: the run became terminal before this message was published. No attempt is consumed and no failure
    -- is recorded, because neither happened; whatever history the row already had is left exactly as it was.
    --
    -- Allowed for a row no relay is *currently* holding — unclaimed, or holding a lease that has expired. The
    -- expired case matters: the relay reclaims on "claim is null OR lease expired", so a row abandoned by a
    -- crashed relay is publishable. Refusing to suppress it would leave a dispatch that cancellation cannot
    -- withdraw and a later relay pass then delivers, for the first time, on behalf of a run that is already over.
    -- A dead claim is cleared as part of the withdrawal so no stale lease outlives it.
    --
    -- A live lease is still left alone: that message may already be at the broker, and suppressing it would be
    -- pretending the control plane can recall something it cannot.
    IF coalesce(immutable_unchanged, false)
       AND OLD.published_at IS NULL AND OLD.terminal_disposition IS NULL
       AND (OLD.relay_claim_id IS NULL OR OLD.relay_claim_expires_at <= now())
       AND NEW.relay_claim_id IS NULL
       AND NEW.published_at IS NULL
       AND NEW.terminal_disposition IN ('SUPPRESSED_CANCELLED', 'SUPPRESSED_QUEUE_TIMEOUT')
       AND NEW.publish_attempts = OLD.publish_attempts
       AND NEW.available_at = OLD.available_at
       AND NEW.last_attempt_at IS NOT DISTINCT FROM OLD.last_attempt_at
       AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code
       -- Bound to the run it claims to be suppressing, and to the reason that run actually ended for. Without
       -- this, one UPDATE permanently withdraws the dispatch of a live QUEUED run — through a one-way door,
       -- because the requeue branch deliberately refuses suppressed rows. The sibling guards check authority
       -- against test_runs the same way.
       AND EXISTS (SELECT 1 FROM test_runs r
                    WHERE r.organization_id = NEW.organization_id AND r.project_id = NEW.project_id
                      AND r.run_id = NEW.run_id AND r.lifecycle_state = 'COMPLETED'
                      AND r.termination_reason = CASE NEW.terminal_disposition
                              WHEN 'SUPPRESSED_CANCELLED' THEN 'USER_REQUESTED'
                              ELSE 'QUEUE_DEADLINE' END) THEN
        RETURN NEW;
    END IF;

    -- REQUEUE: an operator returns a delivery-failed message to the pool. A suppressed message is deliberately
    -- not requeueable: its run is gone, so replaying it would dispatch work nobody is waiting for.
    IF coalesce(immutable_unchanged, false)
       AND OLD.terminal_disposition IN ('RETRIES_EXHAUSTED', 'PERMANENT_FAILURE') AND OLD.published_at IS NULL
       AND NEW.terminal_disposition IS NULL AND NEW.published_at IS NULL
       AND NEW.relay_claim_id IS NULL AND NEW.publish_attempts = 0
       AND NEW.last_failure_code IS NULL AND NEW.available_at >= OLD.available_at THEN
        RETURN NEW;
    END IF;

    IF coalesce(immutable_unchanged, false)
       AND OLD.published_at IS NULL AND OLD.terminal_disposition IS NULL
       AND (
            (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND NEW.relay_claim_id IS NOT NULL
             AND NEW.relay_claim_expires_at > now()
             AND NEW.available_at <= now()
             AND (OLD.relay_claim_id IS NULL OR OLD.relay_claim_expires_at <= now())
             AND NEW.publish_attempts = OLD.publish_attempts
             AND NEW.available_at = OLD.available_at
             AND NEW.last_attempt_at IS NOT DISTINCT FROM OLD.last_attempt_at
             AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code)
         OR (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts
             AND NEW.available_at = OLD.available_at
             AND NEW.last_attempt_at IS NOT DISTINCT FROM OLD.last_attempt_at
             AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code)
         OR (NEW.published_at IS NOT NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NULL
             AND NEW.available_at = OLD.available_at)
         OR (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NOT NULL
             AND NEW.available_at > OLD.available_at
             AND NEW.available_at <= now() + interval '1 day')
         OR (NEW.published_at IS NULL AND NEW.terminal_disposition IN ('RETRIES_EXHAUSTED', 'PERMANENT_FAILURE')
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NOT NULL)
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only claim, release, publication, retry, terminal, suppression, and requeue transitions '
                    'are supported' USING ERRCODE = '23514';
END;
$$;

-- The queue-deadline reaper needs the same durable backoff the scheduler has: a run whose terminalization keeps
-- failing must not be retried every tick forever. It reuses run_scheduling_control rather than growing a second
-- framework, so control state now legitimately describes a QUEUED run as well as a CREATED one. The two uses can
-- never overlap, because scheduling deletes the control row at the moment the run leaves CREATED.
CREATE OR REPLACE FUNCTION guard_run_scheduling_control()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lifecycle varchar(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- FOR NO KEY UPDATE, not FOR KEY SHARE. KEY SHARE conflicts only with FOR UPDATE, and a plain lifecycle
    -- UPDATE takes FOR NO KEY UPDATE — so a KEY SHARE read sails past an uncommitted terminalization and admits
    -- control state for a run that commits as COMPLETED moments later. Such a row can never be updated again
    -- (this guard rejects it), is never cleared (the reaper only selects QUEUED runs), and is counted as
    -- quarantined forever. FOR NO KEY UPDATE conflicts with both, so the check reads a settled row.
    SELECT lifecycle_state INTO lifecycle FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
       FOR NO KEY UPDATE;
    IF lifecycle IS NULL OR lifecycle NOT IN ('CREATED', 'QUEUED') THEN
        RAISE EXCEPTION 'scheduling control state only applies to a run awaiting scheduling or termination'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.run_id <> OLD.run_id
                             OR NEW.organization_id <> OLD.organization_id
                             OR NEW.project_id <> OLD.project_id) THEN
        RAISE EXCEPTION 'scheduling control state cannot be reassigned to another run' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
