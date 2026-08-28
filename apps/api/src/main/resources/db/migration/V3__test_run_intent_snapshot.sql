ALTER TABLE feature_revisions
    ADD CONSTRAINT uq_feature_revisions_org_project_feature_id
    UNIQUE (organization_id, project_id, feature_id, revision_id);

ALTER TABLE run_profile_revisions
    ADD CONSTRAINT uq_run_profile_revisions_pinned_environment
    UNIQUE (
        organization_id, project_id, run_profile_id, revision_id,
        environment_id, environment_revision_id);

CREATE TABLE test_runs (
    run_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_version bigint NOT NULL,
    lifecycle_state varchar(32) NOT NULL,
    cancellation_status varchar(32) NOT NULL,
    test_outcome varchar(32),
    infrastructure_outcome varchar(32),
    quality_gate_status varchar(32) NOT NULL,
    snapshot_sha256 varchar(64) NOT NULL,
    queued_at timestamptz,
    queue_deadline_at timestamptz,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_test_runs_project FOREIGN KEY (organization_id, project_id)
        REFERENCES projects (organization_id, project_id),
    CONSTRAINT uq_test_runs_org_project_id UNIQUE (organization_id, project_id, run_id),
    CONSTRAINT ck_test_runs_version CHECK (run_version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_test_runs_lifecycle CHECK (lifecycle_state IN (
        'CREATED', 'QUEUED', 'CLAIMED', 'PROVISIONING', 'RUNNING',
        'COLLECTING_RESULTS', 'PROCESSING_RESULTS', 'STOPPING', 'COMPLETED')),
    CONSTRAINT ck_test_runs_cancellation CHECK (
        cancellation_status IN ('NOT_REQUESTED', 'REQUESTED', 'ACKNOWLEDGED')),
    CONSTRAINT ck_test_runs_test_outcome CHECK (
        test_outcome IS NULL OR test_outcome IN ('PASSED', 'FAILED', 'NOT_AVAILABLE')),
    CONSTRAINT ck_test_runs_infrastructure_outcome CHECK (
        infrastructure_outcome IS NULL OR infrastructure_outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    CONSTRAINT ck_test_runs_quality_gate CHECK (
        quality_gate_status IN ('NOT_EVALUATED', 'PASSED', 'FAILED')),
    CONSTRAINT ck_test_runs_snapshot_digest CHECK (snapshot_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_test_runs_outcome_phase CHECK (
        (lifecycle_state <> 'COMPLETED' AND test_outcome IS NULL
            AND infrastructure_outcome IS NULL AND quality_gate_status = 'NOT_EVALUATED')
        OR (lifecycle_state = 'COMPLETED' AND test_outcome IS NOT NULL
            AND infrastructure_outcome IS NOT NULL)),
    CONSTRAINT ck_test_runs_outcome_consistency CHECK (
        infrastructure_outcome IS NULL
        OR (infrastructure_outcome = 'SUCCEEDED' AND test_outcome IN ('PASSED', 'FAILED'))
        OR (infrastructure_outcome <> 'SUCCEEDED' AND test_outcome = 'NOT_AVAILABLE')),
    CONSTRAINT ck_test_runs_cancelled_acknowledged CHECK (
        infrastructure_outcome <> 'CANCELLED' OR cancellation_status = 'ACKNOWLEDGED'),
    CONSTRAINT ck_test_runs_created_queue_absent CHECK (
        lifecycle_state <> 'CREATED' OR (queued_at IS NULL AND queue_deadline_at IS NULL)),
    CONSTRAINT ck_test_runs_queue_times CHECK (
        queue_deadline_at IS NULL OR (queued_at IS NOT NULL AND queue_deadline_at > queued_at))
);

CREATE INDEX ix_test_runs_project_history
    ON test_runs (organization_id, project_id, created_at DESC, run_id DESC);
CREATE INDEX ix_test_runs_created
    ON test_runs (created_at, run_id) WHERE lifecycle_state = 'CREATED';

CREATE TABLE run_snapshots (
    run_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    snapshot_version integer NOT NULL,
    run_profile_id uuid NOT NULL,
    run_profile_revision_id uuid NOT NULL,
    run_profile_revision_number bigint NOT NULL,
    run_profile_sha256 varchar(64) NOT NULL,
    environment_id uuid NOT NULL,
    environment_revision_id uuid NOT NULL,
    environment_revision_number bigint NOT NULL,
    environment_sha256 varchar(64) NOT NULL,
    parallelism integer NOT NULL,
    retry_max_attempts integer NOT NULL,
    retry_delay_milliseconds integer NOT NULL,
    execution_timeout_seconds integer NOT NULL,
    max_artifact_bytes bigint NOT NULL,
    max_total_bytes bigint NOT NULL,
    engine varchar(32) NOT NULL,
    engine_version varchar(64) NOT NULL,
    content_sha256 varchar(64) NOT NULL,
    sealed boolean NOT NULL DEFAULT false,
    CONSTRAINT fk_run_snapshots_run FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES test_runs (organization_id, project_id, run_id),
    CONSTRAINT fk_run_snapshots_profile_and_environment
        FOREIGN KEY (
            organization_id, project_id, run_profile_id, run_profile_revision_id,
            environment_id, environment_revision_id)
        REFERENCES run_profile_revisions (
            organization_id, project_id, run_profile_id, revision_id,
            environment_id, environment_revision_id),
    CONSTRAINT uq_run_snapshots_org_project_id UNIQUE (organization_id, project_id, run_id),
    CONSTRAINT ck_run_snapshots_version CHECK (snapshot_version = 1),
    CONSTRAINT ck_run_snapshots_revision_numbers CHECK (
        run_profile_revision_number >= 1 AND environment_revision_number >= 1),
    CONSTRAINT ck_run_snapshots_digests CHECK (
        run_profile_sha256 ~ '^[a-f0-9]{64}$'
        AND environment_sha256 ~ '^[a-f0-9]{64}$'
        AND content_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_run_snapshots_settings CHECK (
        parallelism BETWEEN 1 AND 32
        AND retry_max_attempts BETWEEN 1 AND 5
        AND retry_delay_milliseconds BETWEEN 0 AND 30000
        AND execution_timeout_seconds BETWEEN 1 AND 3600
        AND max_artifact_bytes BETWEEN 0 AND 104857600
        AND max_total_bytes BETWEEN 0 AND 524288000
        AND max_artifact_bytes <= max_total_bytes),
    CONSTRAINT ck_run_snapshots_engine CHECK (
        engine = 'KARATE'
        AND engine_version ~ '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$')
);

CREATE TABLE run_snapshot_features (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    ordinal integer NOT NULL,
    feature_id uuid NOT NULL,
    feature_revision_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    logical_path varchar(512) COLLATE "C" NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    PRIMARY KEY (organization_id, project_id, run_id, ordinal),
    CONSTRAINT fk_run_snapshot_features_snapshot
        FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES run_snapshots (organization_id, project_id, run_id),
    CONSTRAINT fk_run_snapshot_features_revision
        FOREIGN KEY (organization_id, project_id, feature_id, feature_revision_id)
        REFERENCES feature_revisions (organization_id, project_id, feature_id, revision_id),
    CONSTRAINT uq_run_snapshot_features_revision UNIQUE (
        organization_id, project_id, run_id, feature_revision_id),
    CONSTRAINT uq_run_snapshot_features_identity UNIQUE (
        organization_id, project_id, run_id, feature_id),
    CONSTRAINT uq_run_snapshot_features_path UNIQUE (
        organization_id, project_id, run_id, logical_path),
    CONSTRAINT ck_run_snapshot_features_ordinal CHECK (ordinal BETWEEN 0 AND 999),
    CONSTRAINT ck_run_snapshot_features_revision_number CHECK (revision_number >= 1),
    CONSTRAINT ck_run_snapshot_features_digest CHECK (source_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE run_snapshot_configuration_entries (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    config_key varchar(128) COLLATE "C" NOT NULL,
    value_kind varchar(32) NOT NULL,
    string_value text,
    integer_value bigint,
    boolean_value boolean,
    secret_reference_id uuid,
    PRIMARY KEY (organization_id, project_id, run_id, config_key),
    CONSTRAINT fk_run_snapshot_configuration_snapshot
        FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES run_snapshots (organization_id, project_id, run_id),
    CONSTRAINT fk_run_snapshot_configuration_secret
        FOREIGN KEY (organization_id, project_id, secret_reference_id)
        REFERENCES secret_references (organization_id, project_id, secret_reference_id),
    CONSTRAINT ck_run_snapshot_configuration_key
        CHECK (config_key ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_run_snapshot_configuration_kind
        CHECK (value_kind IN ('STRING', 'INTEGER', 'BOOLEAN', 'SECRET_REFERENCE')),
    CONSTRAINT ck_run_snapshot_configuration_shape CHECK (
        (value_kind = 'STRING' AND string_value IS NOT NULL AND integer_value IS NULL
            AND boolean_value IS NULL AND secret_reference_id IS NULL AND octet_length(string_value) <= 4096)
        OR (value_kind = 'INTEGER' AND string_value IS NULL
            AND integer_value BETWEEN -9007199254740991 AND 9007199254740991
            AND boolean_value IS NULL AND secret_reference_id IS NULL)
        OR (value_kind = 'BOOLEAN' AND string_value IS NULL AND integer_value IS NULL
            AND boolean_value IS NOT NULL AND secret_reference_id IS NULL)
        OR (value_kind = 'SECRET_REFERENCE' AND string_value IS NULL AND integer_value IS NULL
            AND boolean_value IS NULL AND secret_reference_id IS NOT NULL))
);

CREATE TABLE run_snapshot_tags (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    tag varchar(128) COLLATE "C" NOT NULL,
    PRIMARY KEY (organization_id, project_id, run_id, tag),
    CONSTRAINT fk_run_snapshot_tags_snapshot FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES run_snapshots (organization_id, project_id, run_id),
    CONSTRAINT ck_run_snapshot_tags_value CHECK (tag ~ '^@[A-Za-z0-9_.:-]{1,127}$')
);

CREATE TABLE run_snapshot_artifact_types (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    artifact_type varchar(32) NOT NULL,
    PRIMARY KEY (organization_id, project_id, run_id, artifact_type),
    CONSTRAINT fk_run_snapshot_artifact_types_snapshot
        FOREIGN KEY (organization_id, project_id, run_id)
        REFERENCES run_snapshots (organization_id, project_id, run_id),
    CONSTRAINT ck_run_snapshot_artifact_type CHECK (
        artifact_type IN ('KARATE_HTML_REPORT', 'RAW_RESULT', 'EXECUTION_LOG', 'OTHER'))
);

CREATE OR REPLACE FUNCTION guard_initial_test_run()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.run_version <> 1 OR NEW.lifecycle_state <> 'CREATED'
       OR NEW.cancellation_status <> 'NOT_REQUESTED'
       OR NEW.test_outcome IS NOT NULL OR NEW.infrastructure_outcome IS NOT NULL
       OR NEW.quality_gate_status <> 'NOT_EVALUATED'
       OR NEW.queued_at IS NOT NULL OR NEW.queue_deadline_at IS NOT NULL
       OR NEW.created_at <> NEW.updated_at OR NEW.created_by <> NEW.updated_by THEN
        RAISE EXCEPTION 'a test run must be inserted in the exact CREATED state' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER test_runs_initial_state
BEFORE INSERT ON test_runs FOR EACH ROW EXECUTE FUNCTION guard_initial_test_run();

CREATE OR REPLACE FUNCTION reject_test_run_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'test run updates are disabled until lifecycle mutation is implemented' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER test_runs_no_update
BEFORE UPDATE ON test_runs FOR EACH ROW EXECUTE FUNCTION reject_test_run_update();

CREATE OR REPLACE FUNCTION reject_test_run_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'test runs cannot be deleted' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER test_runs_no_delete
BEFORE DELETE ON test_runs FOR EACH ROW EXECUTE FUNCTION reject_test_run_delete();

CREATE OR REPLACE FUNCTION guard_run_snapshot_header()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    profile_revision run_profile_revisions%ROWTYPE;
    environment_revision environment_revisions%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.sealed = false THEN
        SELECT * INTO profile_revision
          FROM run_profile_revisions
         WHERE organization_id = NEW.organization_id
           AND project_id = NEW.project_id
           AND run_profile_id = NEW.run_profile_id
           AND revision_id = NEW.run_profile_revision_id
           AND environment_id = NEW.environment_id
           AND environment_revision_id = NEW.environment_revision_id
           AND sealed = true;
        SELECT * INTO environment_revision
          FROM environment_revisions
         WHERE organization_id = NEW.organization_id
           AND project_id = NEW.project_id
           AND environment_id = NEW.environment_id
           AND revision_id = NEW.environment_revision_id
           AND sealed = true;
        IF NOT FOUND
           OR profile_revision.revision_id IS NULL
           OR NEW.run_profile_revision_number <> profile_revision.revision_number
           OR NEW.run_profile_sha256 <> profile_revision.content_sha256
           OR NEW.environment_revision_number <> environment_revision.revision_number
           OR NEW.environment_sha256 <> environment_revision.content_sha256
           OR NEW.parallelism <> profile_revision.parallelism
           OR NEW.retry_max_attempts <> profile_revision.retry_max_attempts
           OR NEW.retry_delay_milliseconds <> profile_revision.retry_delay_milliseconds
           OR NEW.execution_timeout_seconds <> profile_revision.execution_timeout_seconds
           OR NEW.max_artifact_bytes <> profile_revision.max_artifact_bytes
           OR NEW.max_total_bytes <> profile_revision.max_total_bytes THEN
            RAISE EXCEPTION 'run snapshot provenance must match exact sealed profile and environment revisions'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.sealed = false AND NEW.sealed = true
       AND (to_jsonb(NEW) - 'sealed') = (to_jsonb(OLD) - 'sealed') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'run snapshots are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER run_snapshots_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_snapshots
FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot_header();

CREATE OR REPLACE FUNCTION verify_run_snapshot_feature_provenance()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actual_revision_number bigint;
    actual_logical_path varchar(512);
    actual_source_sha256 varchar(64);
BEGIN
    SELECT r.revision_number, f.logical_path, r.source_sha256
      INTO actual_revision_number, actual_logical_path, actual_source_sha256
      FROM feature_revisions r
      JOIN features f ON f.organization_id = r.organization_id
       AND f.project_id = r.project_id AND f.feature_id = r.feature_id
     WHERE r.organization_id = NEW.organization_id
       AND r.project_id = NEW.project_id
       AND r.feature_id = NEW.feature_id
       AND r.revision_id = NEW.feature_revision_id;
    IF NOT FOUND
       OR NEW.revision_number <> actual_revision_number
       OR NEW.logical_path <> actual_logical_path
       OR NEW.source_sha256 <> actual_source_sha256 THEN
        RAISE EXCEPTION 'run snapshot feature provenance must match the exact immutable feature revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_snapshot_features_provenance
BEFORE INSERT ON run_snapshot_features
FOR EACH ROW EXECUTE FUNCTION verify_run_snapshot_feature_provenance();

CREATE OR REPLACE FUNCTION guard_run_snapshot_child()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_sealed boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'run snapshot children are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT sealed INTO parent_sealed FROM run_snapshots
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id AND run_id = NEW.run_id;
    IF parent_sealed IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'run snapshot children require an unsealed parent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_snapshot_features_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_snapshot_features
FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot_child();
CREATE TRIGGER run_snapshot_configuration_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_snapshot_configuration_entries
FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot_child();
CREATE TRIGGER run_snapshot_tags_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_snapshot_tags
FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot_child();
CREATE TRIGGER run_snapshot_artifact_types_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_snapshot_artifact_types
FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot_child();

CREATE OR REPLACE FUNCTION require_test_run_sealed_snapshot()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE matching_snapshots integer;
BEGIN
    SELECT count(*) INTO matching_snapshots FROM run_snapshots
     WHERE organization_id = NEW.organization_id AND project_id = NEW.project_id
       AND run_id = NEW.run_id AND sealed = true AND content_sha256 = NEW.snapshot_sha256;
    IF matching_snapshots <> 1 THEN
        RAISE EXCEPTION 'test run requires exactly one matching sealed snapshot before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER test_run_snapshot_required_at_commit
AFTER INSERT ON test_runs DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_test_run_sealed_snapshot();
