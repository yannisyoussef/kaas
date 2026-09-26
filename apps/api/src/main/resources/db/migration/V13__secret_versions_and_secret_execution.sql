-- KAAS-22: assignment-scoped secret execution with Vault Transit envelope encryption.
--
-- WHAT THIS STORES AND WHAT IT NEVER STORES
--
-- A SecretReference was, until now, a name and nothing else: V2 deliberately gave it no value, no locator and
-- no provider path, because there was no provider. The Operations decision (ADR-034) is that Vault is used as a
-- key service only -- Transit, never KV -- and that the ciphertext Transit returns lives here, beside the
-- metadata it belongs to. So this migration adds exactly three kinds of row:
--
--   * immutable VERSION METADATA: which version, of which reference, encrypted under which Transit key
--     version, by whom and when;
--   * the CIPHERTEXT for that version, in its own table, because it is the one thing about a version that must
--     be destroyable (see crypto-shredding below);
--   * an append-only REVOCATION record per version.
--
-- It stores no plaintext, no hash of plaintext, no length of plaintext and no Transit context. The context is
-- reconstructed from the ownership columns every row already carries, so nothing tenant-supplied can ever choose
-- it. A hash of a secret is deliberately absent: for a low-entropy value it is a guessing oracle, and nothing in
-- the platform needs to compare plaintexts.
--
-- EXPAND-ONLY, FOR ROLLBACK
--
-- Operations deploys forward-only Flyway and requires the previous application release to keep working
-- against the new schema. Every statement below is either a new table, a nullable column, or a constraint over
-- rows the previous release can never have written:
--
--   * the previous release never inserts into the new tables and never reads them;
--   * the new snapshot column is nullable, and the previous release inserts snapshot rows without it -- such a
--     snapshot pins no version, and this release refuses to execute it (RUN_SNAPSHOT_INVALID) rather than
--     guessing one;
--   * the capability scope table is empty in every deployment, because the previous release refused every
--     secret-bearing run before issuing a capability. MigrationUpgradeTests pins that emptiness, which is what
--     makes the NOT NULL and the primary-key change below safe. If a row did exist, this migration would fail
--     loudly rather than invent a version for it.

-- ---------------------------------------------------------------------------------------------------------
-- Version metadata
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE secret_versions (
    secret_version_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    secret_reference_id uuid NOT NULL,
    -- Monotonic per reference, starting at one, with no gaps. A rotation is a new row; nothing is ever renumbered.
    version_number integer NOT NULL,
    -- Which Transit key, and which version of it, produced the ciphertext. Safe metadata: it names a key the
    -- platform owns, never a tenant value, and it is what lets an operator see which versions a future
    -- min_decryption_version change would strand.
    transit_key_name varchar(128) COLLATE "C" NOT NULL,
    transit_key_version integer NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_secret_versions_reference
        FOREIGN KEY (organization_id, project_id, secret_reference_id)
        REFERENCES secret_references (organization_id, project_id, secret_reference_id),
    CONSTRAINT uq_secret_versions_number UNIQUE (secret_reference_id, version_number),
    -- The composite target snapshots and capability scopes point at. Ownership is in the key, so a row in
    -- another project cannot be named even by a correct reference id with the wrong version.
    CONSTRAINT uq_secret_versions_scope
        UNIQUE (organization_id, project_id, secret_reference_id, version_number),
    CONSTRAINT uq_secret_versions_owner UNIQUE (secret_version_id, organization_id, project_id),
    CONSTRAINT ck_secret_versions_number CHECK (version_number BETWEEN 1 AND 100000),
    CONSTRAINT ck_secret_versions_key_name CHECK (transit_key_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_secret_versions_key_version CHECK (transit_key_version BETWEEN 1 AND 1000000)
);

CREATE INDEX ix_secret_versions_reference ON secret_versions (secret_reference_id, version_number);

-- A new version must be the next one. Checked in the database because the application's own read-then-insert
-- runs under a row lock, and a lock discipline that a second writer skips is not a property of the data.
CREATE OR REPLACE FUNCTION guard_secret_version_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    latest integer;
BEGIN
    SELECT max(version_number) INTO latest FROM secret_versions
     WHERE secret_reference_id = NEW.secret_reference_id;
    IF NEW.version_number <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION 'secret versions are numbered consecutively from one' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER secret_versions_consecutive
BEFORE INSERT ON secret_versions
FOR EACH ROW EXECUTE FUNCTION guard_secret_version_insert();

CREATE OR REPLACE FUNCTION reject_secret_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'secret version metadata is immutable' USING ERRCODE = '23514';
END;
$$;

-- IMMUTABLE, including through revocation. Revocation is a separate append-only record rather than a column
-- here, so "this version existed, was created by X at T, under key version K" stays true forever -- which is
-- the audit evidence an incident review needs after the ciphertext itself has been destroyed.
CREATE TRIGGER secret_versions_immutable
BEFORE UPDATE OR DELETE ON secret_versions
FOR EACH ROW EXECUTE FUNCTION reject_secret_version_mutation();

CREATE TRIGGER secret_versions_untruncatable
BEFORE TRUNCATE ON secret_versions
FOR EACH STATEMENT EXECUTE FUNCTION reject_secret_version_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- Revocation
-- ---------------------------------------------------------------------------------------------------------

-- One row per revoked version, never updated and never deleted. Presence is the revocation; there is no
-- "un-revoke", because a version an operator believed compromised must not quietly come back.
CREATE TABLE secret_version_revocations (
    secret_version_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revoked_by varchar(255) NOT NULL,
    revoked_at timestamptz NOT NULL,
    CONSTRAINT fk_secret_version_revocations_version
        FOREIGN KEY (secret_version_id, organization_id, project_id)
        REFERENCES secret_versions (secret_version_id, organization_id, project_id)
);

CREATE OR REPLACE FUNCTION reject_secret_revocation_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'secret revocations are permanent' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER secret_version_revocations_permanent
BEFORE UPDATE OR DELETE ON secret_version_revocations
FOR EACH ROW EXECUTE FUNCTION reject_secret_revocation_mutation();

CREATE TRIGGER secret_version_revocations_untruncatable
BEFORE TRUNCATE ON secret_version_revocations
FOR EACH STATEMENT EXECUTE FUNCTION reject_secret_revocation_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- Ciphertext
-- ---------------------------------------------------------------------------------------------------------

-- The encrypted payload, and the only secret-version state that may ever be removed.
--
-- CRYPTO-SHREDDING. Transit keys are shared across tenants through derived context and are never deleted
-- (ADR-034), so destroying a version cannot mean destroying its key. It means destroying the only copy of the
-- ciphertext: once this row is gone there is nothing any key could decrypt. The deletion is allowed only for a
-- version that has a revocation record, and it happens in the revocation's own transaction, so a revoked
-- version is unusable twice over -- by the revocation check every resolution performs, and by the absence of
-- anything to decrypt.
--
-- Ciphertext is sensitive platform material even though it is encrypted: it is decryptable by anyone who can
-- also reach Vault with this deployment's AppRole. Nothing outside the resolution path reads this table, and no
-- API returns it.
CREATE TABLE secret_version_ciphertexts (
    secret_version_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    ciphertext text NOT NULL,
    CONSTRAINT fk_secret_version_ciphertexts_version
        FOREIGN KEY (secret_version_id, organization_id, project_id)
        REFERENCES secret_versions (secret_version_id, organization_id, project_id),
    -- Exactly the shape Transit returns and nothing else, so a plaintext written here by a defect fails the
    -- insert instead of being stored. Bounded: the largest permitted value encrypts to well under this.
    CONSTRAINT ck_secret_version_ciphertexts_shape
        CHECK (ciphertext ~ '^vault:v[1-9][0-9]{0,6}:[A-Za-z0-9+/]+={0,2}$'
               AND octet_length(ciphertext) <= 16384)
);

CREATE OR REPLACE FUNCTION guard_secret_ciphertext_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM secret_version_revocations WHERE secret_version_id = OLD.secret_version_id) THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'only a revoked secret version may be destroyed' USING ERRCODE = '23514';
    END IF;
    -- No rewrap in this slice: the ciphertext a version was created with is the one it keeps. A future rewrap
    -- would replace this guard deliberately, with its own review.
    RAISE EXCEPTION 'secret ciphertext is never rewritten' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER secret_version_ciphertexts_guarded
BEFORE UPDATE OR DELETE ON secret_version_ciphertexts
FOR EACH ROW EXECUTE FUNCTION guard_secret_ciphertext_mutation();

CREATE TRIGGER secret_version_ciphertexts_untruncatable
BEFORE TRUNCATE ON secret_version_ciphertexts
FOR EACH STATEMENT EXECUTE FUNCTION reject_secret_version_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- Snapshot pinning
-- ---------------------------------------------------------------------------------------------------------

-- A run pins (reference, version), never "latest". The version is resolved when the snapshot is created -- from
-- metadata only, nothing is decrypted -- and it is what the run executes with for as long as it exists. A
-- rotation after the run was queued changes nothing about it; a revocation makes it fail rather than advance.
ALTER TABLE run_snapshot_configuration_entries ADD COLUMN secret_version_number integer;

ALTER TABLE run_snapshot_configuration_entries
    ADD CONSTRAINT ck_run_snapshot_configuration_secret_version
        CHECK (secret_version_number IS NULL
               OR (value_kind = 'SECRET_REFERENCE' AND secret_version_number >= 1));

-- MATCH SIMPLE, the default: a row with no version (every row the previous release wrote) is not checked, and
-- a row with one must name a real version of the same reference in the same project.
ALTER TABLE run_snapshot_configuration_entries
    ADD CONSTRAINT fk_run_snapshot_configuration_secret_version
        FOREIGN KEY (organization_id, project_id, secret_reference_id, secret_version_number)
        REFERENCES secret_versions (organization_id, project_id, secret_reference_id, version_number);

-- ---------------------------------------------------------------------------------------------------------
-- Secret capability scope
-- ---------------------------------------------------------------------------------------------------------

-- The scope names the exact version, not just the reference. A capability for version 3 cannot be redeemed
-- for version 4.
ALTER TABLE execution_capability_secret_references ADD COLUMN secret_version_number integer;
ALTER TABLE execution_capability_secret_references ALTER COLUMN secret_version_number SET NOT NULL;
ALTER TABLE execution_capability_secret_references
    ADD CONSTRAINT fk_execution_capability_secret_version
        FOREIGN KEY (organization_id, project_id, secret_reference_id, secret_version_number)
        REFERENCES secret_versions (organization_id, project_id, secret_reference_id, version_number);

-- Keyed by binding rather than by reference. Two keys may legitimately bind the same reference, and the old
-- key made that a primary-key violation -- a 500 at issuance for a configuration the API had accepted.
ALTER TABLE execution_capability_secret_references
    DROP CONSTRAINT execution_capability_secret_references_pkey;
ALTER TABLE execution_capability_secret_references
    ADD CONSTRAINT execution_capability_secret_references_pkey PRIMARY KEY (capability_id, binding_key);

-- PLAINTEXT IS MORE SENSITIVE THAN SOURCE, so a secret capability does not inherit the source ceiling of 64.
-- Two: the delivery, and one retry for a response lost in transit. Stated as a CHECK so a writer that forgot
-- the application's own predicate still cannot pass it.
ALTER TABLE execution_capabilities
    ADD CONSTRAINT ck_execution_capabilities_secret_redemptions
        CHECK (capability_type <> 'SECRET' OR redemption_count <= 2);
