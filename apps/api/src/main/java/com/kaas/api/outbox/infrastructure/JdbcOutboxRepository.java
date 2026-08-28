package com.kaas.api.outbox.infrastructure;

import com.kaas.api.outbox.application.OutboxRepository;
import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.TerminalDisposition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {
    private static final String SHA256 = "sha256:";

    private final JdbcTemplate jdbc;

    JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One statement, so it is atomic without an explicit transaction and holds no lock beyond its own execution.
     * {@code SKIP LOCKED} makes concurrent relays take disjoint work instead of queueing behind each other, and a
     * claim whose lease has expired is reclaimable so a crashed relay cannot strand a message.
     */
    @Override
    public List<OutboxMessage> claimPending(UUID relayClaimId, int batchSize, Duration claimTtl) {
        return jdbc.query(
                """
                update outbox_messages
                   set relay_claim_id = ?, relay_claimed_at = now(),
                       relay_claim_expires_at = now() + (? * interval '1 second')
                 where outbox_id in (
                       select outbox_id from outbox_messages
                        where published_at is null and terminal_disposition is null
                          and available_at <= now()
                          and (relay_claim_id is null or relay_claim_expires_at <= now())
                        order by available_at, message_id
                        for update skip locked
                        limit ?)
                returning outbox_id, message_id, message_type, schema_version, organization_id, project_id,
                          run_id, dispatch_id, payload::text as payload, payload_sha256, occurred_at,
                          publish_attempts
                """,
                JdbcOutboxRepository::message,
                relayClaimId,
                claimTtl.toSeconds(),
                batchSize);
    }

    @Override
    public boolean releaseClaim(UUID outboxId, UUID relayClaimId) {
        return jdbc.update(
                        """
                        update outbox_messages
                           set relay_claim_id = null, relay_claimed_at = null, relay_claim_expires_at = null
                         where outbox_id = ? and relay_claim_id = ?
                           and published_at is null and terminal_disposition is null
                        """,
                        outboxId, relayClaimId)
                == 1;
    }

    /**
     * Clearing {@code last_failure_code} is required, not cosmetic: a message that failed earlier would otherwise
     * carry the code into its published state, which both the delivery guard and ck_outbox_published_clean
     * reject. The attempt count preserves the fact that it took more than one try.
     */
    @Override
    public boolean recordPublished(UUID outboxId, UUID relayClaimId, Instant attemptedAt) {
        return jdbc.update(
                        """
                        update outbox_messages
                           set published_at = ?, last_attempt_at = ?, publish_attempts = publish_attempts + 1,
                               last_failure_code = null,
                               relay_claim_id = null, relay_claimed_at = null, relay_claim_expires_at = null
                         where outbox_id = ? and relay_claim_id = ?
                           and published_at is null and terminal_disposition is null
                        """,
                        Timestamp.from(attemptedAt), Timestamp.from(attemptedAt), outboxId, relayClaimId)
                == 1;
    }

    @Override
    public boolean recordRetry(
            UUID outboxId, UUID relayClaimId, Instant attemptedAt, Instant availableAt, String failureCode) {
        return jdbc.update(
                        """
                        update outbox_messages
                           set available_at = ?, last_attempt_at = ?, last_failure_code = ?,
                               publish_attempts = publish_attempts + 1,
                               relay_claim_id = null, relay_claimed_at = null, relay_claim_expires_at = null
                         where outbox_id = ? and relay_claim_id = ?
                           and published_at is null and terminal_disposition is null
                        """,
                        Timestamp.from(availableAt), Timestamp.from(attemptedAt), failureCode,
                        outboxId, relayClaimId)
                == 1;
    }

    @Override
    public boolean recordTerminal(
            UUID outboxId,
            UUID relayClaimId,
            Instant attemptedAt,
            TerminalDisposition disposition,
            String failureCode) {
        return jdbc.update(
                        """
                        update outbox_messages
                           set terminal_disposition = ?, last_failure_code = ?, last_attempt_at = ?,
                               publish_attempts = publish_attempts + 1,
                               relay_claim_id = null, relay_claimed_at = null, relay_claim_expires_at = null
                         where outbox_id = ? and relay_claim_id = ?
                           and published_at is null and terminal_disposition is null
                        """,
                        disposition.name(), failureCode, Timestamp.from(attemptedAt), outboxId, relayClaimId)
                == 1;
    }

    @Override
    public long countPending() {
        Long pending = jdbc.queryForObject(
                "select count(*) from outbox_messages"
                        + " where published_at is null and terminal_disposition is null",
                Long.class);
        return pending == null ? 0L : pending;
    }

    /**
     * Counts messages that failed to be delivered. Suppressed messages are deliberately excluded: they are
     * terminal, but nothing went wrong — the run they would have dispatched ended before they were sent. Counting
     * them here would turn every cancellation into a dead letter and make the relay's health say the broker is
     * failing when it is not.
     */
    /**
     * The predicate is derived from the enum rather than written out, so a disposition added on the wrong side of
     * {@link TerminalDisposition#deliveryFailure()} cannot quietly land in or out of the dead-letter count. It
     * matches ix_outbox_dead_letters exactly — an implied predicate would cost a heap scan with a per-row recheck
     * on every health probe and every metrics scrape, over a table that is never pruned.
     */
    @Override
    public long countTerminal() {
        Object[] deliveryFailures = Arrays.stream(TerminalDisposition.values())
                .filter(TerminalDisposition::deliveryFailure)
                .map(TerminalDisposition::name)
                .toArray();
        String placeholders = String.join(", ", Collections.nCopies(deliveryFailures.length, "?"));
        Long terminal = jdbc.queryForObject(
                "select count(*) from outbox_messages where terminal_disposition in (" + placeholders + ")",
                Long.class,
                deliveryFailures);
        return terminal == null ? 0L : terminal;
    }

    @Override
    public long oldestPendingAgeSeconds() {
        Long age = jdbc.queryForObject(
                """
                select coalesce(extract(epoch from now() - min(occurred_at)), 0)::bigint
                  from outbox_messages
                 where published_at is null and terminal_disposition is null
                """,
                Long.class);
        return age == null ? 0L : age;
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private static OutboxMessage message(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxMessage(
                resultSet.getObject("outbox_id", UUID.class),
                resultSet.getObject("message_id", UUID.class),
                resultSet.getString("message_type"),
                resultSet.getString("schema_version"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("dispatch_id", UUID.class),
                resultSet.getString("payload"),
                SHA256 + resultSet.getString("payload_sha256"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getInt("publish_attempts"));
    }
}
