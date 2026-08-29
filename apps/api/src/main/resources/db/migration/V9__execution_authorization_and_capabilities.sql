-- V9 separates owning an attempt from being allowed to execute it.
--
-- Claiming, in V8, established exactly one fact: this worker instance owns this infrastructure attempt. That is
-- necessary for execution and it is not sufficient. Execution needs a second, independent decision — taken
-- against authoritative state at a single instant, bound to one assignment, and expiring — which is what an
-- ExecutionAuthorization is. Everything else here hangs off that decision: the capabilities that let a worker
-- fetch what it needs, and the immutable command describing what it would run.
--
-- Nothing here executes anything. There is no transition to PROVISIONING, no publication to a broker, and no
-- path from a command to the sandbox. A command may exist and have nowhere to go; that is the deliberate end
-- state of this slice.
--
-- Two properties are worth stating because the rest of the file only makes sense in their light.
--
-- AUTHORITY IS ASSIGNMENT-SCOPED. Every row below carries an attempt and an assignment epoch, and the unique
-- constraints are on the pair. Authority from attempt 1 / epoch 1 must never authorize attempt 1 / epoch 2, and
-- keying anything on attemptId alone would make that impossible to enforce later.
--
-- EXPIRY IS NOT REVOCATION. These tables record when authority was issued and when it lapses, and the database
-- can enforce the ordering of those instants. It cannot enforce that a lease is still live, because that is a
-- fact about the future at the time of insertion. Redemption therefore revalidates authoritative state every
-- time; the columns here are an audit record and a first bound, never the security decision.

-- OPERATIONAL NOTE. This migration transforms no existing row: five new tables, one seeded policy revision, and
-- triggers only on tables created in this same file. No constraint is validated against pre-existing data.
--
-- It is NOT online, and an earlier version of this comment said it was. `CREATE TABLE ... REFERENCES` takes a
-- table-level ShareRowExclusiveLock on each referenced table, which conflicts with the RowExclusiveLock every
-- writer needs. For the duration of this transaction, inserts and updates to `organizations`,
-- `execution_attempts`, and `secret_references` block — which stalls the scheduler creating attempts, the claim
-- and heartbeat path, and the lease reconciler. Measured: an ordinary INSERT from a second session with
-- lock_timeout=3s is cancelled.
--
-- It is brief, and the lock_timeout below bounds how long THIS transaction waits to acquire — not how long
-- others queue behind it once it holds. Treat it as a short maintenance window rather than a rolling upgrade.
-- A genuinely online variant would create the tables without foreign keys and add them NOT VALID, then
-- VALIDATE separately.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------
-- Network policy
-- ---------------------------------------------------------------------------------------------------------

-- The egress policy an execution would run under, as an immutable platform-owned revision.
--
-- Platform-owned rather than tenant-owned, deliberately. A tenant choosing its own egress policy is a real
-- product requirement and it needs a policy model, an approval path, and enforcement that has been tested; none
-- of those exists. Until then the only revision that exists is the one the platform created, and a worker
-- cannot select a different one because it never gets to name one at all.
--
-- DENY_ALL is the only type that is ENFORCEABLE today, and the distinction between "defined" and "enforceable"
-- is the point of this table. The sandbox from V8's companion slice proves no network at all; nothing proves an
-- allowlist. A row of type ALLOWLIST may therefore be created here in a later slice and will still be refused at
-- authorization time until a launcher can demonstrate it, rather than silently degrading to something weaker.
CREATE TABLE network_policy_revisions (
    policy_revision_id uuid PRIMARY KEY,
    policy_type varchar(32) NOT NULL,
    policy_version integer NOT NULL,
    canonical_digest varchar(71) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_network_policy_type CHECK (policy_type IN ('DENY_ALL', 'ALLOWLIST')),
    CONSTRAINT ck_network_policy_version CHECK (policy_version BETWEEN 1 AND 1000000),
    CONSTRAINT ck_network_policy_digest CHECK (canonical_digest ~ '^sha256:[a-f0-9]{64}$'),
    -- The platform is the only author, so the actor is in the reserved namespace the API refuses to issue to
    -- tenants. A tenant-authored network policy would be indistinguishable from a platform one without this.
    CONSTRAINT ck_network_policy_actor CHECK (created_by LIKE 'kaas.%'),
    CONSTRAINT uq_network_policy_type_version UNIQUE (policy_type, policy_version)
);

CREATE OR REPLACE FUNCTION reject_network_policy_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'network policy revisions are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER network_policy_revisions_immutable
BEFORE UPDATE OR DELETE ON network_policy_revisions
FOR EACH ROW EXECUTE FUNCTION reject_network_policy_mutation();

-- The one policy that exists, seeded here rather than created by application code at startup.
--
-- A fixed identity and a fixed digest, both computed from the canonical form documented in
-- NetworkPolicyPolicy: a policy whose identity varied per deployment could not be compared across an audit
-- trail, and a policy the application created lazily would be a policy the application could create differently.
INSERT INTO network_policy_revisions (
    policy_revision_id, policy_type, policy_version, canonical_digest, created_by, created_at)
VALUES (
    '00000000-0000-4000-8000-00000000d001',
    'DENY_ALL',
    1,
    'sha256:90bc5fe597d868eb21bc933950f31f10f4ea1f528e9e96a8eabdc7bd73a02450',
    'kaas.platform',
    '2026-01-01T00:00:00Z');

-- ---------------------------------------------------------------------------------------------------------
-- Execution authorization
-- ---------------------------------------------------------------------------------------------------------

-- The decision that a specific assignment may execute, taken once and recorded.
--
-- Every column that is not an identifier is evidence of something that was true at issuance: which snapshot was
-- sealed, which sandbox security profile the platform expected, which assessment was presented for it, which
-- egress policy applied. Binding them here is what lets an audit answer "on what basis was this allowed", and
-- what lets a later check notice that the basis has changed.
CREATE TABLE execution_authorizations (
    authorization_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (organization_id),
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    -- The run version the decision was taken against. A later version means the run moved, and every capability
    -- issued under this authorization revalidates against the current one rather than this recorded value.
    run_version bigint NOT NULL,
    attempt_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    assignment_epoch integer NOT NULL,
    -- The worker the assignment belonged to. Unlike the attempt table, where identity is audit only, here it is
    -- part of the authority: a capability issued to one worker must not be redeemable by another, and that is
    -- checked against this value and against the live assignment together.
    worker_id varchar(255) NOT NULL,
    -- Bare lowercase hex, matching test_runs.snapshot_sha256, so the two can be compared without normalising.
    -- A prefix mismatch between two representations of the same digest is how an earlier slice made every
    -- redelivery look like a conflict, and the fix was to stop having two spellings.
    run_snapshot_sha256 varchar(64) NOT NULL,
    security_profile_version varchar(64) NOT NULL,
    security_assessment_digest varchar(71) NOT NULL,
    probe_image_digest varchar(71) NOT NULL,
    network_policy_revision_id uuid NOT NULL REFERENCES network_policy_revisions (policy_revision_id),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    -- Set when the authorization is explicitly withdrawn. Distinct from expiry: an expired authorization simply
    -- ran out, a revoked one was taken away, and the two are different things to an operator reading an audit.
    revoked_at timestamptz,
    revoked_reason varchar(64),
    CONSTRAINT ck_execution_authorizations_attempt_number CHECK (attempt_number = 1),
    -- A bounded vocabulary, like every other reason column in this schema. Free text here would mean an audit
    -- trail whose most important field says whatever the last writer felt like.
    CONSTRAINT ck_execution_authorizations_revoked_reason
        CHECK (revoked_reason IS NULL
               OR revoked_reason IN ('ASSIGNMENT_FENCED', 'RUN_TERMINATED', 'SUPERSEDED', 'OPERATOR')),
    CONSTRAINT ck_execution_authorizations_epoch CHECK (assignment_epoch BETWEEN 1 AND 1000),
    CONSTRAINT ck_execution_authorizations_worker CHECK (worker_id LIKE 'kaas.%'),
    CONSTRAINT ck_execution_authorizations_snapshot CHECK (run_snapshot_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_authorizations_assessment CHECK (security_assessment_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_authorizations_probe_image CHECK (probe_image_digest ~ '^sha256:[a-f0-9]{64}$'),
    -- A window, not a point. An authorization that expires before it is issued is not a short-lived credential,
    -- it is a broken one, and the ceiling stops a server bug turning "short-lived" into "for the rest of the day".
    CONSTRAINT ck_execution_authorizations_window
        CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '30 minutes'),
    CONSTRAINT ck_execution_authorizations_revocation
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)
               AND (revoked_at IS NULL OR revoked_at >= issued_at)),
    -- Composite, matching the convention V4:61-63 established and the unique key
    -- uq_execution_attempts_identity exists to support. A single-column FK constrains existence and nothing
    -- else: it accepted an authorization for another tenant's already-fenced attempt, at an epoch that attempt
    -- never held, pointing at a run that does not exist. Ownership has to be structural or it is not enforced.
    CONSTRAINT fk_execution_authorizations_attempt
        FOREIGN KEY (organization_id, project_id, run_id, attempt_id)
        REFERENCES execution_attempts (organization_id, project_id, run_id, attempt_id),
    -- One authorization per assignment. This is the IDEMPOTENCY key and nothing more: a worker retrying its
    -- request finds the authorization it already has rather than minting a second one with fresh capabilities.
    --
    -- It is deliberately NOT described as a fencing constraint, because it is not one — it makes epoch 1 and
    -- epoch 2 two distinct rows, both of which can be unrevoked at once. An earlier version of this comment
    -- claimed the opposite. Fencing is what uq_execution_authorizations_live below enforces.
    CONSTRAINT uq_execution_authorizations_assignment UNIQUE (attempt_id, assignment_epoch),
    -- The keys children reference. Composite FKs need a matching unique key on the parent, and carrying the
    -- tenant through every level is what stops a child row disagreeing with its parent about who owns it.
    CONSTRAINT uq_execution_authorizations_scope UNIQUE (authorization_id, organization_id, project_id),
    CONSTRAINT uq_execution_authorizations_assignment_scope
        UNIQUE (authorization_id, organization_id, run_id, attempt_id, assignment_epoch)
);

-- At most one LIVE authorization per attempt, whatever the epoch.
--
-- This is the fencing constraint the uniqueness above is not. Without it, epoch 1 and epoch 2 can both sit
-- unrevoked on one attempt, which is precisely the "authority alongside the current holder" the design forbids.
CREATE UNIQUE INDEX uq_execution_authorizations_live
    ON execution_authorizations (attempt_id)
    WHERE revoked_at IS NULL;

-- An authorization's semantic content is fixed at issuance. Only revocation may be written afterwards, and only
-- once: an authorization that could be un-revoked would make revocation advisory.
CREATE OR REPLACE FUNCTION guard_execution_authorization_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.authorization_id = OLD.authorization_id
       AND NEW.organization_id = OLD.organization_id
       AND NEW.project_id = OLD.project_id
       AND NEW.run_id = OLD.run_id
       AND NEW.run_version = OLD.run_version
       AND NEW.attempt_id = OLD.attempt_id
       AND NEW.attempt_number = OLD.attempt_number
       AND NEW.assignment_epoch = OLD.assignment_epoch
       AND NEW.worker_id = OLD.worker_id
       AND NEW.run_snapshot_sha256 = OLD.run_snapshot_sha256
       AND NEW.security_profile_version = OLD.security_profile_version
       AND NEW.security_assessment_digest = OLD.security_assessment_digest
       AND NEW.probe_image_digest = OLD.probe_image_digest
       AND NEW.network_policy_revision_id = OLD.network_policy_revision_id
       AND NEW.issued_at = OLD.issued_at
       AND OLD.revoked_at IS NULL
       -- Two updates are permitted on a live authorization, and nothing else.
       --
       -- REVOCATION, which is terminal.
       AND ((NEW.revoked_at IS NOT NULL AND NEW.expires_at = OLD.expires_at)
       -- RE-ANCHORING, which moves the expiry FORWARD and never backward.
       --
       -- Freezing the expiry at issuance was a liveness dead end. An authorization is bounded by the lease that
       -- justifies it, and a lease is thirty seconds that a healthy worker renews indefinitely by heartbeat. So
       -- the authorization's window closed one lease-period after it was issued, the unique constraint made a
       -- replacement impossible, and a perfectly healthy heartbeating worker became permanently unauthorizable
       -- with the run's only exit being FAILED. Re-anchoring is what lets the authorization follow the lease it
       -- is derived from. Moving it forward cannot extend authority beyond the lease, because the service
       -- recomputes it as the earlier of the TTL and the CURRENT lease expiry, and every redemption revalidates
       -- the live assignment regardless.
            OR (NEW.revoked_at IS NULL AND NEW.expires_at >= OLD.expires_at
                AND NEW.expires_at <= NEW.issued_at + interval '30 minutes')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an execution authorization may only be revoked or re-anchored' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_authorizations_guarded
BEFORE UPDATE ON execution_authorizations
FOR EACH ROW EXECUTE FUNCTION guard_execution_authorization_update();

CREATE OR REPLACE FUNCTION reject_execution_authorization_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'execution authorizations are audit evidence and are never deleted' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_authorizations_undeletable
BEFORE DELETE ON execution_authorizations
FOR EACH ROW EXECUTE FUNCTION reject_execution_authorization_delete();

-- ---------------------------------------------------------------------------------------------------------
-- Capabilities
-- ---------------------------------------------------------------------------------------------------------

-- Short-lived bearer authority to fetch one thing, for one assignment.
--
-- Source and secret capabilities share a table rather than having one each. That is not merely fewer tables: a
-- single token_hash unique index across both types is what makes it impossible for a source token to also be a
-- valid secret token, which two tables would leave as a property of the application's routing rather than of the
-- data. The type is carried explicitly and checked at redemption, and the token's own printable prefix says
-- which it is so a misrouted token fails loudly rather than being looked up in the wrong place.
--
-- THE PLAINTEXT TOKEN IS NEVER STORED. Only a SHA-256 of it. The token carries its own entropy — it is random
-- server-generated material, not a human password — so a fast hash is the right choice and a slow one would buy
-- nothing. What matters is that a database backup, a replica, or a log of this table grants nobody anything.
CREATE TABLE execution_capabilities (
    capability_id uuid PRIMARY KEY,
    authorization_id uuid NOT NULL,
    -- Carried rather than joined for. A capability's tenant has to be expressible in a foreign key, or the
    -- scope table below cannot be constrained against the project that owns the secret it names.
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    capability_type varchar(16) NOT NULL,
    -- Bare lowercase hex of SHA-256 over the plaintext token. Unique across every capability of every type.
    token_sha256 varchar(64) NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    redemption_count integer NOT NULL DEFAULT 0,
    last_redeemed_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT ck_execution_capabilities_type CHECK (capability_type IN ('SOURCE', 'SECRET')),
    CONSTRAINT ck_execution_capabilities_hash CHECK (token_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_capabilities_window
        CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '30 minutes'),
    -- A capability that has never been redeemed has no redemption instant, and one that has must have both. The
    -- ceiling is a bound on amplification: a capability is a retry allowance, not an unlimited download licence.
    CONSTRAINT ck_execution_capabilities_redemption
        CHECK (redemption_count BETWEEN 0 AND 64
               AND (redemption_count = 0) = (last_redeemed_at IS NULL)
               AND (last_redeemed_at IS NULL OR last_redeemed_at >= issued_at)),
    CONSTRAINT ck_execution_capabilities_revocation
        CHECK (revoked_at IS NULL OR revoked_at >= issued_at),
    CONSTRAINT uq_execution_capabilities_token UNIQUE (token_sha256),
    CONSTRAINT fk_execution_capabilities_authorization
        FOREIGN KEY (authorization_id, organization_id, project_id)
        REFERENCES execution_authorizations (authorization_id, organization_id, project_id),
    CONSTRAINT uq_execution_capabilities_scope UNIQUE (capability_id, organization_id, project_id)
);

CREATE INDEX ix_execution_capabilities_authorization
    ON execution_capabilities (authorization_id, capability_type);

-- At most one LIVE capability of each type per authorization.
--
-- The application already guarantees this through the run row lock every writer takes, and a test proves it —
-- but the test passes because of the lock, so no schema change could turn it red. Rotation is a revoke followed
-- by an insert with nothing underneath it; a second writer to this table, now or later, would break the
-- invariant silently. This is the backstop that does not depend on lock discipline staying correct.
CREATE UNIQUE INDEX uq_execution_capabilities_live
    ON execution_capabilities (authorization_id, capability_type)
    WHERE revoked_at IS NULL;

-- Only redemption accounting and revocation may change. The identity, the type, the hash, and the window are
-- what the capability IS; a capability whose expiry could be extended after issuance is not short-lived.
CREATE OR REPLACE FUNCTION guard_execution_capability_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.capability_id = OLD.capability_id
       AND NEW.authorization_id = OLD.authorization_id
       AND NEW.capability_type = OLD.capability_type
       AND NEW.token_sha256 = OLD.token_sha256
       AND NEW.issued_at = OLD.issued_at
       AND NEW.expires_at = OLD.expires_at
       AND NEW.redemption_count >= OLD.redemption_count
       -- The redemption instant may not move backwards either. Guarding the count alone let the timestamp be
       -- rewound to issuance on a row that had been redeemed five times, which is an audit trail that lies.
       AND (OLD.last_redeemed_at IS NULL OR NEW.last_redeemed_at >= OLD.last_redeemed_at)
       AND (OLD.revoked_at IS NULL OR NEW.revoked_at = OLD.revoked_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a capability may only record redemption or revocation' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_capabilities_guarded
BEFORE UPDATE ON execution_capabilities
FOR EACH ROW EXECUTE FUNCTION guard_execution_capability_update();

-- Capabilities are audit evidence too, and were deletable while authorizations and commands were not. A
-- capability row is the only record that a token was ever issued and how many times it was redeemed; deleting
-- one erases exactly the evidence an incident review would want.
CREATE OR REPLACE FUNCTION reject_execution_capability_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'execution capabilities are audit evidence and are never deleted' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_capabilities_undeletable
BEFORE DELETE ON execution_capabilities
FOR EACH ROW EXECUTE FUNCTION reject_execution_capability_delete();

-- Which SecretReferences a secret capability may resolve, enumerated at issuance.
--
-- A secret capability names its references rather than carrying a scope expression, because a wildcard is how a
-- capability for one run reads another's secrets. The reference must belong to the same project as the
-- authorization, which the foreign key below makes structural rather than a check the application performs.
CREATE TABLE execution_capability_secret_references (
    capability_id uuid NOT NULL,
    -- The tenant, carried so both foreign keys below can be composite against it. Single-column keys made
    -- cross-tenant scope REPRESENTABLE: a capability under one organization was accepted scoped to another
    -- organization's SecretReference, and an earlier version of this comment claimed the foreign key prevented
    -- exactly that. A single-column FK constrains existence, never ownership.
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    secret_reference_id uuid NOT NULL,
    binding_key varchar(128) NOT NULL,
    PRIMARY KEY (capability_id, secret_reference_id),
    CONSTRAINT fk_execution_capability_secret_capability
        FOREIGN KEY (capability_id, organization_id, project_id)
        REFERENCES execution_capabilities (capability_id, organization_id, project_id),
    -- The same composite target V2:86 and V3:162 use, supported by uq_secret_references_org_project_id.
    CONSTRAINT fk_execution_capability_secret_reference
        FOREIGN KEY (organization_id, project_id, secret_reference_id)
        REFERENCES secret_references (organization_id, project_id, secret_reference_id),
    CONSTRAINT uq_execution_capability_secret_key UNIQUE (capability_id, binding_key)
);

CREATE OR REPLACE FUNCTION reject_capability_secret_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a capability scope is fixed at issuance' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_capability_secret_references_immutable
BEFORE UPDATE OR DELETE ON execution_capability_secret_references
FOR EACH ROW EXECUTE FUNCTION reject_capability_secret_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- Execution command
-- ---------------------------------------------------------------------------------------------------------

-- The immutable description of what would be executed, if anything executed it.
--
-- Nothing in this slice does. The command is produced, digested, and stored, and there its life ends: it is not
-- published to a broker, not handed to the sandbox launcher, and not reachable from the dispatch consumer. That
-- isolation is the point — the authority composition can be proven correct before anything acts on it.
--
-- The stored document contains no bearer token. Capability identifiers appear in it, because an identifier is
-- not authority; the plaintext tokens are assembled into a delivery representation at response time and are
-- never written anywhere durable.
CREATE TABLE execution_commands (
    command_id uuid PRIMARY KEY,
    authorization_id uuid NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations (organization_id),
    run_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    assignment_epoch integer NOT NULL,
    command_digest varchar(71) NOT NULL,
    document jsonb NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_execution_commands_digest CHECK (command_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_execution_commands_epoch CHECK (assignment_epoch BETWEEN 1 AND 1000),
    -- A window with a ceiling, matching the authorization's. Without the ceiling a command could carry a
    -- hundred-year expiry, and expiry is the field a consumer would fence on.
    CONSTRAINT ck_execution_commands_window
        CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '30 minutes'),
    -- octet_length over the text form, not pg_column_size. The two agree at INSERT and diverge afterwards:
    -- pg_column_size returns the COMPRESSED size of a stored datum, so a 250KB document measures ~2.9KB once
    -- written. Any later VALIDATE, or any reasoning about a stored row, would be measuring something ninety
    -- times smaller than what the insert measured. V4:79 already established this convention for the schema.
    CONSTRAINT ck_execution_commands_size CHECK (octet_length(document::text) <= 262144),
    -- A command describes an execution, so it has to be an object rather than a bare scalar. V4:78 does the
    -- same for the dispatch payload.
    CONSTRAINT ck_execution_commands_document_shape CHECK (jsonb_typeof(document) = 'object'),
    -- One command per assignment, matching the authorization's own uniqueness. Two commands for one assignment
    -- would mean two different descriptions of the same execution, and nothing could say which was authoritative.
    CONSTRAINT uq_execution_commands_assignment UNIQUE (attempt_id, assignment_epoch),
    CONSTRAINT uq_execution_commands_authorization UNIQUE (authorization_id),
    -- The command's own assignment fields must be the authorization's. They were previously free: a command
    -- carrying a valid authorization_id was accepted naming a different attempt, a different epoch, a different
    -- tenant, and a run that did not exist — and those are precisely the fields a consumer would fence on. The
    -- uniqueness above was therefore enforcing uniqueness over values that need not have been true.
    CONSTRAINT fk_execution_commands_authorization
        FOREIGN KEY (authorization_id, organization_id, run_id, attempt_id, assignment_epoch)
        REFERENCES execution_authorizations (
            authorization_id, organization_id, run_id, attempt_id, assignment_epoch)
);

CREATE OR REPLACE FUNCTION reject_execution_command_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'execution commands are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER execution_commands_immutable
BEFORE UPDATE OR DELETE ON execution_commands
FOR EACH ROW EXECUTE FUNCTION reject_execution_command_mutation();

-- TRUNCATE fires no row-level trigger, so every guard above is silent about it. Immutability that a single
-- statement can bypass is not immutability, and the statement in question is one a careless cleanup script
-- reaches for first.
CREATE TRIGGER execution_commands_untruncatable
BEFORE TRUNCATE ON execution_commands
FOR EACH STATEMENT EXECUTE FUNCTION reject_execution_command_mutation();

CREATE TRIGGER execution_authorizations_untruncatable
BEFORE TRUNCATE ON execution_authorizations
FOR EACH STATEMENT EXECUTE FUNCTION reject_execution_authorization_delete();

CREATE TRIGGER execution_capabilities_untruncatable
BEFORE TRUNCATE ON execution_capabilities
FOR EACH STATEMENT EXECUTE FUNCTION reject_execution_capability_delete();

CREATE TRIGGER network_policy_revisions_untruncatable
BEFORE TRUNCATE ON network_policy_revisions
FOR EACH STATEMENT EXECUTE FUNCTION reject_network_policy_mutation();

CREATE TRIGGER execution_capability_secret_references_untruncatable
BEFORE TRUNCATE ON execution_capability_secret_references
FOR EACH STATEMENT EXECUTE FUNCTION reject_capability_secret_mutation();
