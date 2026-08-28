package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.RunSchedulingRepository;
import com.kaas.api.controlplane.domain.CancellationStatus;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.QualityGateStatus;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.TestOutcome;
import com.kaas.api.controlplane.domain.TestRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunSchedulingRepository implements RunSchedulingRepository {
    private static final String ACTOR = "kaas.scheduler";
    private final JdbcTemplate jdbc;

    JdbcRunSchedulingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TestRun> lockCreated(UUID organizationId, UUID runId, long expectedRunVersion) {
        return jdbc.query(
                        """
                        select run_id, project_id, run_version, lifecycle_state, cancellation_status,
                               test_outcome, infrastructure_outcome, quality_gate_status, snapshot_sha256,
                               queued_at, queue_deadline_at, created_by, created_at, updated_at
                          from test_runs
                         where organization_id = ? and run_id = ? and lifecycle_state = 'CREATED'
                           and cancellation_status = 'NOT_REQUESTED' and run_version = ?
                         for update
                        """,
                        JdbcRunSchedulingRepository::run,
                        organizationId,
                        runId,
                        expectedRunVersion)
                .stream()
                .findFirst();
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void persistSchedule(
            UUID organizationId,
            TestRun previous,
            TestRun queued,
            ExecutionAttempt attempt,
            ExecutionDispatch dispatch,
            UUID lifecycleEventId,
            UUID outboxId,
            String dispatchPayload) {
        int changed = jdbc.update(
                """
                update test_runs
                   set run_version = ?, lifecycle_state = 'QUEUED', queued_at = ?, queue_deadline_at = ?,
                       current_attempt_id = ?, updated_by = ?, updated_at = ?
                 where organization_id = ? and project_id = ? and run_id = ?
                   and lifecycle_state = 'CREATED' and cancellation_status = 'NOT_REQUESTED' and run_version = ?
                """,
                queued.runVersion(), Timestamp.from(queued.queueStartedAt()), Timestamp.from(queued.queueDeadlineAt()),
                attempt.attemptId(), ACTOR, Timestamp.from(queued.updatedAt()), organizationId, queued.projectId(),
                queued.runId(), previous.runVersion());
        if (changed != 1) {
            throw new IllegalStateException("The locked CREATED run did not satisfy the scheduling compare-and-set.");
        }
        jdbc.update(
                """
                insert into execution_attempts
                    (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                     created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attempt.attemptId(), organizationId, queued.projectId(), queued.runId(), attempt.attemptNumber(),
                attempt.state().name(), ACTOR, Timestamp.from(attempt.createdAt()));
        jdbc.update(
                """
                insert into execution_dispatches
                    (dispatch_id, message_id, organization_id, project_id, run_id, run_version,
                     attempt_id, attempt_number, run_snapshot_id, run_snapshot_sha256,
                     schema_version, message_type, producer, occurred_at, queue_deadline_at,
                     payload, payload_sha256)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                dispatch.dispatchId(), dispatch.messageId(), organizationId, queued.projectId(), queued.runId(),
                queued.runVersion(), dispatch.attemptId(), dispatch.attemptNumber(), dispatch.runSnapshotId(),
                hex(dispatch.runSnapshotDigest()), dispatch.schemaVersion(), dispatch.messageType(), dispatch.producer(),
                Timestamp.from(dispatch.occurredAt()), Timestamp.from(dispatch.queueDeadlineAt()), dispatchPayload,
                hex(dispatch.payloadDigest()));
        jdbc.update(
                """
                insert into run_lifecycle_events
                    (event_id, organization_id, project_id, run_id, run_version, sequence,
                     event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                values (?, ?, ?, ?, ?, 1, 'RUN_STATE_CHANGED', 'CREATED', 'QUEUED', ?, ?, ?)
                """,
                lifecycleEventId, organizationId, queued.projectId(), queued.runId(), queued.runVersion(),
                attempt.attemptId(), ACTOR, Timestamp.from(queued.queueStartedAt()));
        jdbc.update(
                """
                insert into outbox_messages
                    (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                     message_type, schema_version, aggregate_type, aggregate_id, payload_sha256,
                     occurred_at, published_at, publish_attempts, last_failure_code)
                values (?, ?, ?, ?, ?, ?, 'EXECUTION_DISPATCH', '1.0', 'TEST_RUN', ?, ?, ?, null, 0, null)
                """,
                outboxId, dispatch.dispatchId(), dispatch.messageId(), organizationId, queued.projectId(),
                queued.runId(), queued.runId(), hex(dispatch.payloadDigest()), Timestamp.from(dispatch.occurredAt()));
    }

    private static TestRun run(ResultSet resultSet, int rowNumber) throws SQLException {
        String testOutcome = resultSet.getString("test_outcome");
        String infrastructureOutcome = resultSet.getString("infrastructure_outcome");
        Timestamp queuedAt = resultSet.getTimestamp("queued_at");
        Timestamp queueDeadlineAt = resultSet.getTimestamp("queue_deadline_at");
        return new TestRun(
                resultSet.getObject("run_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getLong("run_version"), RunLifecycle.valueOf(resultSet.getString("lifecycle_state")),
                CancellationStatus.valueOf(resultSet.getString("cancellation_status")),
                testOutcome == null ? null : TestOutcome.valueOf(testOutcome),
                infrastructureOutcome == null ? null : InfrastructureOutcome.valueOf(infrastructureOutcome),
                QualityGateStatus.valueOf(resultSet.getString("quality_gate_status")),
                digest(resultSet.getString("snapshot_sha256")),
                queuedAt == null ? null : queuedAt.toInstant(),
                queueDeadlineAt == null ? null : queueDeadlineAt.toInstant(),
                resultSet.getString("created_by"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static final String SHA256 = "sha256:";

    private static String hex(String digest) {
        if (digest == null || !digest.startsWith(SHA256)) {
            throw new IllegalArgumentException("Only sha256-prefixed digests are persisted.");
        }
        return digest.substring(SHA256.length());
    }

    private static String digest(String hex) {
        return SHA256 + hex;
    }
}
