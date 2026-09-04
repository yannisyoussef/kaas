package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.domain.CancellationStatus;
import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.QualityGateStatus;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import com.kaas.api.controlplane.domain.TerminationPhase;
import com.kaas.api.controlplane.domain.TerminationReason;
import com.kaas.api.controlplane.domain.TestOutcome;
import com.kaas.api.controlplane.domain.TestRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * One mapper for the run aggregate, shared by every adapter that reads it.
 *
 * <p>It is shared rather than repeated because the column list and the mapper have to move together. They were
 * duplicated across two adapters while the run had no terminal state, and adding terminal columns to one
 * projection but not the other would have produced runs that silently claimed to be unfinished.
 */
// Public so the execution package can read these rows through the same mapper the control plane uses.
// The alternative is a second copy of the column list, and two column lists that must agree are two
// column lists that eventually do not.
public final class TestRunRowMapper implements RowMapper<TestRun> {
    public static final TestRunRowMapper INSTANCE = new TestRunRowMapper();

    public static final String SELECT_COLUMNS =
            """
            select run_id, project_id, run_version, lifecycle_state, cancellation_status, test_outcome,
                   infrastructure_outcome, quality_gate_status, termination_reason, termination_phase,
                   stop_reason, snapshot_sha256, queued_at, queue_deadline_at, cancellation_requested_at,
                   cancellation_acknowledged_at, completed_at, created_by, created_at, updated_at,
                   phase_deadline_at, execution_started_at
            """;

    private static final String SHA256 = "sha256:";

    private TestRunRowMapper() {}

    @Override
    public TestRun mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String testOutcome = resultSet.getString("test_outcome");
        String infrastructureOutcome = resultSet.getString("infrastructure_outcome");
        String terminationReason = resultSet.getString("termination_reason");
        String terminationPhase = resultSet.getString("termination_phase");
        String stopReason = resultSet.getString("stop_reason");
        return new TestRun(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getLong("run_version"),
                RunLifecycle.valueOf(resultSet.getString("lifecycle_state")),
                CancellationStatus.valueOf(resultSet.getString("cancellation_status")),
                testOutcome == null ? null : TestOutcome.valueOf(testOutcome),
                infrastructureOutcome == null ? null : InfrastructureOutcome.valueOf(infrastructureOutcome),
                QualityGateStatus.valueOf(resultSet.getString("quality_gate_status")),
                terminationReason == null ? null : TerminationReason.valueOf(terminationReason),
                terminationPhase == null ? null : TerminationPhase.valueOf(terminationPhase),
                stopReason == null ? null : StopReason.valueOf(stopReason),
                SHA256 + resultSet.getString("snapshot_sha256"),
                instant(resultSet, "queued_at"),
                instant(resultSet, "queue_deadline_at"),
                instant(resultSet, "cancellation_requested_at"),
                instant(resultSet, "cancellation_acknowledged_at"),
                instant(resultSet, "completed_at"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                instant(resultSet, "phase_deadline_at"),
                instant(resultSet, "execution_started_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
