CREATE TABLE secret_references (
    secret_reference_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    name varchar(128) COLLATE "C" NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_secret_references_project FOREIGN KEY (organization_id, project_id)
        REFERENCES projects (organization_id, project_id),
    CONSTRAINT uq_secret_references_org_project_id
        UNIQUE (organization_id, project_id, secret_reference_id),
    CONSTRAINT uq_secret_references_org_project_name
        UNIQUE (organization_id, project_id, name),
    CONSTRAINT ck_secret_references_name
        CHECK (name ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$')
);

CREATE INDEX ix_secret_references_org_project_created
    ON secret_references (organization_id, project_id, created_at, secret_reference_id);

CREATE TABLE environments (
    environment_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    name varchar(128) NOT NULL,
    next_revision_number bigint NOT NULL DEFAULT 2,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_environments_project FOREIGN KEY (organization_id, project_id)
        REFERENCES projects (organization_id, project_id),
    CONSTRAINT uq_environments_org_project_id
        UNIQUE (organization_id, project_id, environment_id),
    CONSTRAINT uq_environments_org_project_name
        UNIQUE (organization_id, project_id, name),
    CONSTRAINT ck_environments_name
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT ck_environments_next_revision CHECK (next_revision_number >= 2),
    CONSTRAINT ck_environments_version CHECK (version >= 0)
);

CREATE INDEX ix_environments_org_project_created
    ON environments (organization_id, project_id, created_at, environment_id);

CREATE TABLE environment_revisions (
    revision_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    content_sha256 varchar(64) NOT NULL,
    sealed boolean NOT NULL DEFAULT false,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_environment_revisions_environment
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES environments (organization_id, project_id, environment_id),
    CONSTRAINT uq_environment_revisions_org_project_environment_id
        UNIQUE (organization_id, project_id, environment_id, revision_id),
    CONSTRAINT uq_environment_revisions_number
        UNIQUE (organization_id, project_id, environment_id, revision_number),
    CONSTRAINT ck_environment_revisions_number CHECK (revision_number >= 1),
    CONSTRAINT ck_environment_revisions_digest CHECK (content_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_environment_revisions_history
    ON environment_revisions (organization_id, project_id, environment_id, revision_number DESC);

CREATE TABLE environment_revision_entries (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    environment_revision_id uuid NOT NULL,
    config_key varchar(128) COLLATE "C" NOT NULL,
    value_kind varchar(32) NOT NULL,
    string_value text,
    integer_value bigint,
    boolean_value boolean,
    secret_reference_id uuid,
    PRIMARY KEY (
        organization_id, project_id, environment_id, environment_revision_id, config_key),
    CONSTRAINT fk_environment_entries_revision
        FOREIGN KEY (organization_id, project_id, environment_id, environment_revision_id)
        REFERENCES environment_revisions (organization_id, project_id, environment_id, revision_id),
    CONSTRAINT fk_environment_entries_secret_reference
        FOREIGN KEY (organization_id, project_id, secret_reference_id)
        REFERENCES secret_references (organization_id, project_id, secret_reference_id),
    CONSTRAINT ck_environment_entries_key
        CHECK (config_key ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_environment_entries_kind
        CHECK (value_kind IN ('STRING', 'INTEGER', 'BOOLEAN', 'SECRET_REFERENCE')),
    CONSTRAINT ck_environment_entries_shape CHECK (
        (value_kind = 'STRING'
            AND string_value IS NOT NULL
            AND integer_value IS NULL
            AND boolean_value IS NULL
            AND secret_reference_id IS NULL
            AND octet_length(string_value) <= 4096)
        OR (value_kind = 'INTEGER'
            AND string_value IS NULL
            AND integer_value BETWEEN -9007199254740991 AND 9007199254740991
            AND boolean_value IS NULL
            AND secret_reference_id IS NULL)
        OR (value_kind = 'BOOLEAN'
            AND string_value IS NULL
            AND integer_value IS NULL
            AND boolean_value IS NOT NULL
            AND secret_reference_id IS NULL)
        OR (value_kind = 'SECRET_REFERENCE'
            AND string_value IS NULL
            AND integer_value IS NULL
            AND boolean_value IS NULL
            AND secret_reference_id IS NOT NULL)
    )
);

CREATE TABLE run_profiles (
    run_profile_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    name varchar(128) NOT NULL,
    next_revision_number bigint NOT NULL DEFAULT 2,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_run_profiles_project FOREIGN KEY (organization_id, project_id)
        REFERENCES projects (organization_id, project_id),
    CONSTRAINT uq_run_profiles_org_project_id
        UNIQUE (organization_id, project_id, run_profile_id),
    CONSTRAINT uq_run_profiles_org_project_name
        UNIQUE (organization_id, project_id, name),
    CONSTRAINT ck_run_profiles_name
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT ck_run_profiles_next_revision CHECK (next_revision_number >= 2),
    CONSTRAINT ck_run_profiles_version CHECK (version >= 0)
);

CREATE INDEX ix_run_profiles_org_project_created
    ON run_profiles (organization_id, project_id, created_at, run_profile_id);

CREATE TABLE run_profile_revisions (
    revision_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_profile_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    environment_id uuid NOT NULL,
    environment_revision_id uuid NOT NULL,
    parallelism integer NOT NULL,
    retry_max_attempts integer NOT NULL,
    retry_delay_milliseconds integer NOT NULL,
    execution_timeout_seconds integer NOT NULL,
    max_artifact_bytes bigint NOT NULL,
    max_total_bytes bigint NOT NULL,
    content_sha256 varchar(64) NOT NULL,
    sealed boolean NOT NULL DEFAULT false,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_run_profile_revisions_profile
        FOREIGN KEY (organization_id, project_id, run_profile_id)
        REFERENCES run_profiles (organization_id, project_id, run_profile_id),
    CONSTRAINT fk_run_profile_revisions_environment_revision
        FOREIGN KEY (organization_id, project_id, environment_id, environment_revision_id)
        REFERENCES environment_revisions (organization_id, project_id, environment_id, revision_id),
    CONSTRAINT uq_run_profile_revisions_org_project_profile_id
        UNIQUE (organization_id, project_id, run_profile_id, revision_id),
    CONSTRAINT uq_run_profile_revisions_number
        UNIQUE (organization_id, project_id, run_profile_id, revision_number),
    CONSTRAINT ck_run_profile_revisions_number CHECK (revision_number >= 1),
    CONSTRAINT ck_run_profile_revisions_parallelism CHECK (parallelism BETWEEN 1 AND 32),
    CONSTRAINT ck_run_profile_revisions_retry_attempts CHECK (retry_max_attempts BETWEEN 1 AND 5),
    CONSTRAINT ck_run_profile_revisions_retry_delay
        CHECK (retry_delay_milliseconds BETWEEN 0 AND 30000),
    CONSTRAINT ck_run_profile_revisions_timeout
        CHECK (execution_timeout_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_run_profile_revisions_artifact_bytes
        CHECK (max_artifact_bytes BETWEEN 0 AND 104857600),
    CONSTRAINT ck_run_profile_revisions_total_bytes
        CHECK (max_total_bytes BETWEEN 0 AND 524288000),
    CONSTRAINT ck_run_profile_revisions_artifact_total
        CHECK (max_artifact_bytes <= max_total_bytes),
    CONSTRAINT ck_run_profile_revisions_digest CHECK (content_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_run_profile_revisions_history
    ON run_profile_revisions (organization_id, project_id, run_profile_id, revision_number DESC);

CREATE TABLE run_profile_revision_overrides (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_profile_id uuid NOT NULL,
    run_profile_revision_id uuid NOT NULL,
    config_key varchar(128) COLLATE "C" NOT NULL,
    value_kind varchar(16) NOT NULL,
    string_value text,
    integer_value bigint,
    boolean_value boolean,
    PRIMARY KEY (
        organization_id, project_id, run_profile_id, run_profile_revision_id, config_key),
    CONSTRAINT fk_run_profile_overrides_revision
        FOREIGN KEY (organization_id, project_id, run_profile_id, run_profile_revision_id)
        REFERENCES run_profile_revisions (organization_id, project_id, run_profile_id, revision_id),
    CONSTRAINT ck_run_profile_overrides_key
        CHECK (config_key ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_run_profile_overrides_kind CHECK (value_kind IN ('STRING', 'INTEGER', 'BOOLEAN')),
    CONSTRAINT ck_run_profile_overrides_shape CHECK (
        (value_kind = 'STRING'
            AND string_value IS NOT NULL
            AND integer_value IS NULL
            AND boolean_value IS NULL
            AND octet_length(string_value) <= 4096)
        OR (value_kind = 'INTEGER'
            AND string_value IS NULL
            AND integer_value BETWEEN -9007199254740991 AND 9007199254740991
            AND boolean_value IS NULL)
        OR (value_kind = 'BOOLEAN'
            AND string_value IS NULL
            AND integer_value IS NULL
            AND boolean_value IS NOT NULL)
    )
);

CREATE TABLE run_profile_revision_tags (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_profile_id uuid NOT NULL,
    run_profile_revision_id uuid NOT NULL,
    tag varchar(128) COLLATE "C" NOT NULL,
    PRIMARY KEY (
        organization_id, project_id, run_profile_id, run_profile_revision_id, tag),
    CONSTRAINT fk_run_profile_tags_revision
        FOREIGN KEY (organization_id, project_id, run_profile_id, run_profile_revision_id)
        REFERENCES run_profile_revisions (organization_id, project_id, run_profile_id, revision_id),
    CONSTRAINT ck_run_profile_tags_value CHECK (tag ~ '^@[A-Za-z0-9_.:-]{1,127}$')
);

CREATE TABLE run_profile_revision_artifact_types (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_profile_id uuid NOT NULL,
    run_profile_revision_id uuid NOT NULL,
    artifact_type varchar(32) NOT NULL,
    PRIMARY KEY (
        organization_id, project_id, run_profile_id, run_profile_revision_id, artifact_type),
    CONSTRAINT fk_run_profile_artifact_types_revision
        FOREIGN KEY (organization_id, project_id, run_profile_id, run_profile_revision_id)
        REFERENCES run_profile_revisions (organization_id, project_id, run_profile_id, revision_id),
    CONSTRAINT ck_run_profile_artifact_type CHECK (
        artifact_type IN ('KARATE_HTML_REPORT', 'RAW_RESULT', 'EXECUTION_LOG', 'OTHER'))
);

CREATE OR REPLACE FUNCTION reject_secret_reference_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'secret reference metadata is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER secret_references_immutable
BEFORE UPDATE OR DELETE ON secret_references
FOR EACH ROW EXECUTE FUNCTION reject_secret_reference_mutation();

CREATE OR REPLACE FUNCTION guard_environment_revision_header()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.sealed = false
       AND NEW.sealed = true
       AND (to_jsonb(NEW) - 'sealed') = (to_jsonb(OLD) - 'sealed') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'environment revisions are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER environment_revisions_immutable
BEFORE UPDATE OR DELETE ON environment_revisions
FOR EACH ROW EXECUTE FUNCTION guard_environment_revision_header();

CREATE OR REPLACE FUNCTION require_environment_revision_sealed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_sealed boolean;
BEGIN
    SELECT sealed INTO current_sealed
      FROM environment_revisions
     WHERE organization_id = NEW.organization_id
       AND project_id = NEW.project_id
       AND environment_id = NEW.environment_id
       AND revision_id = NEW.revision_id;
    IF current_sealed IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'environment revision must be sealed before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER environment_revision_sealed_at_commit
AFTER INSERT ON environment_revisions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_environment_revision_sealed();

CREATE OR REPLACE FUNCTION guard_environment_revision_entry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_sealed boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'environment revision entries are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT sealed INTO parent_sealed
      FROM environment_revisions
     WHERE organization_id = NEW.organization_id
       AND project_id = NEW.project_id
       AND environment_id = NEW.environment_id
       AND revision_id = NEW.environment_revision_id;
    IF parent_sealed IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'environment revision entries require an unsealed parent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER environment_revision_entries_immutable
BEFORE INSERT OR UPDATE OR DELETE ON environment_revision_entries
FOR EACH ROW EXECUTE FUNCTION guard_environment_revision_entry();

CREATE OR REPLACE FUNCTION guard_run_profile_revision_header()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.sealed = false
       AND NEW.sealed = true
       AND (to_jsonb(NEW) - 'sealed') = (to_jsonb(OLD) - 'sealed') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'run profile revisions are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER run_profile_revisions_immutable
BEFORE UPDATE OR DELETE ON run_profile_revisions
FOR EACH ROW EXECUTE FUNCTION guard_run_profile_revision_header();

CREATE OR REPLACE FUNCTION require_run_profile_revision_sealed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_sealed boolean;
BEGIN
    SELECT sealed INTO current_sealed
      FROM run_profile_revisions
     WHERE organization_id = NEW.organization_id
       AND project_id = NEW.project_id
       AND run_profile_id = NEW.run_profile_id
       AND revision_id = NEW.revision_id;
    IF current_sealed IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'run profile revision must be sealed before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER run_profile_revision_sealed_at_commit
AFTER INSERT ON run_profile_revisions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_run_profile_revision_sealed();

CREATE OR REPLACE FUNCTION guard_run_profile_revision_child()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_sealed boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'run profile revision children are immutable' USING ERRCODE = '23514';
    END IF;
    SELECT sealed INTO parent_sealed
      FROM run_profile_revisions
     WHERE organization_id = NEW.organization_id
       AND project_id = NEW.project_id
       AND run_profile_id = NEW.run_profile_id
       AND revision_id = NEW.run_profile_revision_id;
    IF parent_sealed IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'run profile revision children require an unsealed parent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_profile_revision_overrides_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_profile_revision_overrides
FOR EACH ROW EXECUTE FUNCTION guard_run_profile_revision_child();

CREATE TRIGGER run_profile_revision_tags_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_profile_revision_tags
FOR EACH ROW EXECUTE FUNCTION guard_run_profile_revision_child();

CREATE TRIGGER run_profile_revision_artifact_types_immutable
BEFORE INSERT OR UPDATE OR DELETE ON run_profile_revision_artifact_types
FOR EACH ROW EXECUTE FUNCTION guard_run_profile_revision_child();

CREATE OR REPLACE FUNCTION reject_incompatible_profile_override()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    environment_kind varchar(32);
BEGIN
    SELECT entry.value_kind INTO environment_kind
      FROM run_profile_revisions profile_revision
      JOIN environment_revision_entries entry
        ON entry.organization_id = profile_revision.organization_id
       AND entry.project_id = profile_revision.project_id
       AND entry.environment_id = profile_revision.environment_id
       AND entry.environment_revision_id = profile_revision.environment_revision_id
       AND entry.config_key = NEW.config_key
     WHERE profile_revision.organization_id = NEW.organization_id
       AND profile_revision.project_id = NEW.project_id
       AND profile_revision.run_profile_id = NEW.run_profile_id
       AND profile_revision.revision_id = NEW.run_profile_revision_id;
    IF environment_kind = 'SECRET_REFERENCE'
       OR (environment_kind IS NOT NULL AND environment_kind <> NEW.value_kind) THEN
        RAISE EXCEPTION 'profile override conflicts with environment configuration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_profile_override_compatible
BEFORE INSERT ON run_profile_revision_overrides
FOR EACH ROW EXECUTE FUNCTION reject_incompatible_profile_override();
