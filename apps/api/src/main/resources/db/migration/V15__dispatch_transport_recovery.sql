-- KAAS-MSG-001: PostgreSQL-authoritative reconstruction of a dispatch RabbitMQ lost after publication.
--
-- Expand-only. Nothing existing is altered: execution_dispatches stays immutable, outbox_messages keeps its guard,
-- and published_at keeps meaning "the FIRST publication the broker confirmed". A recovery republication is a
-- further transport event for the SAME message -- same message_id, same bytes -- and it is recorded here, beside
-- the outbox row, rather than by rewriting the outbox row until only the latest attempt survives.
--
-- One row per dispatch that recovery has ever considered, created lazily when a published dispatch first
-- becomes eligible. The previous release neither reads nor writes this table.

CREATE TABLE dispatch_recoveries (
    message_id uuid PRIMARY KEY,
    outbox_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    run_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    -- Every republication attempt, confirmed or not, and the confirmed ones among them.
    recovery_attempts integer NOT NULL,
    recovery_publications integer NOT NULL,
    first_recovered_at timestamptz,
    last_recovered_at timestamptz,
    last_attempt_at timestamptz,
    last_failure_code varchar(64),
    -- When this dispatch may next be considered. Backoff after a failure; a growing interval after a success.
    next_attempt_at timestamptz NOT NULL,
    -- One recovery instance at a time. All-or-nothing, like the relay's claim.
    claim_id uuid,
    claimed_at timestamptz,
    claim_expires_at timestamptz,
    CONSTRAINT fk_dispatch_recoveries_outbox FOREIGN KEY (message_id) REFERENCES outbox_messages (message_id),
    CONSTRAINT uq_dispatch_recoveries_outbox UNIQUE (outbox_id),
    CONSTRAINT ck_dispatch_recoveries_counts
        CHECK (recovery_attempts BETWEEN 0 AND 1000 AND recovery_publications BETWEEN 0 AND recovery_attempts),
    CONSTRAINT ck_dispatch_recoveries_history
        CHECK ((recovery_publications = 0 AND first_recovered_at IS NULL AND last_recovered_at IS NULL)
               OR (recovery_publications > 0 AND first_recovered_at IS NOT NULL AND last_recovered_at IS NOT NULL
                   AND last_recovered_at >= first_recovered_at)),
    CONSTRAINT ck_dispatch_recoveries_attempted
        CHECK ((recovery_attempts = 0) = (last_attempt_at IS NULL)),
    CONSTRAINT ck_dispatch_recoveries_failure_code
        CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[A-Z_]{1,64}$'),
    CONSTRAINT ck_dispatch_recoveries_claim_shape
        CHECK ((claim_id IS NULL AND claimed_at IS NULL AND claim_expires_at IS NULL)
               OR (claim_id IS NOT NULL AND claimed_at IS NOT NULL AND claim_expires_at > claimed_at))
);

-- The recovery pass's access path: unclaimed-or-expired rows that are due.
CREATE INDEX ix_dispatch_recoveries_due ON dispatch_recoveries (next_attempt_at, message_id);

-- No new index on outbox_messages. Eligibility is driven from QUEUED runs (ix_test_runs_queue_deadline), then the
-- run's current attempt, its dispatch (uq_execution_dispatches_attempt) and that dispatch's outbox row (by message
-- id): each step indexed, and none walks outbox history, which is never pruned. An index over published outbox
-- rows would cover every dispatch ever sent, and building it would lock the outbox for the running release.

CREATE OR REPLACE FUNCTION guard_dispatch_recovery()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'dispatch recoveries are retained as transport evidence' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Born unattempted, and only for a dispatch the relay really published: recovery reconstructs a
        -- delivery that happened and was lost, never one that never happened.
        IF NEW.recovery_attempts = 0 AND NEW.recovery_publications = 0 AND NEW.last_failure_code IS NULL
           AND NEW.first_recovered_at IS NULL AND NEW.last_recovered_at IS NULL AND NEW.last_attempt_at IS NULL
           AND NEW.claim_id IS NULL
           AND EXISTS (SELECT 1 FROM outbox_messages o
                        WHERE o.message_id = NEW.message_id AND o.outbox_id = NEW.outbox_id
                          AND o.organization_id = NEW.organization_id AND o.run_id = NEW.run_id
                          AND o.message_type = 'EXECUTION_DISPATCH'
                          AND o.published_at IS NOT NULL AND o.terminal_disposition IS NULL) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a dispatch recovery starts unattempted, for a published execution dispatch'
            USING ERRCODE = '23514';
    END IF;

    -- UPDATE: identity never moves; history only grows.
    IF NEW.message_id <> OLD.message_id OR NEW.outbox_id <> OLD.outbox_id
       OR NEW.organization_id <> OLD.organization_id OR NEW.run_id <> OLD.run_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'a dispatch recovery identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.recovery_attempts < OLD.recovery_attempts OR NEW.recovery_publications < OLD.recovery_publications
       OR NEW.recovery_attempts > OLD.recovery_attempts + 1
       OR NEW.recovery_publications > OLD.recovery_publications + 1
       OR (OLD.first_recovered_at IS NOT NULL AND NEW.first_recovered_at IS DISTINCT FROM OLD.first_recovered_at)
       OR (OLD.last_recovered_at IS NOT NULL AND NEW.last_recovered_at < OLD.last_recovered_at) THEN
        RAISE EXCEPTION 'dispatch recovery history only grows, one attempt at a time' USING ERRCODE = '23514';
    END IF;
    -- A republication is an attempt: publications cannot move without attempts moving with them.
    IF NEW.recovery_publications = OLD.recovery_publications + 1
       AND NEW.recovery_attempts <> OLD.recovery_attempts + 1 THEN
        RAISE EXCEPTION 'dispatch recovery history only grows, one attempt at a time' USING ERRCODE = '23514';
    END IF;
    IF NEW.recovery_attempts = OLD.recovery_attempts + 1 THEN
        -- A recorded attempt is a claim's outcome: it happens only as the claim that made it is released.
        IF OLD.claim_id IS NULL OR NEW.claim_id IS NOT NULL THEN
            RAISE EXCEPTION 'a recovery attempt is recorded by the claim that made it' USING ERRCODE = '23514';
        END IF;
        IF NEW.last_attempt_at IS NULL OR (OLD.last_attempt_at IS NOT NULL AND NEW.last_attempt_at < OLD.last_attempt_at)
           OR (NEW.recovery_publications = OLD.recovery_publications
               AND NEW.last_recovered_at IS DISTINCT FROM OLD.last_recovered_at) THEN
            RAISE EXCEPTION 'dispatch recovery history only grows, one attempt at a time' USING ERRCODE = '23514';
        END IF;
    ELSE
        -- Claiming and releasing touch the lease and nothing else: no history is rewritten between attempts.
        IF NEW.first_recovered_at IS DISTINCT FROM OLD.first_recovered_at
           OR NEW.last_recovered_at IS DISTINCT FROM OLD.last_recovered_at
           OR NEW.last_attempt_at IS DISTINCT FROM OLD.last_attempt_at
           OR NEW.last_failure_code IS DISTINCT FROM OLD.last_failure_code
           OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at THEN
            RAISE EXCEPTION 'dispatch recovery history only grows, one attempt at a time' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispatch_recoveries_guard
BEFORE INSERT OR UPDATE OR DELETE ON dispatch_recoveries
FOR EACH ROW EXECUTE FUNCTION guard_dispatch_recovery();

CREATE TRIGGER dispatch_recoveries_no_truncate
BEFORE TRUNCATE ON dispatch_recoveries FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();

-- The delivered marker recovery relies on is dispatch_inbox, which has always been retained: its guard refuses
-- DELETE and TRUNCATE. That retention is now load-bearing for recovery -- a pruned inbox row would make a
-- delivered dispatch look lost -- and KAAS-MSG-001's tests assert the refusal so a future retention policy
-- cannot remove it silently. Any retention introduced later must keep a row at least until its run is terminal.
