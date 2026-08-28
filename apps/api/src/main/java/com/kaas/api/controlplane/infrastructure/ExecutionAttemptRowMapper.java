package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.WorkerAssignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** One mapper for the attempt aggregate and its assignment, shared by every adapter that reads it. */
final class ExecutionAttemptRowMapper implements RowMapper<ExecutionAttempt> {
    static final ExecutionAttemptRowMapper INSTANCE = new ExecutionAttemptRowMapper();

    static final String SELECT_COLUMNS =
            """
            select attempt_id, run_id, attempt_number, attempt_state, created_at, assignment_epoch,
                   assigned_worker_id, lease_started_at, lease_expires_at, last_heartbeat_at, fenced_at
            """;

    private ExecutionAttemptRowMapper() {}

    @Override
    public ExecutionAttempt mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        ExecutionAttemptState state = ExecutionAttemptState.valueOf(resultSet.getString("attempt_state"));
        WorkerAssignment assignment = state == ExecutionAttemptState.WAITING_FOR_CLAIM
                ? null
                : new WorkerAssignment(
                        resultSet.getInt("assignment_epoch"),
                        resultSet.getString("assigned_worker_id"),
                        instant(resultSet, "lease_started_at"),
                        instant(resultSet, "lease_expires_at"),
                        instant(resultSet, "last_heartbeat_at"),
                        instant(resultSet, "fenced_at"));
        return new ExecutionAttempt(
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getInt("attempt_number"),
                state,
                resultSet.getTimestamp("created_at").toInstant(),
                assignment);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
