package com.kaas.api.consumer.infrastructure;

import com.kaas.api.consumer.application.DispatchInboxRepository;
import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.consumer.domain.InboxRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDispatchInboxRepository implements DispatchInboxRepository {
    /**
     * A distinct advisory lock class, so a message identity can never collide with an idempotency key or an
     * organization and invert a lock ordering somewhere else. Class 1 is request idempotency, 2 is admission.
     */
    private static final int LOCK_CLASS = 3;

    /** Matches ck_dispatch_inbox_deliveries. The counter saturates here rather than violating it. */
    private static final int MAX_DELIVERY_COUNT = 1_000_000;

    private final JdbcTemplate jdbc;

    JdbcDispatchInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises concurrent deliveries of one message. Without it two copies delivered at the same moment both
     * read an empty inbox, both attempt a claim, and the loser's insert fails on the unique key after it has
     * already done work — correct in the end, but only by accident and only because the claim itself is a
     * compare-and-set. Locking first makes the check and the decision one critical section.
     */
    @Override
    public void lockMessage(String consumer, UUID messageId) {
        jdbc.queryForObject(
                // The key covers the consumer as well as the message, because the uniqueness it protects is
                // (consumer, message_id). Keying on the message alone would serialise two consumer groups against
                // each other while they touch entirely independent rows.
                "select pg_advisory_xact_lock(?, hashtext(?) # ?)",
                Object.class,
                LOCK_CLASS,
                consumer,
                // The low bits of the message identity are already uniformly distributed, and a collision only
                // costs two unrelated messages a shared lock rather than correctness.
                (int) messageId.getLeastSignificantBits());
    }

    @Override
    public Optional<InboxRecord> find(String consumer, UUID messageId) {
        return jdbc
                .query(
                        """
                        select inbox_id, consumer, message_id, payload_digest, organization_id, project_id,
                               run_id, disposition, reason, first_received_at, last_received_at, decided_at,
                               delivery_count
                          from dispatch_inbox
                         where consumer = ? and message_id = ?
                        """,
                        JdbcDispatchInboxRepository::inbox,
                        consumer,
                        messageId)
                .stream()
                .findFirst();
    }

    /**
     * Records another offer of a message that already has a decision.
     *
     * <p>Two details are load-bearing. The instant is clamped to never move backwards, because the row was
     * stamped from the application clock and this runs on the database's: when the application host leads, a bare
     * {@code clock_timestamp()} is *older* than the value it replaces, the guard rejects it, and the consumer
     * treats that as a failure and requeues. The message can then never be acknowledged — which breaks the exact
     * mechanism that makes committing before acknowledging safe.
     *
     * <p>And the counter saturates rather than overflowing its bound. Incrementing past the ceiling would violate
     * the CHECK on every subsequent delivery of that message, forever, with no legal statement able to lower it:
     * a poison pill only DDL could clear.
     */
    @Override
    public void countRedelivery(String consumer, UUID messageId) {
        jdbc.update(
                """
                update dispatch_inbox
                   set delivery_count = least(delivery_count + 1, ?),
                       last_received_at = greatest(clock_timestamp(), last_received_at)
                 where consumer = ? and message_id = ?
                """,
                MAX_DELIVERY_COUNT,
                consumer,
                messageId);
    }

    @Override
    public void record(InboxRecord decision) {
        jdbc.update(
                """
                insert into dispatch_inbox
                    (inbox_id, consumer, message_id, payload_digest, organization_id, project_id, run_id,
                     disposition, reason, first_received_at, last_received_at, decided_at, delivery_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                decision.inboxId(),
                decision.consumer(),
                decision.messageId(),
                decision.payloadDigest(),
                decision.organizationId(),
                decision.projectId(),
                decision.runId(),
                decision.disposition().name(),
                decision.reason(),
                Timestamp.from(decision.firstReceivedAt()),
                Timestamp.from(decision.lastReceivedAt()),
                Timestamp.from(decision.decidedAt()));
    }

    private static InboxRecord inbox(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InboxRecord(
                resultSet.getObject("inbox_id", UUID.class),
                resultSet.getString("consumer"),
                resultSet.getObject("message_id", UUID.class),
                resultSet.getString("payload_digest"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                InboxDisposition.valueOf(resultSet.getString("disposition")),
                resultSet.getString("reason"),
                instant(resultSet, "first_received_at"),
                instant(resultSet, "last_received_at"),
                instant(resultSet, "decided_at"),
                resultSet.getInt("delivery_count"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
