-- Assignment-scoped egress authority.
--
-- No new table. An egress capability is an execution capability: it belongs to one ExecutionAuthorization,
-- which already binds the run, the attempt, the assignment epoch, the worker, and the network policy revision
-- this execution was authorized under. Building a second bearer-credential system beside the one that already
-- expresses all of that would mean two rotation rules, two revocation paths, two expiry ceilings, and two
-- places for them to disagree — and the one that disagrees is the one nobody is looking at.
--
-- What this migration does is widen one CHECK and add one that did not need to exist before.

ALTER TABLE execution_capabilities
    DROP CONSTRAINT ck_execution_capabilities_type;

ALTER TABLE execution_capabilities
    ADD CONSTRAINT ck_execution_capabilities_type
    CHECK (capability_type IN ('SOURCE', 'SECRET', 'EGRESS'));

-- AN EGRESS VALIDATION IS NOT A REDEMPTION, and this is the constraint that keeps it that way.
--
-- SOURCE and SECRET capabilities are exchanged for something: bytes, a value. The redemption counter bounds
-- how much can be extracted with one token, and its ceiling of 64 is a bound on amplification.
--
-- An egress capability is never exchanged for anything. It is presented, an authoritative yes-or-no is
-- computed, and nothing is delivered — and it is presented on every proxied request and again on every
-- revalidation of every open tunnel, which for a single execution is easily hundreds of times. Counting those
-- as redemptions would hit the ceiling within seconds and fence a healthy execution, and raising the ceiling
-- to accommodate it would quietly remove the amplification bound from the two capability types that need it.
--
-- So egress validations do not touch the counter. That is a decision made in the service layer, and this
-- constraint is what stops a later change there from being silently wrong: wiring egress validation into the
-- redemption path would fail on the first request rather than working for a while and then fencing a run for
-- reasons nobody could reconstruct.
ALTER TABLE execution_capabilities
    ADD CONSTRAINT ck_execution_capabilities_egress_is_not_redeemed
    CHECK (capability_type <> 'EGRESS' OR (redemption_count = 0 AND last_redeemed_at IS NULL));

-- ---------------------------------------------------------------------------------------------------------
-- Which policy an execution runs under, and how a project comes to have one.
-- ---------------------------------------------------------------------------------------------------------
--
-- Until now the authorization service looked up DENY_ALL by its well-known identifier, because DENY_ALL was
-- the only policy that existed. With a second enforceable type the policy has to be a property of the run
-- rather than a constant in the code, and it has to be pinned at the same moment everything else about the
-- run is pinned — otherwise a policy change mid-flight would alter what an already-authorized execution is
-- permitted to reach.

-- Policies become tenant-scopeable. NULL means platform-global, which is what the seeded DENY_ALL is and must
-- remain: every project uses that one row, and giving it an owner would mean copying it per tenant.
--
-- Both columns move together. A policy owned by an organization but not by a project would be scoped to
-- something no other table in this schema is scoped to, and the composite foreign keys below could not be
-- expressed against it.
ALTER TABLE network_policy_revisions
    ADD COLUMN organization_id uuid REFERENCES organizations (organization_id),
    ADD COLUMN project_id uuid;

ALTER TABLE network_policy_revisions
    ADD CONSTRAINT ck_network_policy_scope
    CHECK ((organization_id IS NULL) = (project_id IS NULL));

-- A DENY_ALL is global and an ALLOWLIST is a tenant's own list of destinations.
--
-- Stated as a constraint rather than left to the application because the consequence of getting it wrong is
-- one tenant's allowlist becoming reachable from another tenant's run, and "the service always sets this
-- correctly" is a claim about code that changes.
ALTER TABLE network_policy_revisions
    ADD CONSTRAINT ck_network_policy_allowlist_is_owned
    CHECK (policy_type <> 'ALLOWLIST' OR organization_id IS NOT NULL);

CREATE UNIQUE INDEX uq_network_policy_revisions_scope
    ON network_policy_revisions (policy_revision_id, organization_id, project_id);

-- Which policy a project's future runs will be sealed with. Mutable, like every other project setting; what
-- is immutable is the snapshot that copies it.
--
-- The default is the platform DENY_ALL, so every project that existed before this migration keeps exactly the
-- egress posture it already had. A default of anything else would silently widen what existing projects can
-- reach, which is the one direction a migration must never move on its own.
ALTER TABLE projects
    ADD COLUMN network_policy_revision_id uuid NOT NULL
        DEFAULT '00000000-0000-4000-8000-00000000d001'
        REFERENCES network_policy_revisions (policy_revision_id);

-- What THIS run was authorized under, fixed when the snapshot was sealed.
--
-- Deliberately NOT folded into content_sha256. That digest was computed for every existing snapshot without
-- this field, and adding it to the preimage would make every run already in a deployment fail its own
-- integrity check — permanently, not for a bounded window. The binding is protected by the snapshot's
-- immutability instead, and the ExecutionCommand carries the revision and its digest for the runner to verify
-- independently, which is where a worker actually needs it.
ALTER TABLE run_snapshots
    ADD COLUMN network_policy_revision_id uuid NOT NULL
        DEFAULT '00000000-0000-4000-8000-00000000d001'
        REFERENCES network_policy_revisions (policy_revision_id);

-- A project may name the global policy or one of its own, and nothing else.
--
-- A composite foreign key cannot express "either NULL-scoped or matching", so this is a trigger. It runs on
-- both tables that carry a policy reference, because a guard on one of them would leave the other as the way
-- in — and the run snapshot is the one that actually reaches an execution.
CREATE OR REPLACE FUNCTION require_policy_belongs_to_referrer()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    policy_organization uuid;
    policy_project uuid;
BEGIN
    SELECT organization_id, project_id INTO policy_organization, policy_project
      FROM network_policy_revisions
     WHERE policy_revision_id = NEW.network_policy_revision_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'network policy revision % does not exist', NEW.network_policy_revision_id
            USING ERRCODE = '23503';
    END IF;

    -- Platform-global: usable by everyone, which is what makes DENY_ALL the universal default.
    IF policy_organization IS NULL THEN
        RETURN NEW;
    END IF;

    IF policy_organization = NEW.organization_id AND policy_project = NEW.project_id THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a network policy belongs to the project that uses it' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER projects_policy_ownership
BEFORE INSERT OR UPDATE OF network_policy_revision_id ON projects
FOR EACH ROW EXECUTE FUNCTION require_policy_belongs_to_referrer();

CREATE TRIGGER run_snapshots_policy_ownership
BEFORE INSERT OR UPDATE OF network_policy_revision_id ON run_snapshots
FOR EACH ROW EXECUTE FUNCTION require_policy_belongs_to_referrer();

CREATE INDEX ix_network_policy_revisions_project
    ON network_policy_revisions (organization_id, project_id)
    WHERE organization_id IS NOT NULL;

-- Policy versions become unique PER OWNER rather than globally.
--
-- V9 declared UNIQUE (policy_type, policy_version) because DENY_ALL was the only policy that would ever
-- exist, and one row cannot collide with itself. With tenant-owned allowlists that constraint means exactly
-- one ALLOWLIST may exist across the entire platform: the second project to configure egress fails on a
-- unique violation naming a row belonging to a tenant it cannot see.
--
-- NULLS NOT DISTINCT is what keeps the other half of the guarantee. PostgreSQL treats NULLs as distinct in a
-- unique index by default, so without it the platform-global scope — where both owner columns are NULL —
-- would permit any number of DENY_ALL version 1 rows, and the well-known identifier every project defaults to
-- would stop being the only one of its kind.
ALTER TABLE network_policy_revisions
    DROP CONSTRAINT uq_network_policy_type_version;

CREATE UNIQUE INDEX uq_network_policy_scope_type_version
    ON network_policy_revisions (organization_id, project_id, policy_type, policy_version)
    NULLS NOT DISTINCT;
