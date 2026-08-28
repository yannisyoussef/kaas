package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.RunClaimRepository;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.TestRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunClaimRepository implements RunClaimRepository {
    private final JdbcTemplate jdbc;

    JdbcRunClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the run first and the attempt second, always in that order, because every other writer that touches
     * both — termination, the lease reconciler — does the same. A lock order that varies by caller is how two
     * correct transactions deadlock.
     */
    @Override
    public Optional<ClaimableRun> lockClaimable(UUID organizationId, UUID runId) {
        Optional<TestRun> run = jdbc.query(
                        TestRunRowMapper.SELECT_COLUMNS
                                + """
                                  from test_runs
                                 where organization_id = ? and run_id = ?
                                 for update
                                """,
                        TestRunRowMapper.INSTANCE,
                        organizationId,
                        runId)
                .stream()
                .findFirst();
        if (run.isEmpty()) {
            return Optional.empty();
        }
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
                        run.orElseThrow().projectId(),
                        runId)
                .stream()
                .findFirst()
                .map(attempt -> new ClaimableRun(run.orElseThrow(), attempt));
    }

    @Override
    public Optional<PersistedDispatch> findDispatch(UUID organizationId, UUID messageId) {
        return jdbc
                .query(
                        """
                        select organization_id, project_id, run_id, run_version, attempt_id, run_snapshot_id,
                               run_snapshot_sha256, payload_sha256
                          from execution_dispatches
                         where organization_id = ? and message_id = ?
                        """,
                        (resultSet, rowNumber) -> new PersistedDispatch(
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getObject("project_id", UUID.class),
                                resultSet.getObject("run_id", UUID.class),
                                resultSet.getLong("run_version"),
                                resultSet.getObject("attempt_id", UUID.class),
                                resultSet.getObject("run_snapshot_id", UUID.class),
                                "sha256:" + resultSet.getString("run_snapshot_sha256"),
                                "sha256:" + resultSet.getString("payload_sha256")),
                        organizationId,
                        messageId)
                .stream()
                .findFirst();
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void persistClaim(
            UUID organizationId,
            TestRun previous,
            TestRun claimed,
            ExecutionAttempt attempt,
            UUID lifecycleEventId) {
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'CLAIMED', updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = 'QUEUED' and cancellation_status = 'NOT_REQUESTED'
                   and run_version = ? and current_attempt_id = ?
                """,
                claimed.runVersion(),
                RunClaimService.CONSUMER_ACTOR,
                Timestamp.from(claimed.updatedAt()),
                organizationId,
                claimed.projectId(),
                claimed.runId(),
                previous.runVersion(),
                attempt.attemptId());
        if (changed != 1) {
            throw new IllegalStateException("The locked QUEUED run did not satisfy the claim compare-and-set.");
        }
        var assignment = attempt.assignment();
        int assigned = jdbc.update(
                """
                update execution_attempts
                   set attempt_state = 'CLAIMED', assignment_epoch = ?, assigned_worker_id = ?,
                       lease_started_at = ?, lease_expires_at = ?, last_heartbeat_at = ?
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and attempt_state = 'WAITING_FOR_CLAIM' and assignment_epoch is null
                """,
                assignment.epoch(),
                assignment.workerId(),
                Timestamp.from(assignment.leaseStartedAt()),
                Timestamp.from(assignment.leaseExpiresAt()),
                Timestamp.from(assignment.lastHeartbeatAt()),
                organizationId,
                claimed.projectId(),
                claimed.runId(),
                attempt.attemptId());
        if (assigned != 1) {
            throw new IllegalStateException("The attempt did not satisfy the assignment compare-and-set.");
        }
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                values (?, ?, ?, ?, ?, ?, 'RUN_STATE_CHANGED', 'QUEUED', 'CLAIMED', ?, ?, ?)
                """,
                lifecycleEventId,
                organizationId,
                claimed.projectId(),
                claimed.runId(),
                claimed.runVersion(),
                claimed.runVersion() - 1,
                attempt.attemptId(),
                RunClaimService.CONSUMER_ACTOR,
                Timestamp.from(claimed.updatedAt()));
    }
}
