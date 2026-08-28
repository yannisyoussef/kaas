package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.SchedulingControlRepository;
import com.kaas.api.controlplane.domain.SchedulingAttempt;
import com.kaas.api.controlplane.domain.SchedulingOutcome;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSchedulingControlRepository implements SchedulingControlRepository {
    private final JdbcTemplate jdbc;

    JdbcSchedulingControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One statement does everything: stamps both timestamps from the database clock, derives the next delay from
     * the count this attempt produces, and decides quarantine.
     *
     * <p>Two things made that necessary rather than tidy. Reading the clock or the current count first meant the
     * failure path needed a database round trip *before* it could record a failure — so a partial outage, such as
     * an exhausted connection pool, would fail to write any backoff and leave the run immediately eligible again.
     * That is the hot loop this table exists to prevent. And computing the delay from a separately read count let
     * two replicas derive a shorter delay and a later quarantine than the stored count warranted.
     */
    @Override
    public SchedulingOutcome recordAttempt(SchedulingAttempt attempt) {
        return jdbc.queryForObject(
                """
                insert into run_scheduling_control
                    (run_id, organization_id, project_id, failure_count, next_attempt_at, last_attempt_at,
                     last_failure_code, quarantined_at)
                values (?, ?, ?, ?,
                        clock_timestamp()
                            + (least(? * power(2, least(greatest(?, 1) - 1, 30)), ?) * ?) * interval '1 second',
                        clock_timestamp(), ?,
                        case when ? then clock_timestamp() end)
                on conflict (run_id) do update
                   set failure_count = run_scheduling_control.failure_count + ?,
                       -- A deferral may postpone eligibility but must never advance it: a run that has earned a
                       -- long transient backoff must not be demoted to the short capacity delay.
                       next_attempt_at = greatest(
                           case when ? then run_scheduling_control.next_attempt_at
                                else timestamptz '-infinity' end,
                           clock_timestamp()
                               + (least(? * power(2, least(greatest(
                                     run_scheduling_control.failure_count + ?, 1) - 1, 30)), ?) * ?)
                                 * interval '1 second'),
                       last_attempt_at = clock_timestamp(),
                       last_failure_code = excluded.last_failure_code,
                       quarantined_at = case
                           when ? or run_scheduling_control.failure_count + ? >= ? then clock_timestamp()
                           else run_scheduling_control.quarantined_at end
                returning failure_count, quarantined_at is not null as quarantined
                """,
                (resultSet, rowNumber) -> new SchedulingOutcome(
                        resultSet.getInt("failure_count"), resultSet.getBoolean("quarantined")),
                // insert
                attempt.runId(),
                attempt.organizationId(),
                attempt.projectId(),
                attempt.increment(),
                attempt.baseDelaySeconds(),
                attempt.increment(),
                attempt.maxDelaySeconds(),
                attempt.jitterMultiplier(),
                attempt.failureCode(),
                attempt.permanent(),
                // conflict
                attempt.increment(),
                attempt.preserveExistingDelay(),
                attempt.baseDelaySeconds(),
                attempt.increment(),
                attempt.maxDelaySeconds(),
                attempt.jitterMultiplier(),
                attempt.permanent(),
                attempt.increment(),
                attempt.maxFailures());
    }

    @Override
    public boolean clear(UUID runId) {
        return jdbc.update("delete from run_scheduling_control where run_id = ?", runId) == 1;
    }

    @Override
    public long countQuarantined() {
        Long quarantined = jdbc.queryForObject(
                "select count(*) from run_scheduling_control where quarantined_at is not null", Long.class);
        return quarantined == null ? 0L : quarantined;
    }
}
