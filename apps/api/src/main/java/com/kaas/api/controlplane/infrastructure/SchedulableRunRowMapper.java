package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.domain.SchedulableRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * The trusted identity a background pass needs to attempt one compare-and-set transition on a run.
 *
 * <p>Shared by the scheduler's and the reaper's selection queries. It is four columns, which is exactly why it
 * was duplicated in the first place — and exactly why the duplication is not worth keeping: the two selections
 * project the same tuple for the same purpose, so they should not be able to drift.
 */
final class SchedulableRunRowMapper implements RowMapper<SchedulableRun> {
    static final SchedulableRunRowMapper INSTANCE = new SchedulableRunRowMapper();

    private SchedulableRunRowMapper() {}

    @Override
    public SchedulableRun mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SchedulableRun(
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getLong("run_version"));
    }
}
