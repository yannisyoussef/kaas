-- KAAS-DEPLOY-001: workers claim their own work, and submit the evidence that authorizes it.
--
-- Expand-only. The previous release neither reads nor writes anything added here, and nothing it wrote is
-- rewritten: its consumer recorded CLAIMED/STALE/REJECTED/CONFLICT, all of which the widened CHECK still admits.

-- ---------------------------------------------------------------------------------------------------------
-- Delivery is no longer a claim
-- ---------------------------------------------------------------------------------------------------------

-- The consumer used to claim every run it received for one configured worker id, which named nobody who would
-- actually execute it. It now records that the broker DELIVERED a corroborated dispatch, and a worker claims it
-- for itself through the internal assignment endpoint. A run is claimable only once its dispatch was delivered,
-- so RabbitMQ stays the delivery path: nothing here lets a worker reach a run the broker never handed over.
ALTER TABLE dispatch_inbox DROP CONSTRAINT ck_dispatch_inbox_disposition;
ALTER TABLE dispatch_inbox ADD CONSTRAINT ck_dispatch_inbox_disposition
    CHECK (disposition IN ('DELIVERED', 'CLAIMED', 'STALE', 'REJECTED', 'CONFLICT'));

-- The claim query's access path: delivered decisions, by the run they are about.
CREATE INDEX ix_dispatch_inbox_delivered
    ON dispatch_inbox (consumer, organization_id, run_id) WHERE disposition = 'DELIVERED';

-- ---------------------------------------------------------------------------------------------------------
-- Runner-submitted sandbox security evidence
-- ---------------------------------------------------------------------------------------------------------

-- A runner re-assesses its runtime on a schedule and submits the signed result, so evidence stays fresh without
-- restarting anything. The signature, not the submitter, is the authority: a document is stored only after it
-- verified against a key the OPERATOR pinned, and it is re-verified whenever it is used. The submitting worker
-- is recorded because evidence describes one host, and an authorization for a worker uses that worker's own
-- latest evidence and nobody else's.
CREATE TABLE sandbox_attestations (
    submission_id uuid PRIMARY KEY,
    worker_id varchar(255) NOT NULL,
    attestation_id varchar(128) NOT NULL,
    key_id varchar(128) NOT NULL,
    payload_digest varchar(80) NOT NULL,
    runtime_subject varchar(255) NOT NULL,
    runtime_implementation_digest varchar(80) NOT NULL,
    assessed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    -- The signed document itself, so every use re-verifies it rather than trusting a column someone could edit.
    document text NOT NULL,
    CONSTRAINT uq_sandbox_attestations_worker_attestation UNIQUE (worker_id, attestation_id),
    CONSTRAINT ck_sandbox_attestations_worker CHECK (worker_id ~ '^kaas\.worker\.[A-Za-z0-9._-]{1,200}$'),
    CONSTRAINT ck_sandbox_attestations_digest CHECK (payload_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_sandbox_attestations_implementation
        CHECK (runtime_implementation_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_sandbox_attestations_document CHECK (octet_length(document) BETWEEN 1 AND 262144)
);

CREATE INDEX ix_sandbox_attestations_worker_latest ON sandbox_attestations (worker_id, assessed_at DESC);

CREATE OR REPLACE FUNCTION guard_sandbox_attestations()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Evidence is appended, never edited. A submission that turned out wrong is superseded by a newer one; it is
    -- not rewritten into something it did not say. Deletion is left to a retention policy that does not exist yet.
    RAISE EXCEPTION 'submitted attestations are immutable evidence' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER sandbox_attestations_guard
BEFORE UPDATE OR DELETE ON sandbox_attestations
FOR EACH ROW EXECUTE FUNCTION guard_sandbox_attestations();

CREATE TRIGGER sandbox_attestations_no_truncate
BEFORE TRUNCATE ON sandbox_attestations FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();

-- ---------------------------------------------------------------------------------------------------------
-- Worker presence
-- ---------------------------------------------------------------------------------------------------------

-- When each worker last asked for work. Operational, not evidence: the deployment check reads it to tell "a
-- runner is polling" from "no runner exists", and a presence row authorizes nothing.
CREATE TABLE worker_presence (
    worker_id varchar(255) PRIMARY KEY,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    CONSTRAINT ck_worker_presence_worker CHECK (worker_id ~ '^kaas\.worker\.[A-Za-z0-9._-]{1,200}$'),
    CONSTRAINT ck_worker_presence_chronology CHECK (last_seen_at >= first_seen_at)
);
