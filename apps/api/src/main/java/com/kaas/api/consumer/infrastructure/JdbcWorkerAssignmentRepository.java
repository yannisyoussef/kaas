package com.kaas.api.consumer.infrastructure;

import com.kaas.api.consumer.application.WorkerAssignmentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWorkerAssignmentRepository implements WorkerAssignmentRepository {
    private final JdbcTemplate jdbc;

    JdbcWorkerAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DeliveredDispatch> lockNextDelivered(String consumer) {
        return jdbc
                .query(
                        """
                        select d.organization_id, d.message_id, d.payload::text as payload
                          from dispatch_inbox i
                          join execution_dispatches d
                            on d.organization_id = i.organization_id and d.message_id = i.message_id
                          join test_runs r
                            on r.organization_id = d.organization_id and r.run_id = d.run_id
                          join execution_attempts a
                            on a.organization_id = d.organization_id and a.attempt_id = d.attempt_id
                         where i.consumer = ?
                           and i.disposition = 'DELIVERED'
                           and r.lifecycle_state = 'QUEUED'
                           and r.current_attempt_id = d.attempt_id
                           and a.attempt_state = 'WAITING_FOR_CLAIM'
                           and r.queue_deadline_at > clock_timestamp()
                         order by r.queued_at, r.run_id
                         limit 1
                           for update of r skip locked
                        """,
                        (resultSet, rowNumber) -> new DeliveredDispatch(
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getObject("message_id", UUID.class),
                                resultSet.getString("payload")),
                        consumer)
                .stream()
                .findFirst();
    }

    @Override
    public boolean deliveredWaiting(String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from dispatch_inbox i
                      join execution_dispatches d
                        on d.organization_id = i.organization_id and d.message_id = i.message_id
                      join test_runs r
                        on r.organization_id = d.organization_id and r.run_id = d.run_id
                      join execution_attempts a
                        on a.organization_id = d.organization_id and a.attempt_id = d.attempt_id
                     where i.consumer = ?
                       and i.disposition = 'DELIVERED'
                       and r.lifecycle_state = 'QUEUED'
                       and r.current_attempt_id = d.attempt_id
                       and a.attempt_state = 'WAITING_FOR_CLAIM'
                       and r.queue_deadline_at > clock_timestamp())
                """,
                Boolean.class,
                consumer));
    }

    @Override
    public void recordPresence(String workerId) {
        jdbc.update(
                """
                insert into worker_presence (worker_id, first_seen_at, last_seen_at)
                values (?, clock_timestamp(), clock_timestamp())
                on conflict (worker_id) do update set last_seen_at = greatest(worker_presence.last_seen_at,
                                                                              excluded.last_seen_at)
                """,
                workerId);
    }
}
