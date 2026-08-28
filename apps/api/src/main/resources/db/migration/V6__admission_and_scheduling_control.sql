-- V6 hardens the two amplification paths the automatic scheduler and relay created.
--
--   Admission: run creation now counts an organization's non-terminal runs under an organization-scoped
--   advisory lock. That count must not be a sequential scan over every run the platform has ever accepted, so it
--   gets its own partial index.
--
--   Scheduler backoff: a run whose scheduling fails was previously held back by an in-process map, which a
--   restart erased. The delay now lives in the database. It is deliberately a separate table rather than columns
--   on test_runs: scheduling is technical infrastructure state, and putting it on the aggregate would mean
--   relaxing the lifecycle guard that makes CREATED -> QUEUED the only permitted mutation.

-- Serves both admission counts: active (any non-terminal state) and queued. The predicate is deliberately
-- "not COMPLETED" rather than the two states that happen to exist today: baking CREATED and QUEUED into the
-- index would make the ceiling silently stop binding the moment QUEUED -> CLAIMED lands, and fixing it then
-- would need another migration.
CREATE INDEX ix_test_runs_admission
    ON test_runs (organization_id, lifecycle_state)
    WHERE lifecycle_state <> 'COMPLETED';

CREATE TABLE run_scheduling_control (
    run_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    failure_count integer NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    last_attempt_at timestamptz NOT NULL,
    last_failure_code varchar(64) NOT NULL,
    quarantined_at timestamptz,
    CONSTRAINT fk_run_scheduling_control_run
        FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES test_runs (organization_id, project_id, run_id),
    CONSTRAINT ck_run_scheduling_control_failures
        CHECK (failure_count BETWEEN 0 AND 1000),
    -- A deferral is not a failure: a run held back because its organization's queue is full has nothing wrong
    -- with it, so it must not accumulate failures or ever be quarantined for waiting.
    CONSTRAINT ck_run_scheduling_control_quarantine
        CHECK (quarantined_at IS NULL OR failure_count >= 1),
    CONSTRAINT ck_run_scheduling_control_schedule
        CHECK (next_attempt_at >= last_attempt_at)
);

-- No eligibility index: the scheduler drives from test_runs and reaches this table by primary key, so an index
-- on next_attempt_at would never be chosen and would only cost writes on every failed attempt.

-- Quarantined runs are an operational queue, not a lifecycle state. They are read by diagnostics only.
CREATE INDEX ix_run_scheduling_control_quarantined
    ON run_scheduling_control (quarantined_at, run_id)
    WHERE quarantined_at IS NOT NULL;

-- This table is technical state, so unlike the evidence tables it is deliberately mutable and deletable: the
-- scheduler updates it on every failure and removes it on success. What it must never do is drift from the run
-- it describes, so ownership is bound by the same composite foreign key as everything else, and a control row
-- may only exist for a run that has not yet been scheduled.
CREATE OR REPLACE FUNCTION guard_run_scheduling_control()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lifecycle varchar(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- FOR KEY SHARE serialises against a concurrent CREATED -> QUEUED transition, so the check cannot read a
    -- stale snapshot and admit control state for a run that is being scheduled right now.
    SELECT lifecycle_state INTO lifecycle FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
       FOR KEY SHARE;
    IF lifecycle IS DISTINCT FROM 'CREATED' THEN
        RAISE EXCEPTION 'scheduling control state only applies to a run awaiting scheduling'
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

CREATE TRIGGER run_scheduling_control_guard
BEFORE INSERT OR UPDATE OR DELETE ON run_scheduling_control
FOR EACH ROW EXECUTE FUNCTION guard_run_scheduling_control();
