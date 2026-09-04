-- V10 lets an authorized assignment actually run something, and lets what it ran come back.
--
-- Every earlier migration stopped work before it started. This one opens the four phases between owning an
-- attempt and finishing a run — PROVISIONING, RUNNING, COLLECTING_RESULTS, PROCESSING_RESULTS — and adds the
-- evidence a completed execution leaves behind.
--
-- What runs is a platform-owned synthetic workload. No feature source enters a sandbox, no secret is resolved,
-- and Karate is still absent. The point of this migration is that the LIFECYCLE composes correctly, proven with
-- a workload the platform wrote, before anything user-controlled is admitted.
--
-- THREE PROPERTIES GOVERN EVERYTHING BELOW.
--
-- NO STATE WITHOUT A BOUNDED EXIT. Each new phase carries a deadline and appears in a reconciler's index. An
-- earlier slice shipped a state whose only exit was a worker choosing to act, and the run sat in it forever
-- holding admission capacity. Every phase here has a success transition, a cancellation transition, and a
-- timeout transition before it is reachable at all.
--
-- OUTCOMES ARE ORTHOGONAL. "The infrastructure worked and the test failed" and "the infrastructure failed so
-- there is no test result" are different facts, and one column cannot carry both. A synthetic assertion failing
-- is infrastructure SUCCEEDED with test FAILED; a sandbox that would not start is infrastructure FAILED with
-- test NOT_AVAILABLE. Collapsing them would make every dashboard lie in one direction or the other.
--
-- RESULTS ARE EVIDENCE, NOT CLAIMS. A result is accepted only from the assignment that produced it, bound to
-- the run, attempt, epoch, command, and snapshot it belongs to, and it cannot be rewritten afterwards.

-- OPERATIONAL NOTE. This migration DOES transform validation over existing rows: it drops and re-adds the
-- lifecycle-event transition CHECK, the termination vocabulary CHECKs, the stop-reason CHECK, and the snapshot
-- engine CHECK, each of which is then validated against every row already present. It takes ACCESS EXCLUSIVE on
-- test_runs, execution_attempts, run_lifecycle_events, and run_snapshots for the duration and full-scans them
-- under it. This is a
-- maintenance window, not a rolling upgrade. The lock_timeout makes it fail fast rather than queue.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------
-- Phase deadlines
-- ---------------------------------------------------------------------------------------------------------

-- One deadline column, not four.
--
-- The phase a run is in already says which deadline applies, so a column per phase would be three NULLs and a
-- value at every instant, three more CHECKs to keep them consistent, and four indexes for a reconciler that
-- only ever asks one question: what is overdue. A single instant that each transition re-arms answers that
-- question with one index, and makes "this phase has no deadline" impossible to express by accident.
ALTER TABLE test_runs ADD COLUMN phase_deadline_at timestamptz;

-- When execution actually began, which is what a result's startedAt must agree with.
ALTER TABLE test_runs ADD COLUMN execution_started_at timestamptz;

ALTER TABLE test_runs
    -- Every non-terminal phase this slice opens must carry its deadline, and no terminal state may keep one.
    -- This is the constraint that makes "no state without a bounded exit" structural rather than a convention
    -- somebody remembers: a transition into an execution phase that forgot to arm the deadline is refused.
    ADD CONSTRAINT ck_test_runs_phase_deadline
        CHECK ((lifecycle_state IN ('PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS', 'PROCESSING_RESULTS'))
               = (phase_deadline_at IS NOT NULL)),
    -- Execution has a start instant exactly in the states where execution has started.
    ADD CONSTRAINT ck_test_runs_execution_started
        CHECK ((execution_started_at IS NOT NULL)
               <= (lifecycle_state IN ('RUNNING', 'COLLECTING_RESULTS', 'PROCESSING_RESULTS', 'STOPPING',
                                       'COMPLETED'))),
    -- The states that own an assignment now include the execution phases: each of them is a worker actively
    -- holding this attempt, so each needs the attempt to hold.
    DROP CONSTRAINT ck_test_runs_owned_states_have_attempt;

ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_owned_states_have_attempt
        CHECK (lifecycle_state NOT IN ('CLAIMED', 'PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS',
                                       'PROCESSING_RESULTS', 'STOPPING')
               OR current_attempt_id IS NOT NULL);

-- The deadline reconciler's selection. Partial, so its cost tracks work in flight rather than every run the
-- platform has ever created.
CREATE INDEX ix_test_runs_phase_deadline
    ON test_runs (phase_deadline_at, run_id)
    WHERE lifecycle_state IN ('PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS', 'PROCESSING_RESULTS');

-- ---------------------------------------------------------------------------------------------------------
-- Stop and termination vocabulary
-- ---------------------------------------------------------------------------------------------------------

-- Execution can now stop for reasons the earlier phases could not produce, and a successful run needs a
-- termination reason of its own — until now every terminal run had failed, been cancelled, or timed out, so
-- "finished normally" had no vocabulary at all.
ALTER TABLE test_runs DROP CONSTRAINT ck_test_runs_stop_reason;
ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_stop_reason
        CHECK (stop_reason IS NULL
               OR stop_reason IN ('USER_REQUESTED', 'LEASE_LOST', 'PROVISIONING_DEADLINE',
                                  'EXECUTION_DEADLINE', 'RESULT_DEADLINE', 'INFRASTRUCTURE_FAILURE'));

ALTER TABLE test_runs DROP CONSTRAINT ck_test_runs_termination_vocabulary;
ALTER TABLE test_runs DROP CONSTRAINT ck_test_runs_terminal_reason_outcome;
ALTER TABLE test_runs
    -- Reason, phase, and infrastructure outcome are one fact written three ways, and they may never disagree.
    -- Each arm names all three together so a row cannot claim to have timed out during cancellation, or to have
    -- succeeded while failing.
    ADD CONSTRAINT ck_test_runs_termination_vocabulary
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND termination_phase = 'CANCELLATION')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND termination_phase = 'QUEUE')
                      OR (termination_reason = 'LEASE_LOST' AND termination_phase = 'CLAIM')
                      OR (termination_reason = 'PROVISIONING_DEADLINE' AND termination_phase = 'PROVISIONING')
                      OR (termination_reason = 'EXECUTION_DEADLINE' AND termination_phase = 'EXECUTION')
                      OR (termination_reason = 'RESULT_DEADLINE' AND termination_phase = 'RESULTS')
                      OR (termination_reason = 'INFRASTRUCTURE_FAILURE' AND termination_phase = 'EXECUTION')
                      OR (termination_reason = 'EXECUTION_COMPLETED' AND termination_phase = 'EXECUTION'),
                      false)),
    ADD CONSTRAINT ck_test_runs_terminal_reason_outcome
        CHECK (termination_reason IS NULL
               OR coalesce(
                      (termination_reason = 'USER_REQUESTED' AND infrastructure_outcome = 'CANCELLED')
                      OR (termination_reason = 'QUEUE_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT')
                      OR (termination_reason = 'LEASE_LOST' AND infrastructure_outcome = 'FAILED')
                      OR (termination_reason = 'PROVISIONING_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT')
                      OR (termination_reason = 'EXECUTION_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT')
                      OR (termination_reason = 'RESULT_DEADLINE' AND infrastructure_outcome = 'TIMED_OUT')
                      OR (termination_reason = 'INFRASTRUCTURE_FAILURE' AND infrastructure_outcome = 'FAILED')
                      OR (termination_reason = 'EXECUTION_COMPLETED' AND infrastructure_outcome = 'SUCCEEDED'),
                      false)),
    -- The orthogonality rule, stated as a constraint rather than trusted to callers.
    --
    -- A test outcome exists only when the infrastructure got far enough to produce one. Infrastructure that
    -- failed, timed out, or was cancelled has no test result, and saying PASSED or FAILED there would be
    -- inventing evidence. Conversely a successful execution must say what the tests did.
    ADD CONSTRAINT ck_test_runs_outcomes_orthogonal
        CHECK (infrastructure_outcome IS NULL
               OR coalesce(
                      (infrastructure_outcome = 'SUCCEEDED') = (test_outcome IN ('PASSED', 'FAILED')),
                      false)),
    -- A stopping run's reason must survive terminalization unchanged, including the new reasons.
    DROP CONSTRAINT ck_test_runs_stop_reason_agrees;

ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_stop_reason_agrees
        CHECK (stop_reason IS NULL OR termination_reason IS NULL OR termination_reason = stop_reason);

-- ---------------------------------------------------------------------------------------------------------
-- Lifecycle transitions, rewritten as a unit
-- ---------------------------------------------------------------------------------------------------------

-- The previous version enumerated (sequence, from, to) triples: transition three was always CLAIMED to
-- STOPPING, transition four always STOPPING to COMPLETED. That worked while every path was the same length.
-- It cannot survive execution, where a run may stop from any of five states and therefore reach STOPPING at
-- sequence three, four, five, or six.
--
-- So the sequence is decoupled from the edge. What is still bound is the counter relationship — one transition
-- bumps the version once and appends exactly one event — and the set of edges that exist at all. Everything
-- outside that set is refused, which is what keeps the database failing closed beyond what is implemented.
ALTER TABLE run_lifecycle_events DROP CONSTRAINT ck_run_lifecycle_events_transition;
ALTER TABLE run_lifecycle_events
    ADD CONSTRAINT ck_run_lifecycle_events_transition CHECK (
        event_type = 'RUN_STATE_CHANGED'
        AND run_version = sequence + 1
        AND coalesce(
            -- Scheduling and the early terminal paths, unchanged.
            (previous_state = 'CREATED' AND lifecycle_state = 'QUEUED'
                AND attempt_id IS NOT NULL AND actor = 'kaas.scheduler')
            OR (previous_state = 'CREATED' AND lifecycle_state = 'COMPLETED' AND attempt_id IS NULL)
            OR (previous_state = 'QUEUED' AND lifecycle_state = 'COMPLETED' AND attempt_id IS NOT NULL)
            OR (previous_state = 'QUEUED' AND lifecycle_state = 'CLAIMED'
                AND attempt_id IS NOT NULL AND actor = 'kaas.dispatch-consumer')
            -- The execution path this slice opens. Each of these is driven by the assigned runner, so each
            -- carries an attempt.
            OR (previous_state = 'CLAIMED' AND lifecycle_state = 'PROVISIONING' AND attempt_id IS NOT NULL)
            OR (previous_state = 'PROVISIONING' AND lifecycle_state = 'RUNNING' AND attempt_id IS NOT NULL)
            OR (previous_state = 'RUNNING' AND lifecycle_state = 'COLLECTING_RESULTS' AND attempt_id IS NOT NULL)
            OR (previous_state = 'COLLECTING_RESULTS' AND lifecycle_state = 'PROCESSING_RESULTS'
                AND attempt_id IS NOT NULL)
            OR (previous_state = 'PROCESSING_RESULTS' AND lifecycle_state = 'COMPLETED' AND attempt_id IS NOT NULL)
            -- Stopping, from every state that can own an assignment. This is the arm the sequence-indexed
            -- version could not express.
            -- PROCESSING_RESULTS included. A tenant cannot cancel there, but the platform must be able to
            -- reclaim it: a worker that dies mid-submission would otherwise leave a run nothing can move. The
            -- run guard enforces WHO may do it; this only records that the edge exists.
            OR (previous_state IN ('CLAIMED', 'PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS',
                                   'PROCESSING_RESULTS')
                AND lifecycle_state = 'STOPPING' AND attempt_id IS NOT NULL)
            OR (previous_state = 'STOPPING' AND lifecycle_state = 'COMPLETED' AND attempt_id IS NOT NULL),
            false));

-- The event-to-transition binding needs the new phases too. PROVISIONING, RUNNING, COLLECTING_RESULTS and
-- PROCESSING_RESULTS are non-terminal and carry no completion instant, so they bind to the transition's audit
-- stamp exactly as CLAIMED and STOPPING already do — which the ELSE branch already covers. Only the terminal
-- branch needs to keep working, and it does. Rewritten here anyway rather than left alone, because a function
-- that is replaced is a function somebody must re-read, and CREATE OR REPLACE validates nothing about the rows
-- already present.
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
        SELECT count(*) INTO matching_run FROM test_runs
         WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
           AND lifecycle_state = 'COMPLETED' AND run_version = NEW.run_version
           AND completed_at = NEW.occurred_at AND updated_by = NEW.actor
           AND current_attempt_id IS NOT DISTINCT FROM NEW.attempt_id;
    ELSE
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
-- Attempt execution history
-- ---------------------------------------------------------------------------------------------------------

-- What the infrastructure actually did, kept on the attempt rather than the run.
--
-- The run says what happened to the user's request; the attempt says what happened to this particular piece of
-- infrastructure. They diverge the moment a second attempt exists, and recording execution history on the run
-- would mean losing the first attempt's story when the second overwrote it. There is only one attempt today,
-- which is exactly when this is cheap to get right.
ALTER TABLE execution_attempts
    ADD COLUMN provisioned_at timestamptz,
    ADD COLUMN execution_started_at timestamptz,
    ADD COLUMN execution_finished_at timestamptz,
    -- An opaque server-generated handle for the sandbox this attempt created. Deliberately not the container
    -- id: a container id is infrastructure detail that would leak through any surface this column reaches, and
    -- the reconciler matches on labels rather than on this value.
    ADD COLUMN sandbox_reference varchar(128),
    ADD COLUMN infrastructure_disposition varchar(32);

ALTER TABLE execution_attempts
    ADD CONSTRAINT ck_execution_attempts_disposition
        CHECK (infrastructure_disposition IS NULL
               OR infrastructure_disposition IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    -- Chronology. Each instant implies the one before it, and none may precede the attempt itself.
    ADD CONSTRAINT ck_execution_attempts_execution_chronology
        CHECK ((provisioned_at IS NULL OR provisioned_at >= created_at)
               AND (execution_started_at IS NULL
                    OR (provisioned_at IS NOT NULL AND execution_started_at >= provisioned_at))
               AND (execution_finished_at IS NULL
                    OR (execution_started_at IS NOT NULL AND execution_finished_at >= execution_started_at))),
    -- A sandbox reference exists exactly once provisioning has happened.
    ADD CONSTRAINT ck_execution_attempts_sandbox_reference
        CHECK ((sandbox_reference IS NOT NULL) <= (provisioned_at IS NOT NULL));

-- A composite FK needs a unique key on exactly the columns it references. command_id is already the primary
-- key of execution_commands, so this constraint adds no new uniqueness — it exists so the results table can
-- name the command AND the assignment together, which is what makes "this result answers the command this
-- assignment was issued" a property of the schema rather than of the code that happened to insert the row.
ALTER TABLE execution_commands
    ADD CONSTRAINT uq_execution_commands_identity UNIQUE (command_id, attempt_id, assignment_epoch);

-- ---------------------------------------------------------------------------------------------------------
-- Execution results
-- ---------------------------------------------------------------------------------------------------------

-- The evidence one execution produced, accepted once and never rewritten.
--
-- Bound to the full assignment identity rather than to the run alone. A result is a claim about what happened,
-- and the only thing that makes it evidence is that the assignment which produced it was the one authorized to
-- produce it. A stale worker holding a superseded epoch must not be able to complete a run — successfully or
-- otherwise — and binding the epoch here is what lets the acceptance path check that against live state.
CREATE TABLE execution_results (
    result_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (organization_id),
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    assignment_epoch integer NOT NULL,
    command_id uuid NOT NULL,
    -- Bare lowercase hex, matching test_runs.snapshot_sha256 so the two compare without normalising.
    run_snapshot_sha256 varchar(64) NOT NULL,
    -- A canonical semantic digest over the result document, computed the same way the command's is.
    result_digest varchar(71) NOT NULL,
    test_outcome varchar(32) NOT NULL,
    infrastructure_outcome varchar(32) NOT NULL,
    document jsonb NOT NULL,
    submitted_at timestamptz NOT NULL,
    CONSTRAINT ck_execution_results_epoch CHECK (assignment_epoch BETWEEN 1 AND 1000),
    CONSTRAINT ck_execution_results_snapshot CHECK (run_snapshot_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_results_digest CHECK (result_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_results_test_outcome
        CHECK (test_outcome IN ('PASSED', 'FAILED', 'NOT_AVAILABLE')),
    CONSTRAINT ck_execution_results_infrastructure_outcome
        CHECK (infrastructure_outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    -- The same orthogonality the run enforces, restated where the evidence is written. A result that claims a
    -- test outcome without a successful execution is describing something that did not happen.
    CONSTRAINT ck_execution_results_outcomes_orthogonal
        CHECK ((infrastructure_outcome = 'SUCCEEDED') = (test_outcome IN ('PASSED', 'FAILED'))),
    -- octet_length over the text form, not pg_column_size: the two agree at INSERT and diverge afterwards,
    -- because pg_column_size returns the COMPRESSED size of a stored datum.
    CONSTRAINT ck_execution_results_size CHECK (octet_length(document::text) <= 262144),
    CONSTRAINT ck_execution_results_document_shape CHECK (jsonb_typeof(document) = 'object'),
    -- One result per assignment. A second result for the same attempt and epoch would be a second account of
    -- one execution, and nothing could say which was true.
    CONSTRAINT uq_execution_results_assignment UNIQUE (attempt_id, assignment_epoch),
    CONSTRAINT fk_execution_results_attempt
        FOREIGN KEY (organization_id, project_id, run_id, attempt_id)
        REFERENCES execution_attempts (organization_id, project_id, run_id, attempt_id),
    -- The command this result answers, bound structurally rather than only by the acceptance path.
    --
    -- The header above says a result is bound to "the run, attempt, epoch, command, and snapshot it belongs
    -- to". Four of those five were enforced; command_id was a bare uuid naming anything at all, including a
    -- command belonging to another organization. The acceptance path does check it, thoroughly — but this table
    -- is read back as authoritative evidence by require_execution_evidence, and evidence whose binding lives
    -- only in the code that wrote it is evidence one repair script away from being wrong.
    --
    -- Composite on (command_id, attempt_id, assignment_epoch) rather than command_id alone, so the command must
    -- belong to THIS assignment. uq_execution_commands_assignment makes that a real key.
    CONSTRAINT fk_execution_results_command
        FOREIGN KEY (command_id, attempt_id, assignment_epoch)
        REFERENCES execution_commands (command_id, attempt_id, assignment_epoch)
);

CREATE INDEX ix_execution_results_run ON execution_results (run_id, submitted_at DESC);

-- Accepted evidence is never edited and never removed. A worker that could rewrite its own result could turn a
-- failure into a success after the fact, which is the one thing a result store must not permit.
CREATE OR REPLACE FUNCTION reject_execution_result_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'execution results are immutable evidence' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_results_immutable
BEFORE UPDATE OR DELETE ON execution_results
FOR EACH ROW EXECUTE FUNCTION reject_execution_result_mutation();

CREATE TRIGGER execution_results_untruncatable
BEFORE TRUNCATE ON execution_results
FOR EACH STATEMENT EXECUTE FUNCTION reject_execution_result_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- Completion requires evidence
-- ---------------------------------------------------------------------------------------------------------

-- A run that completed through the execution path must have the result that justified its outcome.
--
-- Stated as a trigger rather than a CHECK because it spans two tables. Terminal runs that never executed —
-- cancelled in the queue, timed out before a claim, fenced for a lost lease — have no result and must not be
-- required to have one, so the condition is keyed on the termination reason rather than on being terminal.
CREATE OR REPLACE FUNCTION require_execution_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE evidence integer;
BEGIN
    IF NEW.lifecycle_state = 'COMPLETED' AND NEW.termination_reason = 'EXECUTION_COMPLETED' THEN
        SELECT count(*) INTO evidence FROM execution_results
         WHERE run_id = NEW.run_id AND attempt_id = NEW.current_attempt_id
           AND test_outcome = NEW.test_outcome
           AND infrastructure_outcome = NEW.infrastructure_outcome;
        IF evidence <> 1 THEN
            RAISE EXCEPTION 'a run completed through execution must carry the result that produced its outcome'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER test_runs_execution_evidence
BEFORE INSERT OR UPDATE ON test_runs
FOR EACH ROW EXECUTE FUNCTION require_execution_evidence();

-- ---------------------------------------------------------------------------------------------------------
-- Supported updates, rewritten as a unit
-- ---------------------------------------------------------------------------------------------------------

-- This guard says which column-shapes a transition may move, and it has to be replaced wholesale rather than
-- extended: the execution phases add five transitions, widen STOPPING's source states from one to four, and
-- introduce the first terminal transition that carries a real test outcome. Adding arms to the old function
-- while leaving its assumptions in place is how a guard ends up permitting a shape nobody intended.
--
-- `CREATE OR REPLACE FUNCTION` validates nothing about rows already present — it replaces a definition and
-- checks no history. Nothing here relies on it doing more than that: every constraint that must hold over
-- existing data is a CHECK, dropped and re-added above so PostgreSQL validates it.
--
-- The shape of each arm is deliberate and repeated: the set of columns that may move, the exact states it moves
-- between, the version increment, the audit-stamp ordering, and the actor entitled to it. A transition that
-- satisfies all five is supported; everything else raises.
CREATE OR REPLACE FUNCTION guard_supported_test_run_update()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unchanged_except_scheduling boolean;
DECLARE unchanged_except_claim boolean;
DECLARE unchanged_except_phase boolean;
DECLARE unchanged_except_stopping boolean;
DECLARE unchanged_except_terminal boolean;
BEGIN
    unchanged_except_scheduling :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
         - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
           - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at');

    unchanged_except_claim :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'updated_by' - 'updated_at');

    -- Columns an execution-phase transition may move: the lifecycle, the version, the phase deadline, the
    -- execution start instant, and the audit stamps. Deliberately absent are every outcome column, the
    -- cancellation columns, the stop reason, the attempt reference, and the snapshot digest. A runner advancing
    -- its own phase must not be able to write an outcome, acknowledge a cancellation, or repoint the run at a
    -- different attempt — those are three separate authorities and this is none of them.
    unchanged_except_phase :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'phase_deadline_at' - 'execution_started_at'
         - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'phase_deadline_at' - 'execution_started_at'
           - 'updated_by' - 'updated_at');

    -- Entering STOPPING clears the phase deadline as well, because the phase that deadline bounded is over and
    -- the settlement window is the reconciler's concern rather than a worker's.
    unchanged_except_stopping :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'stop_reason' - 'cancellation_status'
         - 'cancellation_requested_at' - 'phase_deadline_at' - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'stop_reason' - 'cancellation_status'
           - 'cancellation_requested_at' - 'phase_deadline_at' - 'updated_by' - 'updated_at');

    unchanged_except_terminal :=
        (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
         - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
         - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'phase_deadline_at'
         - 'updated_by' - 'updated_at')
        = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'test_outcome' - 'infrastructure_outcome'
           - 'completed_at' - 'termination_reason' - 'termination_phase' - 'cancellation_status'
           - 'cancellation_requested_at' - 'cancellation_acknowledged_at' - 'phase_deadline_at'
           - 'updated_by' - 'updated_at');

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

    -- CLAIM: QUEUED -> CLAIMED.
    IF coalesce(unchanged_except_claim, false)
       AND OLD.lifecycle_state = 'QUEUED' AND NEW.lifecycle_state = 'CLAIMED'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.current_attempt_id IS NOT NULL
       AND NEW.updated_at >= OLD.updated_at
       AND NEW.updated_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.updated_at <= OLD.queue_deadline_at
       AND NEW.updated_by = 'kaas.dispatch-consumer' THEN
        RETURN NEW;
    END IF;

    -- EXECUTION PHASES: the four transitions the assigned runner drives.
    --
    -- One arm for all of them, because they differ only in which pair of states they connect. What they share is
    -- what matters: a deadline must be armed on arrival, the version moves by one, nothing but the phase columns
    -- moves, and the actor is a platform worker rather than a tenant. The pair itself is enumerated so an
    -- unimplemented edge — RUNNING straight to PROCESSING_RESULTS, say — is refused here as well as by the
    -- lifecycle-event CHECK.
    IF coalesce(unchanged_except_phase, false)
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.phase_deadline_at IS NOT NULL
       AND NEW.updated_at >= OLD.updated_at
       AND NEW.updated_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.phase_deadline_at > NEW.updated_at
       AND NEW.updated_by LIKE 'kaas.worker.%'
       AND ((OLD.lifecycle_state = 'CLAIMED' AND NEW.lifecycle_state = 'PROVISIONING'
             AND NEW.execution_started_at IS NULL)
            -- RUNNING is where execution begins, so it is the one arm that stamps the start instant, and it
            -- must stamp it as the transition's own instant rather than an arbitrary earlier one.
            OR (OLD.lifecycle_state = 'PROVISIONING' AND NEW.lifecycle_state = 'RUNNING'
                AND OLD.execution_started_at IS NULL
                AND NEW.execution_started_at = NEW.updated_at)
            OR (OLD.lifecycle_state = 'RUNNING' AND NEW.lifecycle_state = 'COLLECTING_RESULTS'
                AND NEW.execution_started_at = OLD.execution_started_at)
            OR (OLD.lifecycle_state = 'COLLECTING_RESULTS' AND NEW.lifecycle_state = 'PROCESSING_RESULTS'
                AND NEW.execution_started_at = OLD.execution_started_at)) THEN
        RETURN NEW;
    END IF;

    -- STOP: an owned phase -> STOPPING.
    --
    -- WHICH phases depends on WHO is stopping it, so the source-state set is inside each reason arm rather than
    -- shared above them.
    --
    -- A tenant may cancel up to COLLECTING_RESULTS. PROCESSING_RESULTS is deliberately excluded: by then the
    -- execution is over and the sandbox is gone, so cancelling buys nothing and would throw away evidence the
    -- platform already paid to produce.
    --
    -- The PLATFORM must be able to stop all five, PROCESSING_RESULTS included. A phase the reconciler cannot
    -- act on is a phase with no bounded exit — a worker that dies mid-submission would leave the run holding
    -- admission capacity until somebody noticed by hand. That is the failure this migration's own header says
    -- it exists to prevent, and an earlier draft of this guard reintroduced it by sharing one source-state list
    -- across every reason.
    IF coalesce(unchanged_except_stopping, false)
       AND NEW.lifecycle_state = 'STOPPING'
       AND NEW.run_version = OLD.run_version + 1
       AND NEW.phase_deadline_at IS NULL
       AND NEW.updated_at >= OLD.updated_at
       AND NEW.updated_at <= greatest(
               clock_timestamp(), OLD.updated_at,
               coalesce(NEW.cancellation_requested_at, timestamptz '-infinity'))
       AND OLD.stop_reason IS NULL AND NEW.stop_reason IS NOT NULL
       AND NEW.completed_at IS NULL AND NEW.termination_reason IS NULL
       AND (
            (NEW.stop_reason = 'USER_REQUESTED'
             AND OLD.lifecycle_state IN ('CLAIMED', 'PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS')
             AND OLD.cancellation_status = 'NOT_REQUESTED'
             AND NEW.cancellation_status = 'REQUESTED'
             AND NEW.cancellation_requested_at IS NOT NULL
             AND NEW.cancellation_requested_at >= OLD.created_at
             AND NEW.updated_by NOT LIKE 'kaas.%')
         OR (NEW.stop_reason = 'LEASE_LOST'
             AND OLD.lifecycle_state IN ('CLAIMED', 'PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS',
                                         'PROCESSING_RESULTS')
             AND NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at
             AND NEW.updated_by = 'kaas.lease-reconciler')
            -- The three execution deadlines and an infrastructure failure. Each is a platform decision about
            -- work nobody asked to stop, so none of them may record a cancellation, and each is pinned to the
            -- actor entitled to make it: a deadline is the reconciler's to declare, a failure the runner's to
            -- report about its own sandbox.
         OR (NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at
             AND OLD.phase_deadline_at IS NOT NULL
             -- The deadline that actually elapsed, not merely some deadline. Pairing each reason with the
             -- phase it can expire in stops a reconciler recording a provisioning timeout against a run that
             -- was executing, which would be a lie in exactly the place operators go to find out what broke.
             AND ((NEW.stop_reason = 'PROVISIONING_DEADLINE' AND OLD.lifecycle_state = 'PROVISIONING')
                  OR (NEW.stop_reason = 'EXECUTION_DEADLINE' AND OLD.lifecycle_state = 'RUNNING')
                  OR (NEW.stop_reason = 'RESULT_DEADLINE'
                      AND OLD.lifecycle_state IN ('COLLECTING_RESULTS', 'PROCESSING_RESULTS')))
             AND NEW.updated_at >= OLD.phase_deadline_at
             AND NEW.updated_by = 'kaas.execution-reconciler')
         OR (NEW.stop_reason = 'INFRASTRUCTURE_FAILURE'
             AND OLD.lifecycle_state IN ('PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS',
                                         'PROCESSING_RESULTS')
             AND NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at
             AND NEW.updated_by LIKE 'kaas.worker.%')
       ) THEN
        RETURN NEW;
    END IF;

    -- COMPLETE WITH RESULT: PROCESSING_RESULTS -> COMPLETED.
    --
    -- The only transition in the system that may write a test outcome, and the only one whose infrastructure
    -- outcome is SUCCEEDED. Everything else that ends a run ends it without a test result, because nothing ran
    -- to completion. The evidence trigger checks separately that the result which produced this outcome exists.
    IF coalesce(unchanged_except_terminal, false)
       AND OLD.lifecycle_state = 'PROCESSING_RESULTS' AND NEW.lifecycle_state = 'COMPLETED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
       AND NEW.updated_at = NEW.completed_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.completed_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.phase_deadline_at IS NULL
       AND NEW.termination_reason = 'EXECUTION_COMPLETED'
       AND NEW.infrastructure_outcome = 'SUCCEEDED'
       AND NEW.test_outcome IN ('PASSED', 'FAILED')
       AND NEW.stop_reason IS NULL
       AND NEW.cancellation_status = OLD.cancellation_status
       AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at
       AND NEW.cancellation_acknowledged_at IS NOT DISTINCT FROM OLD.cancellation_acknowledged_at
       AND NEW.updated_by LIKE 'kaas.worker.%' THEN
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
       AND NEW.completed_at <= greatest(
               clock_timestamp(), OLD.updated_at,
               coalesce(NEW.cancellation_requested_at, timestamptz '-infinity'))
       AND (
            (NEW.termination_reason = 'USER_REQUESTED'
             AND NEW.cancellation_status = 'ACKNOWLEDGED'
             AND NEW.cancellation_requested_at IS NOT NULL
             AND NEW.cancellation_requested_at >= OLD.created_at
             AND NEW.cancellation_acknowledged_at = NEW.completed_at
             AND NEW.updated_by NOT LIKE 'kaas.%')
         OR (NEW.termination_reason = 'QUEUE_DEADLINE'
             AND NEW.cancellation_status = 'NOT_REQUESTED'
             AND NEW.cancellation_requested_at IS NULL
             AND OLD.lifecycle_state = 'QUEUED'
             AND OLD.queue_deadline_at IS NOT NULL
             AND NEW.completed_at >= OLD.queue_deadline_at
             AND NEW.updated_by = 'kaas.queue-reaper')
       ) THEN
        RETURN NEW;
    END IF;

    -- SETTLE: STOPPING -> COMPLETED. The outcome was fixed when the run entered STOPPING.
    IF coalesce(unchanged_except_terminal, false)
       AND OLD.lifecycle_state = 'STOPPING' AND NEW.lifecycle_state = 'COMPLETED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
       AND NEW.updated_at = NEW.completed_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.completed_at <= greatest(clock_timestamp(), OLD.updated_at)
       AND NEW.test_outcome = 'NOT_AVAILABLE'
       AND NEW.termination_reason = OLD.stop_reason
       AND NEW.updated_by IN ('kaas.lease-reconciler', 'kaas.execution-reconciler')
       AND (
            (OLD.stop_reason = 'USER_REQUESTED'
             AND OLD.cancellation_status = 'REQUESTED'
             AND NEW.cancellation_status = 'ACKNOWLEDGED'
             AND NEW.cancellation_acknowledged_at = NEW.completed_at
             AND NEW.cancellation_requested_at = OLD.cancellation_requested_at)
         OR (OLD.stop_reason IN ('LEASE_LOST', 'PROVISIONING_DEADLINE', 'EXECUTION_DEADLINE',
                                 'RESULT_DEADLINE', 'INFRASTRUCTURE_FAILURE')
             AND NEW.cancellation_status = OLD.cancellation_status
             AND NEW.cancellation_acknowledged_at IS NOT DISTINCT FROM OLD.cancellation_acknowledged_at
             AND NEW.cancellation_requested_at IS NOT DISTINCT FROM OLD.cancellation_requested_at)
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only scheduling, claim, execution, stop, and terminal transitions are supported'
        USING ERRCODE = '23514';
END;
$$;

-- ---------------------------------------------------------------------------------------------------------
-- The engine a snapshot declares
-- ---------------------------------------------------------------------------------------------------------

-- V3 pinned this to 'KARATE', which was false in a way that only became dangerous once something could
-- actually execute a run. No Karate exists anywhere in this repository, so every snapshot and every command
-- declared an engine that could not have produced their results. The moment a runner started executing, that
-- would have meant synthetic shell assertions reported as a Karate suite to every dashboard and every operator.
--
-- SYNTHETIC is the platform's own deterministic workload — the honest name for what actually runs today.
-- KARATE stays valid so the model can be built toward it; the runner refuses to execute it, because it has no
-- Karate to run. Same shape as the network policy: represented, unenforceable here, refused rather than
-- silently degraded.
--
-- This revalidates against every existing row. All of them say KARATE, which the widened check still admits,
-- so the transformation is a widening and no historical row is invalidated by it.
ALTER TABLE run_snapshots DROP CONSTRAINT ck_run_snapshots_engine;
ALTER TABLE run_snapshots
    ADD CONSTRAINT ck_run_snapshots_engine CHECK (
        engine IN ('SYNTHETIC', 'KARATE')
        AND engine_version ~ '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$');

-- ---------------------------------------------------------------------------------------------------------
-- Attempt transitions, rewritten as a unit
-- ---------------------------------------------------------------------------------------------------------

-- V8's guard treated every column outside the assignment as immutable, which was correct while an attempt had
-- nothing to record but who owned it. This slice gives an attempt a history — when it was provisioned, when
-- execution started and finished, which sandbox it created, how it ended — and every one of those writes fails
-- V8's identity check.
--
-- Rewritten whole rather than patched, for the same reason the run guard was: the arms below share a notion of
-- what "unchanged" means, and adding a fourth arm with a different one is how a guard comes to permit a shape
-- nobody chose.
--
-- THE SEPARATION THAT MATTERS: an execution-history write may not touch the assignment, and an assignment
-- transition may not touch the history. They are different authorities — one is a worker reporting what it did,
-- the other is the platform deciding who owns the work — and a single statement doing both would let a worker
-- extend its own lease while reporting progress.
CREATE OR REPLACE FUNCTION guard_execution_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_run integer;
DECLARE identity_unchanged boolean;
DECLARE history_only boolean;
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

    -- An ASSIGNMENT transition moves only the assignment. Execution history is excluded from this set, so a
    -- claim, heartbeat, or fence that also wrote history is refused rather than silently accepted.
    identity_unchanged := (to_jsonb(NEW) - 'attempt_state' - 'assignment_epoch' - 'assigned_worker_id'
                           - 'lease_started_at' - 'lease_expires_at' - 'last_heartbeat_at' - 'fenced_at'
                           - 'acquired_at')
                        = (to_jsonb(OLD) - 'attempt_state' - 'assignment_epoch' - 'assigned_worker_id'
                           - 'lease_started_at' - 'lease_expires_at' - 'last_heartbeat_at' - 'fenced_at'
                           - 'acquired_at');

    -- A HISTORY write moves only history. The complement of the set above, deliberately disjoint from it: no
    -- single statement can satisfy both, which is what keeps the two authorities apart.
    history_only := (to_jsonb(NEW) - 'provisioned_at' - 'execution_started_at' - 'execution_finished_at'
                     - 'sandbox_reference' - 'infrastructure_disposition')
                  = (to_jsonb(OLD) - 'provisioned_at' - 'execution_started_at' - 'execution_finished_at'
                     - 'sandbox_reference' - 'infrastructure_disposition');

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

    -- EXECUTION HISTORY: the assigned worker recording what it did.
    --
    -- Every field is write-once. A second provisioning instant, a second start, or a rewritten disposition
    -- would be an attempt rewriting its own past, and the whole value of this row as evidence is that it
    -- cannot. Chronology between the instants is a CHECK rather than a condition here, so it holds for any
    -- writer, including one that bypasses this trigger.
    --
    -- The assignment is untouched: same state, same epoch, same worker, same lease, same heartbeat, still
    -- unfenced. A worker reporting progress must not be able to renew its own lease as a side effect.
    IF coalesce(history_only, false)
       AND OLD.attempt_state = 'CLAIMED' AND NEW.attempt_state = 'CLAIMED'
       AND OLD.fenced_at IS NULL AND NEW.fenced_at IS NULL
       -- Still live. A fenced or lapsed assignment writing history would be a worker that has already lost the
       -- work continuing to describe it.
       AND OLD.lease_expires_at > clock_timestamp()
       AND (
            -- PROVISIONED: the sandbox exists, and its reference arrives with it.
            (OLD.provisioned_at IS NULL AND NEW.provisioned_at IS NOT NULL
             AND OLD.sandbox_reference IS NULL AND NEW.sandbox_reference IS NOT NULL
             AND NEW.execution_started_at IS NOT DISTINCT FROM OLD.execution_started_at
             AND NEW.execution_finished_at IS NOT DISTINCT FROM OLD.execution_finished_at
             AND NEW.infrastructure_disposition IS NOT DISTINCT FROM OLD.infrastructure_disposition
             -- Clamped, not bare. This instant is application-sourced (the same value the run guard
             -- bounds with greatest(...) a few hundred lines above), so a bare clock_timestamp()
             -- refuses an ordinary write whenever the API host leads the database — which is the
             -- failure this file's own fencing comment describes and then does not guard against.
             AND NEW.provisioned_at <= greatest(clock_timestamp(), OLD.created_at))
            -- STARTED: execution began. Requires provisioning to have happened, so the order cannot invert.
         OR (OLD.execution_started_at IS NULL AND NEW.execution_started_at IS NOT NULL
             AND OLD.provisioned_at IS NOT NULL
             AND NEW.provisioned_at = OLD.provisioned_at
             AND NEW.sandbox_reference = OLD.sandbox_reference
             AND NEW.execution_finished_at IS NOT DISTINCT FROM OLD.execution_finished_at
             AND NEW.infrastructure_disposition IS NOT DISTINCT FROM OLD.infrastructure_disposition
             -- Clamped, not bare. This instant is application-sourced (the same value the run guard
             -- bounds with greatest(...) a few hundred lines above), so a bare clock_timestamp()
             -- refuses an ordinary write whenever the API host leads the database — which is the
             -- failure this file's own fencing comment describes and then does not guard against.
             AND NEW.execution_started_at <= greatest(clock_timestamp(), OLD.created_at))
            -- FINISHED: the workload is over and the sandbox can go.
         OR (OLD.execution_finished_at IS NULL AND NEW.execution_finished_at IS NOT NULL
             AND OLD.execution_started_at IS NOT NULL
             AND NEW.provisioned_at = OLD.provisioned_at
             AND NEW.sandbox_reference = OLD.sandbox_reference
             AND NEW.execution_started_at = OLD.execution_started_at
             AND NEW.infrastructure_disposition IS NOT DISTINCT FROM OLD.infrastructure_disposition
             -- Clamped, not bare. This instant is application-sourced (the same value the run guard
             -- bounds with greatest(...) a few hundred lines above), so a bare clock_timestamp()
             -- refuses an ordinary write whenever the API host leads the database — which is the
             -- failure this file's own fencing comment describes and then does not guard against.
             AND NEW.execution_finished_at <= greatest(clock_timestamp(), OLD.created_at))
            -- DISPOSITION: how it ended, written once.
            --
            -- SUCCEEDED requires a finished execution, because that is what succeeding means. The other three
            -- deliberately do NOT: an infrastructure failure is precisely the case where execution never
            -- finished, and requiring execution_finished_at made this column structurally write-once-SUCCEEDED.
            -- The one thing an attempt's own record of "what happened to this piece of infrastructure" could
            -- never say was that it broke.
         OR (OLD.infrastructure_disposition IS NULL AND NEW.infrastructure_disposition IS NOT NULL
             AND (OLD.execution_finished_at IS NOT NULL
                  OR NEW.infrastructure_disposition <> 'SUCCEEDED')
             AND NEW.provisioned_at IS NOT DISTINCT FROM OLD.provisioned_at
             AND NEW.sandbox_reference IS NOT DISTINCT FROM OLD.sandbox_reference
             AND NEW.execution_started_at IS NOT DISTINCT FROM OLD.execution_started_at
             AND NEW.execution_finished_at IS NOT DISTINCT FROM OLD.execution_finished_at)
       ) THEN
        RETURN NEW;
    END IF;

    -- ACQUIRE: the first authenticated worker binds the assignment to itself.
    --
    -- Write-once by construction (`OLD.acquired_at IS NULL`), so a second worker cannot take an attempt that is
    -- already held, and the holder cannot be swapped underneath a running execution. The epoch and the lease are
    -- untouched: acquisition says WHO holds this assignment, not that a new assignment began.
    IF coalesce(identity_unchanged, false)
       AND OLD.attempt_state = 'CLAIMED' AND NEW.attempt_state = 'CLAIMED'
       AND OLD.acquired_at IS NULL AND NEW.acquired_at IS NOT NULL
       AND OLD.fenced_at IS NULL AND NEW.fenced_at IS NULL
       AND NEW.assignment_epoch = OLD.assignment_epoch
       AND NEW.assigned_worker_id IS NOT NULL
       AND NEW.lease_started_at = OLD.lease_started_at
       AND NEW.lease_expires_at = OLD.lease_expires_at
       AND NEW.last_heartbeat_at = OLD.last_heartbeat_at
       -- Only a live assignment can be acquired. Acquiring a lapsed one would let a worker take ownership by
       -- being late rather than by being first.
       AND OLD.lease_expires_at > clock_timestamp()
       -- Clamped upward like every other application-sourced instant in this schema.
       AND NEW.acquired_at <= greatest(clock_timestamp(), OLD.created_at) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only claim, acquire, heartbeat, fence, and execution-history transitions are supported'
        USING ERRCODE = '23514';
END;
$$;

-- ---------------------------------------------------------------------------------------------------------
-- The scheduling bundle, rewritten as a unit
-- ---------------------------------------------------------------------------------------------------------

-- This is the deferred constraint that checks, at commit, that a run and its children agree. Its final branch
-- refuses any run that has scheduling children while being in a state no implemented path produces — which is
-- exactly right, and which means every state this slice adds has to be accounted for here or the whole
-- execution path is refused at commit.
--
-- The four execution phases join CLAIMED as owned states: one attempt, holding the live assignment. The
-- terminal branch is unchanged and still requires the assignment to be gone, which is why completion now fences
-- the attempt rather than leaving it CLAIMED behind a finished run.
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
    ELSIF run_record.lifecycle_state IN ('CLAIMED', 'PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS',
                                        'PROCESSING_RESULTS') THEN
        -- Every state in which a worker owns the run. They share one rule because they share one property:
        -- the run owns exactly the attempt it names, and that attempt holds the live assignment. Listing only
        -- CLAIMED here — which is what V8 did, correctly, when nothing else could own a run — sent all four
        -- execution phases to the final branch and refused them as unimplemented states.
        IF scheduling_children <> 1 OR live_assignments <> 1 THEN
            RAISE EXCEPTION 'an owned run requires exactly one attempt holding the active assignment'
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

-- ---------------------------------------------------------------------------------------------------------
-- Assignment acquisition
-- ---------------------------------------------------------------------------------------------------------

-- WHO the assignment belongs to, as opposed to which process happened to claim it.
--
-- `assigned_worker_id` is written by the dispatch consumer from its own configuration, and that value is one
-- constant for the whole deployment. Every run in every organization is therefore assigned to the same string.
-- Comparing an authenticated caller against it proves that the caller is *a* worker, never that it is *the*
-- worker holding this attempt — which defeats the assignment epoch, because fencing assumes identity
-- distinguishes one holder from another. Two workers could both satisfy every ownership check on one run at
-- the same time, and nothing in the audit trail could tell them apart afterwards.
--
-- Acquisition closes that. The first authenticated worker to authorize an attempt binds the assignment to
-- itself, once, and every later check compares against that. A second worker is refused rather than admitted
-- alongside the first.
--
-- This does NOT create a tenant boundary at the worker layer, and nothing here should be read as claiming one:
-- workers are a shared pool consuming one queue, so any worker may legitimately acquire any run. What it
-- creates is EXCLUSIVITY and ATTRIBUTION — one holder at a time, named in the audit trail, and revocable
-- individually.
ALTER TABLE execution_attempts ADD COLUMN acquired_at timestamptz;

ALTER TABLE execution_attempts
    -- Acquisition presupposes an assignment. An unassigned attempt has nothing to acquire.
    ADD CONSTRAINT ck_execution_attempts_acquired
        CHECK ((acquired_at IS NOT NULL) <= (assignment_epoch IS NOT NULL AND acquired_at >= created_at));
