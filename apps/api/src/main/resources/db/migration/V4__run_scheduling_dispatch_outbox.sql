ALTER TABLE test_runs ADD COLUMN current_attempt_id uuid;

ALTER TABLE run_snapshots
    ADD CONSTRAINT uq_run_snapshots_exact_digest
    UNIQUE (organization_id, project_id, run_id, content_sha256);

CREATE TABLE execution_attempts (
    attempt_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    attempt_state varchar(32) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_execution_attempts_run
        FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES test_runs (organization_id, project_id, run_id),
    CONSTRAINT uq_execution_attempts_identity
        UNIQUE (organization_id, project_id, run_id, attempt_id),
    CONSTRAINT uq_execution_attempts_one_per_run
        UNIQUE (organization_id, project_id, run_id),
    CONSTRAINT uq_execution_attempts_number
        UNIQUE (organization_id, project_id, run_id, attempt_number),
    CONSTRAINT ck_execution_attempts_initial_number CHECK (attempt_number = 1),
    CONSTRAINT ck_execution_attempts_initial_state CHECK (attempt_state = 'WAITING_FOR_CLAIM'),
    CONSTRAINT ck_execution_attempts_actor CHECK (created_by = 'kaas.scheduler')
);

ALTER TABLE test_runs
    ADD CONSTRAINT fk_test_runs_current_attempt
    FOREIGN KEY (organization_id, project_id, run_id, current_attempt_id)
    REFERENCES execution_attempts (organization_id, project_id, run_id, attempt_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE test_runs
    ADD CONSTRAINT ck_test_runs_created_attempt_absent
        CHECK (lifecycle_state <> 'CREATED' OR current_attempt_id IS NULL),
    ADD CONSTRAINT ck_test_runs_queued_bundle_present
        CHECK (lifecycle_state <> 'QUEUED' OR (
            queued_at IS NOT NULL AND queue_deadline_at IS NOT NULL AND current_attempt_id IS NOT NULL));

CREATE TABLE execution_dispatches (
    dispatch_id uuid PRIMARY KEY,
    message_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    run_version bigint NOT NULL,
    attempt_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    run_snapshot_id uuid NOT NULL,
    run_snapshot_sha256 varchar(64) NOT NULL,
    schema_version varchar(16) NOT NULL,
    message_type varchar(64) NOT NULL,
    producer varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    queue_deadline_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    CONSTRAINT fk_execution_dispatches_attempt
        FOREIGN KEY (organization_id, project_id, run_id, attempt_id)
        REFERENCES execution_attempts (organization_id, project_id, run_id, attempt_id),
    CONSTRAINT fk_execution_dispatches_snapshot
        FOREIGN KEY (organization_id, project_id, run_snapshot_id, run_snapshot_sha256)
        REFERENCES run_snapshots (organization_id, project_id, run_id, content_sha256),
    CONSTRAINT uq_execution_dispatches_identity_digest
        UNIQUE (dispatch_id, message_id, organization_id, project_id, run_id, payload_sha256),
    CONSTRAINT uq_execution_dispatches_attempt
        UNIQUE (organization_id, project_id, run_id, attempt_id),
    CONSTRAINT ck_execution_dispatches_version
        CHECK (run_version BETWEEN 2 AND 9007199254740991),
    CONSTRAINT ck_execution_dispatches_attempt_number CHECK (attempt_number = 1),
    CONSTRAINT ck_execution_dispatches_snapshot_identity CHECK (run_snapshot_id = run_id),
    CONSTRAINT ck_execution_dispatches_contract CHECK (
        schema_version = '1.0' AND message_type = 'EXECUTION_DISPATCH' AND producer = 'kaas.scheduler'),
    CONSTRAINT ck_execution_dispatches_timing CHECK (queue_deadline_at > occurred_at),
    CONSTRAINT ck_execution_dispatches_digests CHECK (
        run_snapshot_sha256 ~ '^[a-f0-9]{64}$' AND payload_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_dispatches_payload_shape CHECK (
        jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 16384)
);

CREATE TABLE run_lifecycle_events (
    event_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    run_version bigint NOT NULL,
    sequence bigint NOT NULL,
    event_type varchar(64) NOT NULL,
    previous_state varchar(32) NOT NULL,
    lifecycle_state varchar(32) NOT NULL,
    attempt_id uuid NOT NULL,
    actor varchar(255) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT fk_run_lifecycle_events_attempt
        FOREIGN KEY (organization_id, project_id, run_id, attempt_id)
        REFERENCES execution_attempts (organization_id, project_id, run_id, attempt_id),
    CONSTRAINT uq_run_lifecycle_events_version UNIQUE (organization_id, project_id, run_id, run_version),
    CONSTRAINT uq_run_lifecycle_events_sequence UNIQUE (organization_id, project_id, run_id, sequence),
    CONSTRAINT ck_run_lifecycle_events_schedule CHECK (
        run_version >= 2 AND sequence = 1 AND event_type = 'RUN_STATE_CHANGED'
        AND previous_state = 'CREATED' AND lifecycle_state = 'QUEUED' AND actor = 'kaas.scheduler')
);

CREATE TABLE outbox_messages (
    outbox_id uuid PRIMARY KEY,
    dispatch_id uuid NOT NULL,
    message_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    message_type varchar(64) NOT NULL,
    schema_version varchar(16) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    publish_attempts integer NOT NULL,
    last_failure_code varchar(64),
    CONSTRAINT fk_outbox_dispatch
        FOREIGN KEY (dispatch_id, message_id, organization_id, project_id, run_id, payload_sha256)
        REFERENCES execution_dispatches (
            dispatch_id, message_id, organization_id, project_id, run_id, payload_sha256),
    CONSTRAINT uq_outbox_dispatch UNIQUE (dispatch_id),
    CONSTRAINT ck_outbox_dispatch_identity CHECK (
        message_type = 'EXECUTION_DISPATCH' AND schema_version = '1.0'
        AND aggregate_type = 'TEST_RUN' AND aggregate_id = run_id),
    CONSTRAINT ck_outbox_initial_delivery CHECK (
        published_at IS NULL AND publish_attempts = 0 AND last_failure_code IS NULL),
    CONSTRAINT ck_outbox_digest CHECK (payload_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_outbox_pending
    ON outbox_messages (occurred_at, message_id) WHERE published_at IS NULL;

DROP TRIGGER test_runs_no_update ON test_runs;
DROP FUNCTION reject_test_run_update();

CREATE OR REPLACE FUNCTION guard_supported_test_run_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state = 'CREATED' AND NEW.lifecycle_state = 'QUEUED'
       AND OLD.cancellation_status = 'NOT_REQUESTED'
       AND NEW.run_version = OLD.run_version + 1
       AND OLD.current_attempt_id IS NULL AND NEW.current_attempt_id IS NOT NULL
       AND OLD.queued_at IS NULL AND OLD.queue_deadline_at IS NULL
       AND NEW.queued_at IS NOT NULL AND NEW.queue_deadline_at > NEW.queued_at
       AND NEW.updated_at = NEW.queued_at AND NEW.updated_at >= OLD.updated_at
       AND NEW.updated_by = 'kaas.scheduler'
       AND (to_jsonb(NEW) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
            - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at')
           = (to_jsonb(OLD) - 'run_version' - 'lifecycle_state' - 'current_attempt_id'
              - 'queued_at' - 'queue_deadline_at' - 'updated_by' - 'updated_at') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'only the exact CREATED to QUEUED scheduling transition is supported'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER test_runs_supported_update
BEFORE UPDATE ON test_runs FOR EACH ROW EXECUTE FUNCTION guard_supported_test_run_update();

CREATE OR REPLACE FUNCTION guard_initial_execution_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_run integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'execution attempts are immutable until claim is implemented' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO matching_run FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
       AND lifecycle_state = 'QUEUED' AND current_attempt_id = NEW.attempt_id
       AND queued_at = NEW.created_at;
    IF matching_run <> 1 THEN
        RAISE EXCEPTION 'initial execution attempt requires its exact QUEUED run' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER execution_attempts_guard
BEFORE INSERT OR UPDATE OR DELETE ON execution_attempts
FOR EACH ROW EXECUTE FUNCTION guard_initial_execution_attempt();

CREATE OR REPLACE FUNCTION guard_execution_dispatch()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_authority integer;
DECLARE existing_digest varchar(64);
DECLARE payload_keys text[];
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'execution dispatch identity and payload are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT payload_sha256 INTO existing_digest FROM execution_dispatches WHERE message_id = NEW.message_id;
    IF FOUND AND existing_digest <> NEW.payload_sha256 THEN
        RAISE EXCEPTION 'execution dispatch message identity has a conflicting payload digest'
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO matching_authority
      FROM test_runs r
      JOIN execution_attempts a ON a.organization_id = r.organization_id
       AND a.project_id = r.project_id AND a.run_id = r.run_id AND a.attempt_id = r.current_attempt_id
     WHERE r.organization_id = NEW.organization_id AND r.project_id = NEW.project_id AND r.run_id = NEW.run_id
       AND r.lifecycle_state = 'QUEUED' AND r.run_version = NEW.run_version
       AND r.snapshot_sha256 = NEW.run_snapshot_sha256
       AND r.queued_at = NEW.occurred_at AND r.queue_deadline_at = NEW.queue_deadline_at
       AND a.attempt_id = NEW.attempt_id AND a.attempt_number = NEW.attempt_number;
    IF matching_authority <> 1 THEN
        RAISE EXCEPTION 'execution dispatch must match the authoritative QUEUED run and initial attempt'
            USING ERRCODE = '23514';
    END IF;

    -- The payload must carry exactly the contract's field names. Counting keys is not enough: a payload can drop a
    -- required key and add an attacker-chosen one while keeping the same cardinality.
    SELECT array_agg(k ORDER BY k COLLATE "C") INTO payload_keys FROM jsonb_object_keys(NEW.payload) k;
    IF payload_keys IS DISTINCT FROM ARRAY[
            'attemptId', 'attemptNumber', 'dispatchId', 'messageId', 'messageType', 'occurredAt',
            'organizationId', 'payloadDigest', 'producer', 'projectId', 'queueDeadlineAt', 'runId',
            'runSnapshotDigest', 'runSnapshotId', 'runVersion', 'schemaVersion']::text[] THEN
        RAISE EXCEPTION 'execution dispatch payload must contain exactly the contract fields'
            USING ERRCODE = '23514';
    END IF;

    -- Fail closed. Every term below must be TRUE: an absent key or a JSON null makes ->> yield SQL NULL, and an
    -- unguarded NULL in an OR chain would leave the row accepted rather than rejected.
    IF coalesce(
           jsonb_typeof(NEW.payload->'runVersion') = 'number'
           AND jsonb_typeof(NEW.payload->'attemptNumber') = 'number'
           AND jsonb_typeof(NEW.payload->'schemaVersion') = 'string'
           AND jsonb_typeof(NEW.payload->'messageId') = 'string'
           AND jsonb_typeof(NEW.payload->'messageType') = 'string'
           AND jsonb_typeof(NEW.payload->'dispatchId') = 'string'
           AND jsonb_typeof(NEW.payload->'occurredAt') = 'string'
           AND jsonb_typeof(NEW.payload->'producer') = 'string'
           AND jsonb_typeof(NEW.payload->'organizationId') = 'string'
           AND jsonb_typeof(NEW.payload->'projectId') = 'string'
           AND jsonb_typeof(NEW.payload->'runId') = 'string'
           AND jsonb_typeof(NEW.payload->'attemptId') = 'string'
           AND jsonb_typeof(NEW.payload->'runSnapshotId') = 'string'
           AND jsonb_typeof(NEW.payload->'runSnapshotDigest') = 'string'
           AND jsonb_typeof(NEW.payload->'queueDeadlineAt') = 'string'
           AND jsonb_typeof(NEW.payload->'payloadDigest') = 'string'
           -- Timestamps must carry an explicit offset, so that validating them cannot depend on the session
           -- TimeZone setting.
           AND NEW.payload->>'occurredAt' ~ '(Z|[+-][0-9]{2}:[0-9]{2})$'
           AND NEW.payload->>'queueDeadlineAt' ~ '(Z|[+-][0-9]{2}:[0-9]{2})$'
           AND NEW.payload->>'schemaVersion' = NEW.schema_version
           AND NEW.payload->>'messageId' = NEW.message_id::text
           AND NEW.payload->>'messageType' = NEW.message_type
           AND NEW.payload->>'dispatchId' = NEW.dispatch_id::text
           AND (NEW.payload->>'occurredAt')::timestamptz = NEW.occurred_at
           AND NEW.payload->>'producer' = NEW.producer
           AND NEW.payload->>'organizationId' = NEW.organization_id::text
           AND NEW.payload->>'projectId' = NEW.project_id::text
           AND NEW.payload->>'runId' = NEW.run_id::text
           AND (NEW.payload->>'runVersion')::bigint = NEW.run_version
           AND NEW.payload->>'attemptId' = NEW.attempt_id::text
           AND (NEW.payload->>'attemptNumber')::integer = NEW.attempt_number
           AND NEW.payload->>'runSnapshotId' = NEW.run_snapshot_id::text
           AND NEW.payload->>'runSnapshotDigest' = 'sha256:' || NEW.run_snapshot_sha256
           AND (NEW.payload->>'queueDeadlineAt')::timestamptz = NEW.queue_deadline_at
           AND NEW.payload->>'payloadDigest' = 'sha256:' || NEW.payload_sha256,
           false) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'execution dispatch payload must exactly match its trusted semantic columns'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_dispatches_guard
BEFORE INSERT OR UPDATE OR DELETE ON execution_dispatches
FOR EACH ROW EXECUTE FUNCTION guard_execution_dispatch();

CREATE OR REPLACE FUNCTION guard_run_lifecycle_event()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_run integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'run lifecycle events are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO matching_run FROM test_runs
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id
       AND lifecycle_state = 'QUEUED' AND run_version = NEW.run_version
       AND current_attempt_id = NEW.attempt_id AND queued_at = NEW.occurred_at;
    IF matching_run <> 1 THEN
        RAISE EXCEPTION 'run lifecycle event must match the authoritative QUEUED transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_lifecycle_events_guard
BEFORE INSERT OR UPDATE OR DELETE ON run_lifecycle_events
FOR EACH ROW EXECUTE FUNCTION guard_run_lifecycle_event();

CREATE OR REPLACE FUNCTION guard_outbox_message()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE existing_digest varchar(64);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'outbox messages are immutable until publication is implemented' USING ERRCODE = '23514';
    END IF;
    SELECT payload_sha256 INTO existing_digest FROM outbox_messages WHERE message_id = NEW.message_id;
    IF FOUND AND existing_digest <> NEW.payload_sha256 THEN
        RAISE EXCEPTION 'outbox message identity has a conflicting payload digest' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_messages_guard
BEFORE INSERT OR UPDATE OR DELETE ON outbox_messages
FOR EACH ROW EXECUTE FUNCTION guard_outbox_message();

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
        -- Delivery state is deliberately NOT part of this invariant. ck_outbox_initial_delivery already pins the
        -- initial values at insert, and a future relay owns published_at/publish_attempts/last_failure_code. Reading
        -- them here would make a run-state invariant fail once a message is legitimately published.
        IF complete_bundles <> 1 OR scheduling_children <> 1 THEN
            RAISE EXCEPTION 'QUEUED run requires exactly one complete attempt dispatch event outbox bundle'
                USING ERRCODE = '23514';
        END IF;
    ELSIF scheduling_children <> 0 THEN
        RAISE EXCEPTION 'scheduling children require a QUEUED run' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER test_run_scheduling_bundle_complete
AFTER UPDATE ON test_runs DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();
CREATE CONSTRAINT TRIGGER execution_attempt_scheduling_bundle_complete
AFTER INSERT ON execution_attempts DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();
CREATE CONSTRAINT TRIGGER execution_dispatch_scheduling_bundle_complete
AFTER INSERT ON execution_dispatches DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();
CREATE CONSTRAINT TRIGGER run_lifecycle_event_scheduling_bundle_complete
AFTER INSERT ON run_lifecycle_events DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();
CREATE CONSTRAINT TRIGGER outbox_scheduling_bundle_complete
AFTER INSERT ON outbox_messages DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_complete_scheduling_bundle();

CREATE OR REPLACE FUNCTION reject_truncate()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'run scheduling evidence cannot be truncated' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER test_runs_no_truncate
BEFORE TRUNCATE ON test_runs FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER run_snapshots_no_truncate
BEFORE TRUNCATE ON run_snapshots FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER execution_attempts_no_truncate
BEFORE TRUNCATE ON execution_attempts FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER execution_dispatches_no_truncate
BEFORE TRUNCATE ON execution_dispatches FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER run_lifecycle_events_no_truncate
BEFORE TRUNCATE ON run_lifecycle_events FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER outbox_messages_no_truncate
BEFORE TRUNCATE ON outbox_messages FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
