package com.kaas.api.outbox.infrastructure;

import com.kaas.api.outbox.application.DispatchRecoveryRepository;
import com.kaas.api.outbox.domain.OutboxMessage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcDispatchRecoveryRepository implements DispatchRecoveryRepository {
    private static final String SHA256 = "sha256:";

    /** The guard's ceiling on recorded attempts. A row at it is left to the queue deadline. */
    private static final int ATTEMPT_CEILING = 1000;

    /**
     * The joins every query here shares, driven from QUEUED runs rather than from outbox history: the outbox is
     * never pruned, so a query that started there would walk every dispatch ever published on every pass. From
     * the run, each step is an indexed lookup -- the queued-deadline index, the current attempt, the attempt's
     * dispatch, the dispatch's outbox row.
     */
    private static final String FROM_QUEUED_RUNS = """
              from test_runs r
              join execution_attempts a
                on a.organization_id = r.organization_id and a.attempt_id = r.current_attempt_id
              join execution_dispatches d
                on d.organization_id = r.organization_id and d.project_id = r.project_id
               and d.run_id = r.run_id and d.attempt_id = r.current_attempt_id
              join outbox_messages o
                on o.message_id = d.message_id and o.dispatch_id = d.dispatch_id
               and o.organization_id = d.organization_id
            """;

    /**
     * A published dispatch whose run is still waiting for delivery -- the ONE definition claiming and counting
     * share. Parameter: the consumer.
     */
    private static final String WAITING_WHERE = """
             where r.lifecycle_state = 'QUEUED'
               and r.queue_deadline_at > clock_timestamp()
               and a.attempt_state = 'WAITING_FOR_CLAIM'
               and o.message_type = 'EXECUTION_DISPATCH'
               and o.published_at is not null
               and o.terminal_disposition is null
               and not exists (select 1 from dispatch_inbox i
                                where i.consumer = ? and i.message_id = o.message_id)
            """;

    private static final String WAITING = FROM_QUEUED_RUNS + WAITING_WHERE;

    private final JdbcTemplate jdbc;

    JdbcDispatchRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<Claimed> claimEligible(String consumer, UUID claimId, int batchSize, Duration grace,
            int maxPublications, Duration claimTtl) {
        long graceMillis = grace.toMillis();
        // First sight of a newly eligible dispatch: a row to hold its recovery state. Idempotent under races, on
        // EVERY unique key -- two instances inserting the same row converge on one rather than one aborting.
        jdbc.update("""
                insert into dispatch_recoveries (message_id, outbox_id, organization_id, run_id, created_at,
                                                 recovery_attempts, recovery_publications, next_attempt_at)
                select o.message_id, o.outbox_id, o.organization_id, o.run_id, statement_timestamp(), 0, 0,
                       statement_timestamp()
                """ + WAITING + """
                   and o.published_at + (? * interval '1 millisecond') <= clock_timestamp()
                   and not exists (select 1 from dispatch_recoveries x where x.message_id = o.message_id)
                 order by r.queue_deadline_at, o.message_id
                 limit ?
                on conflict do nothing
                """, consumer, graceMillis, batchSize);
        // Then claim, skipping rows another instance holds. Eligibility is re-joined inside the claiming statement:
        // a row inserted a moment ago may already have been delivered, cancelled or expired. The lease is taken
        // on the statement's own clock and RETURNED, so the caller honours the lease the database will enforce
        // rather than one it computed later.
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                update dispatch_recoveries rec
                   set claim_id = ?, claimed_at = statement_timestamp(),
                       claim_expires_at = statement_timestamp() + (? * interval '1 millisecond')
                 where rec.message_id in (
                       select x.message_id
                """ + FROM_QUEUED_RUNS + """
                          join dispatch_recoveries x on x.message_id = o.message_id
                """ + WAITING_WHERE + """
                          and (x.claim_id is null or x.claim_expires_at <= clock_timestamp())
                          and x.next_attempt_at <= clock_timestamp()
                          and x.recovery_publications < ?
                          and x.recovery_attempts < ?
                          and greatest(o.published_at, coalesce(x.last_recovered_at, o.published_at))
                              + (? * interval '1 millisecond') <= clock_timestamp()
                        order by x.next_attempt_at, x.message_id
                        limit ?
                          for update of x skip locked)
                returning rec.message_id, rec.recovery_attempts, rec.recovery_publications, rec.claim_expires_at
                """, claimId, claimTtl.toMillis(), consumer, maxPublications, ATTEMPT_CEILING, graceMillis, batchSize);
        if (claimed.isEmpty()) {
            return List.of();
        }
        UUID[] ids = claimed.stream().map(row -> (UUID) row.get("message_id")).toArray(UUID[]::new);
        // The bytes the relay published, from the outbox row whose guard keeps them immutable. Recovery never
        // re-serializes a dispatch from domain state.
        Map<UUID, OutboxMessage> messages = jdbc.query("""
                select outbox_id, message_id, message_type, schema_version, organization_id, project_id, run_id,
                       dispatch_id, payload::text as payload, payload_sha256, occurred_at, publish_attempts
                  from outbox_messages where message_id = any(?)
                """, JdbcDispatchRecoveryRepository::message, (Object) ids)
                .stream().collect(Collectors.toMap(OutboxMessage::messageId, Function.identity()));
        return claimed.stream()
                .map(row -> new Claimed(messages.get((UUID) row.get("message_id")),
                        ((Number) row.get("recovery_attempts")).intValue(),
                        ((Number) row.get("recovery_publications")).intValue(),
                        ((Timestamp) row.get("claim_expires_at")).toInstant()))
                .toList();
    }

    @Override
    public Optional<String> ineligibility(String consumer, UUID messageId) {
        // The same joins as WAITING, so the re-check and the claim cannot disagree about which rows they mean;
        // only the verdict is spelled out rather than filtered on.
        List<String> reasons = jdbc.query("""
                select case
                         when o.message_type <> 'EXECUTION_DISPATCH' or o.published_at is null
                              or o.terminal_disposition is not null then 'NOT_PUBLISHED'
                         when exists (select 1 from dispatch_inbox i
                                       where i.consumer = ? and i.message_id = o.message_id) then 'DELIVERED'
                         when r.lifecycle_state <> 'QUEUED' then 'RUN_NOT_QUEUED'
                         when a.attempt_state <> 'WAITING_FOR_CLAIM' then 'ATTEMPT_NOT_WAITING'
                         when r.queue_deadline_at <= clock_timestamp() then 'QUEUE_DEADLINE_PASSED'
                         else 'ELIGIBLE'
                       end as verdict
                """ + FROM_QUEUED_RUNS + """
                 where o.message_id = ?
                """, (resultSet, index) -> resultSet.getString("verdict"), consumer, messageId);
        // No row through those joins at all: the run's current attempt is no longer this dispatch's.
        String verdict = reasons.isEmpty() ? "ATTEMPT_NOT_WAITING" : reasons.get(0);
        return "ELIGIBLE".equals(verdict) ? Optional.empty() : Optional.of(verdict);
    }

    @Override
    public boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt) {
        // One instant for the whole write, so first/last recovery cannot be recorded out of order.
        return jdbc.update("""
                        update dispatch_recoveries
                           set recovery_attempts = recovery_attempts + 1,
                               recovery_publications = recovery_publications + 1,
                               first_recovered_at = coalesce(first_recovered_at, statement_timestamp()),
                               last_recovered_at = statement_timestamp(),
                               last_attempt_at = statement_timestamp(),
                               last_failure_code = null,
                               next_attempt_at = ?,
                               claim_id = null, claimed_at = null, claim_expires_at = null
                         where message_id = ? and claim_id = ?
                        """, Timestamp.from(nextAttemptAt), messageId, claimId)
                == 1;
    }

    @Override
    public boolean recordFailed(UUID messageId, UUID claimId, String failureCode, Instant nextAttemptAt) {
        return jdbc.update("""
                        update dispatch_recoveries
                           set recovery_attempts = recovery_attempts + 1,
                               last_attempt_at = statement_timestamp(),
                               last_failure_code = ?,
                               next_attempt_at = ?,
                               claim_id = null, claimed_at = null, claim_expires_at = null
                         where message_id = ? and claim_id = ?
                        """, failureCode, Timestamp.from(nextAttemptAt), messageId, claimId)
                == 1;
    }

    @Override
    public boolean release(UUID messageId, UUID claimId) {
        return jdbc.update("""
                        update dispatch_recoveries set claim_id = null, claimed_at = null, claim_expires_at = null
                         where message_id = ? and claim_id = ?
                        """, messageId, claimId)
                == 1;
    }

    @Override
    public long countEligible(String consumer, Duration grace, int maxPublications) {
        // Exactly what a pass could claim, ignoring only the lease: the same waiting set, the same spacing
        // (next_attempt_at and the grace since the last publication), the same caps.
        Long count = jdbc.queryForObject("""
                select count(*)
                """ + FROM_QUEUED_RUNS + """
                  left join dispatch_recoveries x on x.message_id = o.message_id
                """ + WAITING_WHERE + """
                   and coalesce(x.recovery_publications, 0) < ?
                   and coalesce(x.recovery_attempts, 0) < ?
                   and coalesce(x.next_attempt_at, clock_timestamp()) <= clock_timestamp()
                   and greatest(o.published_at, coalesce(x.last_recovered_at, o.published_at))
                       + (? * interval '1 millisecond') <= clock_timestamp()
                """, Long.class, consumer, maxPublications, ATTEMPT_CEILING, grace.toMillis());
        return count == null ? 0 : count;
    }

    @Override
    public long countCapped(String consumer, int maxPublications) {
        Long count = jdbc.queryForObject("""
                select count(*)
                """ + WAITING + """
                   and exists (select 1 from dispatch_recoveries x
                                where x.message_id = o.message_id and x.recovery_publications >= ?)
                """, Long.class, consumer, maxPublications);
        return count == null ? 0 : count;
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private static OutboxMessage message(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
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
