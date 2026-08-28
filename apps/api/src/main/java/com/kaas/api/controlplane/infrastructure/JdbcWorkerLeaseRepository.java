package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.WorkerLeaseRepository;
import com.kaas.api.controlplane.application.WorkerLeaseService;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.TestRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWorkerLeaseRepository implements WorkerLeaseRepository {
    private final JdbcTemplate jdbc;
    private final long recoveryWindowSeconds;

    JdbcWorkerLeaseRepository(
            JdbcTemplate jdbc, @Value("${kaas.claim.recovery-window}") java.time.Duration recoveryWindow) {
        this.jdbc = jdbc;
        this.recoveryWindowSeconds = recoveryWindow.toSeconds();
    }

    @Override
    public Optional<OwnedRun> lockOwnedByRun(UUID runId) {
        var run = jdbc.query(
                        TestRunRowMapper.SELECT_COLUMNS
                                + """
                                  , organization_id
                                  from test_runs
                                 where run_id = ? and lifecycle_state in ('CLAIMED', 'STOPPING')
                                 for update
                                """,
                        (resultSet, rowNumber) -> new Object[] {
                            TestRunRowMapper.INSTANCE.mapRow(resultSet, rowNumber),
                            resultSet.getObject("organization_id", UUID.class)
                        },
                        runId)
                .stream()
                .findFirst();
        if (run.isEmpty()) {
            return Optional.empty();
        }
        TestRun testRun = (TestRun) run.orElseThrow()[0];
        UUID organizationId = (UUID) run.orElseThrow()[1];
        return jdbc
                .query(
                        ExecutionAttemptRowMapper.SELECT_COLUMNS
                                + """
                                  from execution_attempts
                                 where organization_id = ? and project_id = ? and run_id = ?
                                 for update
                                """,
                        ExecutionAttemptRowMapper.INSTANCE,
                        organizationId,
                        testRun.projectId(),
                        runId)
                .stream()
                .findFirst()
                .map(attempt -> new OwnedRun(organizationId, testRun, attempt));
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    /**
     * Renews the lease under a compare-and-set on the whole assignment identity — epoch, worker, and the exact
     * heartbeat it is replacing. A renewal that cannot name the assignment it is renewing is not a renewal.
     */
    @Override
    public boolean renewLease(UUID organizationId, ExecutionAttempt attempt) {
        var assignment = attempt.assignment();
        return jdbc.update(
                        """
                        update execution_attempts
                           set last_heartbeat_at = ?, lease_expires_at = ?
                         where organization_id = ? and run_id = ? and attempt_id = ?
                           and attempt_state = 'CLAIMED' and assignment_epoch = ?
                           and assigned_worker_id = ? and last_heartbeat_at < ? and lease_expires_at > ?
                        """,
                        Timestamp.from(assignment.lastHeartbeatAt()),
                        Timestamp.from(assignment.leaseExpiresAt()),
                        organizationId,
                        attempt.runId(),
                        attempt.attemptId(),
                        assignment.epoch(),
                        assignment.workerId(),
                        Timestamp.from(assignment.lastHeartbeatAt()),
                        Timestamp.from(assignment.lastHeartbeatAt()))
                == 1;
    }

    @Override
    public void persistStop(
            UUID organizationId,
            TestRun previous,
            TestRun stopping,
            ExecutionAttempt fenced,
            UUID lifecycleEventId,
            String actor) {
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'STOPPING', stop_reason = ?,
                       cancellation_status = ?, cancellation_requested_at = ?, updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = 'CLAIMED' and run_version = ?
                """,
                stopping.runVersion(),
                stopping.stopReason().name(),
                stopping.cancellationStatus().name(),
                timestamp(stopping.cancellationRequestedAt()),
                actor,
                Timestamp.from(stopping.updatedAt()),
                organizationId,
                stopping.projectId(),
                stopping.runId(),
                previous.runVersion());
        if (changed != 1) {
            throw new IllegalStateException("The locked CLAIMED run did not satisfy the stop compare-and-set.");
        }
        int fencedRows = jdbc.update(
                """
                update execution_attempts
                   set attempt_state = 'FENCED', fenced_at = ?
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and attempt_state = 'CLAIMED' and assignment_epoch = ?
                """,
                Timestamp.from(fenced.assignment().fencedAt()),
                organizationId,
                stopping.projectId(),
                stopping.runId(),
                fenced.attemptId(),
                fenced.assignment().epoch());
        if (fencedRows != 1) {
            throw new IllegalStateException("The assignment did not satisfy the fencing compare-and-set.");
        }
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                values (?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', 'CLAIMED', 'STOPPING', ?, ?, ?)
                """,
                lifecycleEventId,
                organizationId,
                stopping.projectId(),
                stopping.runId(),
                stopping.runVersion(),
                stopping.runVersion() - 1,
                fenced.attemptId(),
                actor,
                Timestamp.from(stopping.updatedAt()));
    }

    @Override
    public void persistSettlement(
            UUID organizationId, TestRun previous, TestRun settled, UUID lifecycleEventId) {
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'COMPLETED', test_outcome = ?,
                       infrastructure_outcome = ?, termination_reason = ?, termination_phase = ?,
                       cancellation_status = ?, cancellation_acknowledged_at = ?, completed_at = ?,
                       updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = 'STOPPING' and run_version = ?
                """,
                settled.runVersion(),
                settled.testOutcome().name(),
                settled.infrastructureOutcome().name(),
                settled.terminationReason().name(),
                settled.terminationPhase().name(),
                settled.cancellationStatus().name(),
                timestamp(settled.cancellationAcknowledgedAt()),
                Timestamp.from(settled.completedAt()),
                WorkerLeaseService.RECONCILER_ACTOR,
                Timestamp.from(settled.updatedAt()),
                organizationId,
                settled.projectId(),
                settled.runId(),
                previous.runVersion());
        if (changed != 1) {
            throw new IllegalStateException("The locked STOPPING run did not satisfy the settle compare-and-set.");
        }
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                select ?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', 'STOPPING', 'COMPLETED', r.current_attempt_id, ?, ?
                  from test_runs r
                 where r.organization_id = ? and r.project_id = ? and r.run_id = ?
                """,
                lifecycleEventId,
                organizationId,
                settled.projectId(),
                settled.runId(),
                settled.runVersion(),
                settled.runVersion() - 1,
                WorkerLeaseService.RECONCILER_ACTOR,
                Timestamp.from(settled.completedAt()),
                organizationId,
                settled.projectId(),
                settled.runId());
    }

    /**
     * Selects assignments whose lease expired and whose recovery window has also passed.
     *
     * <p>The window is why fencing is not immediate: a worker that misses one heartbeat because of a garbage
     * collection pause or a brief network partition deserves a chance to come back before its work is taken away.
     */
    @Override
    public List<UUID> findExpiredLeases(int batchSize) {
        return jdbc.queryForList(
                """
                select a.run_id
                  from execution_attempts a
                  join test_runs r on r.organization_id = a.organization_id
                   and r.project_id = a.project_id and r.run_id = a.run_id
                 where a.attempt_state = 'CLAIMED'
                   -- Written as `column <= now() - constant`, never `column + constant <= now()`. PostgreSQL
                   -- will not rewrite the second into the first, so the partial index degrades into a filter
                   -- over every live claim and the ordered scan cannot stop at the first non-match.
                   and a.lease_expires_at <= now() - (? * interval '1 second')
                   and r.lifecycle_state = 'CLAIMED'
                 order by a.lease_expires_at, a.attempt_id
                 limit ?
                """,
                UUID.class,
                recoveryWindowSeconds,
                batchSize);
    }

    @Override
    public List<UUID> findStopping(int batchSize) {
        return jdbc.queryForList(
                """
                select run_id from test_runs
                 where lifecycle_state = 'STOPPING'
                 order by updated_at, run_id
                 limit ?
                """,
                UUID.class,
                batchSize);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
