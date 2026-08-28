DO $$
BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'KaaS requires a UTF8 PostgreSQL database';
    END IF;
END;
$$;

CREATE TABLE organizations (
    organization_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

CREATE TABLE projects (
    project_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (organization_id),
    name varchar(120) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_projects_org_name UNIQUE (organization_id, name),
    CONSTRAINT uq_projects_org_id UNIQUE (organization_id, project_id),
    CONSTRAINT ck_projects_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT ck_projects_version CHECK (version >= 0)
);

CREATE INDEX ix_projects_org_created ON projects (organization_id, created_at, project_id);

CREATE TABLE features (
    feature_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    logical_path varchar(512) NOT NULL,
    next_revision_number bigint NOT NULL DEFAULT 2,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_features_project FOREIGN KEY (organization_id, project_id)
        REFERENCES projects (organization_id, project_id),
    CONSTRAINT uq_features_org_project_id UNIQUE (organization_id, project_id, feature_id),
    CONSTRAINT uq_features_org_project_path UNIQUE (organization_id, project_id, logical_path),
    CONSTRAINT ck_features_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 160),
    CONSTRAINT ck_features_logical_path CHECK (
        char_length(logical_path) BETWEEN 1 AND 512
        AND logical_path ~ '^[A-Za-z0-9._~ -]+(/[A-Za-z0-9._~ -]+)*\.feature$'
        AND logical_path !~ '(^|/)\.{1,2}(/|$)'
        AND position('//' in logical_path) = 0
        AND position(E'\\' in logical_path) = 0
    ),
    CONSTRAINT ck_features_next_revision CHECK (next_revision_number >= 2),
    CONSTRAINT ck_features_version CHECK (version >= 0)
);

CREATE INDEX ix_features_org_project_created
    ON features (organization_id, project_id, created_at, feature_id);

CREATE TABLE feature_revisions (
    revision_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    feature_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    source text NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_feature_revisions_feature FOREIGN KEY (organization_id, project_id, feature_id)
        REFERENCES features (organization_id, project_id, feature_id),
    CONSTRAINT uq_feature_revisions_number
        UNIQUE (organization_id, project_id, feature_id, revision_number),
    CONSTRAINT ck_feature_revisions_number CHECK (revision_number >= 1),
    CONSTRAINT ck_feature_revisions_source_size CHECK (octet_length(source) BETWEEN 1 AND 524288),
    CONSTRAINT ck_feature_revisions_digest CHECK (source_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_feature_revisions_history
    ON feature_revisions (organization_id, project_id, feature_id, revision_number DESC);

CREATE OR REPLACE FUNCTION reject_feature_revision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'feature revisions are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER feature_revisions_immutable
BEFORE UPDATE OR DELETE ON feature_revisions
FOR EACH ROW EXECUTE FUNCTION reject_feature_revision_mutation();

CREATE TABLE api_idempotency_keys (
    organization_id uuid NOT NULL REFERENCES organizations (organization_id),
    principal_id varchar(255) NOT NULL,
    operation varchar(64) NOT NULL,
    scope_path varchar(256) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_sha256 char(64) NOT NULL,
    resource_id uuid NOT NULL,
    http_status integer NOT NULL,
    location varchar(512) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, principal_id, operation, scope_path, idempotency_key),
    CONSTRAINT ck_idempotency_key CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{8,128}$'),
    CONSTRAINT ck_idempotency_digest CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_idempotency_status CHECK (http_status BETWEEN 200 AND 299)
);

CREATE INDEX ix_idempotency_created_at ON api_idempotency_keys (created_at);
