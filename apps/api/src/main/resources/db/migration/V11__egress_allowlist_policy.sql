-- V11 gives an egress policy something to say beyond "no network at all".
--
-- ALLOWLIST has existed as a type since V9 and has always been refused, because nothing could enforce it. This
-- migration builds the POLICY MODEL it needs — the destinations a policy permits, immutably, bound to a digest
-- a command can carry. It does NOT make ALLOWLIST enforceable: NetworkPolicyType.ALLOWLIST is still
-- enforceable=false, the control plane still denies authorization for it, and the runner still refuses a
-- command that names it. Enforcement arrives only once the proxy, the topology, and the fencing that make it
-- real are proven.
--
-- OPERATIONAL NOTE. This migration drops and re-adds nothing on a large table. It creates one child table and
-- restates one seeded platform row. It takes ACCESS EXCLUSIVE on network_policy_revisions for the duration of
-- that restatement, which holds a single row.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------
-- Destinations
-- ---------------------------------------------------------------------------------------------------------

-- What a policy permits, one row per destination.
--
-- A child table rather than a JSON column, because these rows are compared, sorted, and counted by the digest
-- and will be read by an authorization path on every outbound connection. A JSON blob would make each of those
-- a parse, and would let a malformed entry sit undetected until the moment it mattered.
--
-- Canonicalization is specified in packages/api-contracts/egress-allowlist-canonicalization.md and enforced
-- here as CHECKs as well as in the domain model. The database is the last line: a destination that reached this
-- table in a non-canonical form would be one the proxy could never match, and a tenant would see it stored and
-- believe it was working.
CREATE TABLE network_policy_destinations (
    policy_revision_id uuid NOT NULL REFERENCES network_policy_revisions (policy_revision_id),
    host varchar(253) NOT NULL,
    port integer NOT NULL,
    scheme varchar(8) NOT NULL,

    -- Lower-case ASCII letters, digits, hyphens, and dots only. This one pattern refuses Unicode,
    -- percent-encoding, userinfo, brackets, whitespace, upper case, and every wildcard form at once.
    CONSTRAINT ck_network_policy_destination_host_charset
        CHECK (host ~ '^[a-z0-9.-]+$'),
    -- At least two labels, no empty label, no leading or trailing dot, and no label starting or ending with a
    -- hyphen. A single-label host is a local name rather than a destination, which also removes 'localhost'.
    CONSTRAINT ck_network_policy_destination_host_shape
        CHECK (host ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'),
    -- No IPv4 literal. An entry names a hostname the proxy resolves, and the address classifier exists to
    -- inspect what that resolution returned; a literal reaches connect without passing it.
    CONSTRAINT ck_network_policy_destination_not_ip_literal
        CHECK (host !~ '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_network_policy_destination_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_network_policy_destination_scheme CHECK (scheme IN ('HTTP', 'HTTPS')),
    -- One row per destination per policy. A duplicate would change the digest's destination count while
    -- describing the same policy.
    CONSTRAINT uq_network_policy_destination UNIQUE (policy_revision_id, host, port, scheme)
);

CREATE INDEX ix_network_policy_destinations_revision
    ON network_policy_destinations (policy_revision_id);

-- Destinations are as immutable as the revision that owns them. A policy whose permitted destinations could be
-- edited after a run bound its digest would make that digest a claim about the past rather than about what is
-- enforced now.
CREATE OR REPLACE FUNCTION reject_network_policy_destination_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'network policy destinations are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER network_policy_destinations_immutable
    BEFORE UPDATE OR DELETE ON network_policy_destinations
    FOR EACH ROW EXECUTE FUNCTION reject_network_policy_destination_mutation();

-- Row triggers do not fire on TRUNCATE, so immutability that one statement bypasses is not immutability.
CREATE TRIGGER network_policy_destinations_untruncatable
    BEFORE TRUNCATE ON network_policy_destinations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_network_policy_destination_mutation();

-- ---------------------------------------------------------------------------------------------------------
-- The canonical digest now covers destinations
-- ---------------------------------------------------------------------------------------------------------

-- The digest format moves from kaas.network-policy.v1 to v2, which adds the destination count and the sorted
-- destinations to the preimage.
--
-- WHY THE SEEDED DENY_ALL ROW IS RESTATED. Covering destinations ALWAYS — including a count of zero — changes
-- the digest of every policy, DENY_ALL included. The alternative was a conditional encoding that omitted the
-- destination component for DENY_ALL and so left its existing digest untouched. That was more convenient and
-- worse: it is exactly the shape of special case that later lets a field go uncovered, and a DENY_ALL that
-- somehow acquired a destination would have had it excluded from its own digest. One canonical form, no
-- exceptions, and one row restated here.
--
-- CONSEQUENCE, STATED PLAINLY. An ExecutionCommand issued before this migration binds the v1 digest and will
-- be refused by the runner afterwards. Commands carry a five-minute TTL and the runner revalidates authority
-- before acting, so the window is bounded and the failure is a refusal rather than a silent acceptance — it
-- fails in the direction that stops work rather than the direction that permits it.
ALTER TABLE network_policy_revisions DISABLE TRIGGER network_policy_revisions_immutable;

UPDATE network_policy_revisions
   SET canonical_digest = 'sha256:3944c369d57700eb13ce96b492fbac7ea9443a61faa8985a01e2394ab40e0de6'
 WHERE policy_revision_id = '00000000-0000-4000-8000-00000000d001'
   AND policy_type = 'DENY_ALL'
   AND policy_version = 1;

ALTER TABLE network_policy_revisions ENABLE TRIGGER network_policy_revisions_immutable;

-- The immutability trigger is re-enabled above, and this asserts it: a migration that disabled a guard and
-- failed to restore it would leave every later write unguarded, and nothing else in the schema would notice.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'network_policy_revisions_immutable'
           AND tgrelid = 'network_policy_revisions'::regclass
           AND tgenabled <> 'D')
    THEN
        RAISE EXCEPTION 'the network policy immutability trigger was left disabled';
    END IF;
END;
$$;
