-- V5 turns the execution-dispatch delivery table into a generalized, but still explicitly typed, outbox and
-- gives it a durable delivery-scheduling model owned by the database rather than by relay-process memory.
--
-- Two review findings are resolved here:
--   F-2  the outbox carried no payload of its own and was structurally welded to execution dispatch, so it could
--        never hold a second durable fact. It now owns an immutable payload; dispatch_id becomes an optional
--        domain reference; message_type is a controlled enum rather than a pinned constant.
--   F-1  retry timing lived nowhere, so a permanently unroutable message would be reselected on every relay tick
--        and could never be dead-lettered. available_at, attempt accounting, and a terminal disposition now live
--        in the row.

-- V4's guard rejects every outbox UPDATE, including this migration's own backfill. It must be dropped before the
-- data is touched, not merely replaced afterwards: otherwise V5 applies cleanly to an empty database and fails on
-- any database that already holds a message.
DROP TRIGGER outbox_messages_guard ON outbox_messages;

ALTER TABLE outbox_messages
    ADD COLUMN payload jsonb,
    ADD COLUMN available_at timestamptz,
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN terminal_disposition varchar(32),
    ADD COLUMN relay_claim_id uuid,
    ADD COLUMN relay_claimed_at timestamptz,
    ADD COLUMN relay_claim_expires_at timestamptz;

-- Deterministic backfill: every existing row is an execution dispatch, so its body is its dispatch's body and it
-- has always been immediately available.
UPDATE outbox_messages o
   SET payload = d.payload,
       available_at = o.occurred_at
  FROM execution_dispatches d
 WHERE d.dispatch_id = o.dispatch_id;

ALTER TABLE outbox_messages
    ALTER COLUMN payload SET NOT NULL,
    ALTER COLUMN available_at SET NOT NULL,
    ALTER COLUMN dispatch_id DROP NOT NULL;

-- The composite reference to execution_dispatches is MATCH SIMPLE, so it is simply not enforced when dispatch_id
-- is NULL. When it is present it still binds identity, tenancy, and payload digest to the dispatch row.
ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_dispatch_identity;
ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_initial_delivery;

ALTER TABLE outbox_messages
    -- A controlled enum, not free-form application input. Only EXECUTION_DISPATCH is produced today;
    -- RUN_STATE_CHANGED is declared so the schema demonstrably generalizes, and has no publisher.
    ADD CONSTRAINT ck_outbox_message_type
        CHECK (message_type IN ('EXECUTION_DISPATCH', 'RUN_STATE_CHANGED')),
    ADD CONSTRAINT ck_outbox_schema_version CHECK (schema_version = '1.0'),
    ADD CONSTRAINT ck_outbox_aggregate
        CHECK (aggregate_type = 'TEST_RUN' AND aggregate_id = run_id),
    -- An execution dispatch must still point at its dispatch row; other facts need not.
    ADD CONSTRAINT ck_outbox_dispatch_reference
        CHECK (message_type <> 'EXECUTION_DISPATCH' OR dispatch_id IS NOT NULL),
    ADD CONSTRAINT ck_outbox_payload_shape
        CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 16384),
    ADD CONSTRAINT ck_outbox_attempts CHECK (publish_attempts BETWEEN 0 AND 1000),
    -- Published and terminally failed are mutually exclusive end states.
    ADD CONSTRAINT ck_outbox_end_state
        CHECK (published_at IS NULL OR terminal_disposition IS NULL),
    ADD CONSTRAINT ck_outbox_terminal_disposition
        CHECK (terminal_disposition IS NULL
               OR terminal_disposition IN ('RETRIES_EXHAUSTED', 'PERMANENT_FAILURE')),
    -- A terminal row must say why; a published row must not carry a failure code.
    ADD CONSTRAINT ck_outbox_terminal_reason
        CHECK (terminal_disposition IS NULL OR last_failure_code IS NOT NULL),
    ADD CONSTRAINT ck_outbox_published_clean
        CHECK (published_at IS NULL OR last_failure_code IS NULL),
    -- An attempt must have been recorded before any outcome exists.
    ADD CONSTRAINT ck_outbox_attempt_accounting
        CHECK ((published_at IS NULL AND terminal_disposition IS NULL)
               OR (publish_attempts >= 1 AND last_attempt_at IS NOT NULL)),
    -- The relay claim is all-or-nothing, and an ended row holds no claim.
    ADD CONSTRAINT ck_outbox_claim_shape
        CHECK ((relay_claim_id IS NULL AND relay_claimed_at IS NULL AND relay_claim_expires_at IS NULL)
               OR (relay_claim_id IS NOT NULL AND relay_claimed_at IS NOT NULL
                   AND relay_claim_expires_at IS NOT NULL
                   AND relay_claim_expires_at > relay_claimed_at)),
    ADD CONSTRAINT ck_outbox_ended_unclaimed
        CHECK ((published_at IS NULL AND terminal_disposition IS NULL) OR relay_claim_id IS NULL);

-- Relay selection is driven by availability, with deterministic tie-breaking.
DROP INDEX ix_outbox_pending;
CREATE INDEX ix_outbox_claimable
    ON outbox_messages (available_at, message_id)
    WHERE published_at IS NULL AND terminal_disposition IS NULL;

-- The relay-side dead-letter set is read by health and metrics, and the table is never pruned, so the terminal
-- predicate needs its own index rather than a sequential scan that grows forever.
CREATE INDEX ix_outbox_terminal
    ON outbox_messages (occurred_at, message_id)
    WHERE terminal_disposition IS NOT NULL;

-- V4 rejected every outbox UPDATE because nothing could legitimately publish. Delivery state must now change, so
-- the guard is narrowed rather than removed: only claim, success, retry, and terminal transitions are accepted,
-- and every immutable semantic column must remain byte-identical in all of them.
CREATE OR REPLACE FUNCTION guard_outbox_message()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE existing_digest varchar(64);
DECLARE immutable_unchanged boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox messages are retained as delivery evidence' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT payload_sha256 INTO existing_digest FROM outbox_messages WHERE message_id = NEW.message_id;
        IF FOUND AND existing_digest IS DISTINCT FROM NEW.payload_sha256 THEN
            RAISE EXCEPTION 'outbox message identity has a conflicting payload digest' USING ERRCODE = '23514';
        END IF;
        IF NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL AND NEW.publish_attempts = 0
           AND NEW.last_attempt_at IS NULL AND NEW.last_failure_code IS NULL AND NEW.relay_claim_id IS NULL
           AND NEW.available_at = NEW.occurred_at THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a new outbox message must be unpublished, unclaimed, and immediately available'
            USING ERRCODE = '23514';
    END IF;

    -- Message identity and content can never change, whatever the delivery transition is.
    immutable_unchanged := (to_jsonb(NEW) - 'available_at' - 'last_attempt_at' - 'published_at'
                            - 'publish_attempts' - 'last_failure_code' - 'terminal_disposition'
                            - 'relay_claim_id' - 'relay_claimed_at' - 'relay_claim_expires_at')
                         = (to_jsonb(OLD) - 'available_at' - 'last_attempt_at' - 'published_at'
                            - 'publish_attempts' - 'last_failure_code' - 'terminal_disposition'
                            - 'relay_claim_id' - 'relay_claimed_at' - 'relay_claim_expires_at');

    -- REQUEUE: a deliberate operator action that returns a dead-lettered message to the pending set. This is the
    -- only way out of a terminal disposition, and it is deliberately not reachable from application code: the
    -- attempt budget must be reset explicitly and the failure code cleared, so a requeue is never accidental.
    -- Publication remains final; only a terminal row may be revived.
    IF coalesce(immutable_unchanged, false)
       AND OLD.terminal_disposition IS NOT NULL AND OLD.published_at IS NULL
       AND NEW.terminal_disposition IS NULL AND NEW.published_at IS NULL
       AND NEW.relay_claim_id IS NULL AND NEW.publish_attempts = 0
       AND NEW.last_failure_code IS NULL AND NEW.available_at >= OLD.available_at THEN
        RETURN NEW;
    END IF;

    IF coalesce(immutable_unchanged, false)
       -- A published row is final. A terminal row is revivable only through the requeue branch above.
       AND OLD.published_at IS NULL AND OLD.terminal_disposition IS NULL
       AND (
            -- CLAIM: take an available, unclaimed-or-expired row under a lease that is actually in the future.
            -- Nothing else moves. These predicates are deliberately not left to the repository's WHERE clause:
            -- a second relay implementation or a hand-written repair must not be able to steal a live lease or
            -- claim a row that is still serving its backoff.
            (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND NEW.relay_claim_id IS NOT NULL
             AND NEW.relay_claim_expires_at > now()
             AND NEW.available_at <= now()
             AND (OLD.relay_claim_id IS NULL OR OLD.relay_claim_expires_at <= now())
             AND NEW.publish_attempts = OLD.publish_attempts
             AND NEW.available_at = OLD.available_at
             AND NEW.last_attempt_at IS NOT DISTINCT FROM OLD.last_attempt_at
             AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code)
         OR -- RELEASE: a relay abandoning a batch hands the row back immediately instead of stranding it for the
            -- rest of the lease. Nothing but the claim moves, so no attempt is consumed.
            (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts
             AND NEW.available_at = OLD.available_at
             AND NEW.last_attempt_at IS NOT DISTINCT FROM OLD.last_attempt_at
             AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code)
         OR -- SUCCESS: publisher confirmed. The claim is released and no failure code survives.
            (NEW.published_at IS NOT NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NULL
             AND NEW.available_at = OLD.available_at)
         OR -- RETRY: transient failure. The row becomes available again strictly in the future, but within a
            -- bound, so a retry can never quietly park a message beyond any hope of delivery.
            (NEW.published_at IS NULL AND NEW.terminal_disposition IS NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NOT NULL
             AND NEW.available_at > OLD.available_at
             AND NEW.available_at <= now() + interval '1 day')
         OR -- TERMINAL: retries exhausted, or a permanent integrity/contract failure.
            (NEW.published_at IS NULL AND NEW.terminal_disposition IS NOT NULL
             AND OLD.relay_claim_id IS NOT NULL AND NEW.relay_claim_id IS NULL
             AND NEW.publish_attempts = OLD.publish_attempts + 1
             AND NEW.last_attempt_at IS NOT NULL AND NEW.last_failure_code IS NOT NULL)
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'only claim, release, publication, retry, terminal, and requeue transitions are supported'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER outbox_messages_guard
BEFORE INSERT OR UPDATE OR DELETE ON outbox_messages
FOR EACH ROW EXECUTE FUNCTION guard_outbox_message();

-- The scheduling bundle invariant must keep ignoring delivery state: a run that was legitimately published must
-- not make an unrelated test_runs update fail at commit. It gains only the message-type predicate, so a future
-- RUN_STATE_CHANGED row can never be mistaken for the dispatch the bundle requires.
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
           AND o.payload = d.payload
           AND o.message_type = 'EXECUTION_DISPATCH'
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
