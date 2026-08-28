package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.RunIntentRepository;
import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SecretBinding;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.controlplane.domain.SnapshotRevision;
import com.kaas.api.controlplane.domain.TestRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunIntentRepository implements RunIntentRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    JdbcRunIntentRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Override
    public List<SnapshotFeature> findFeatureRevisions(
            UUID organizationId, UUID projectId, Set<UUID> revisionIds) {
        if (revisionIds.isEmpty()) {
            return List.of();
        }
        return namedJdbc.query(
                """
                select r.feature_id, r.revision_id, r.revision_number, f.logical_path, r.source_sha256
                  from feature_revisions r
                  join features f on f.organization_id = r.organization_id
                    and f.project_id = r.project_id and f.feature_id = r.feature_id
                 where r.organization_id = :organizationId and r.project_id = :projectId
                   and r.revision_id in (:revisionIds)
                 order by f.logical_path collate "C", r.feature_id, r.revision_id
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("projectId", projectId)
                        .addValue("revisionIds", revisionIds),
                (resultSet, rowNumber) -> new SnapshotFeature(
                        resultSet.getObject("feature_id", UUID.class),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getLong("revision_number"),
                        resultSet.getString("logical_path"),
                        digest(resultSet.getString("source_sha256"))));
    }

    @Override
    public void insert(UUID organizationId, TestRun run, RunSnapshot snapshot) {
        jdbc.update(
                """
                insert into test_runs
                    (run_id, organization_id, project_id, run_version, lifecycle_state,
                     cancellation_status, test_outcome, infrastructure_outcome, quality_gate_status,
                     snapshot_sha256, created_by, created_at, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.runId(), organizationId, run.projectId(), run.runVersion(), run.lifecycleState().name(),
                run.cancellationStatus().name(), enumName(run.testOutcome()), enumName(run.infrastructureOutcome()),
                run.qualityGateStatus().name(), hex(run.snapshotDigest()), run.createdBy(),
                Timestamp.from(run.createdAt()), run.createdBy(), Timestamp.from(run.updatedAt()));
        jdbc.update(
                """
                insert into run_snapshots
                    (run_id, organization_id, project_id, snapshot_version,
                     run_profile_id, run_profile_revision_id, run_profile_revision_number, run_profile_sha256,
                     environment_id, environment_revision_id, environment_revision_number, environment_sha256,
                     parallelism, retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                     max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256, sealed)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """,
                snapshot.runId(), organizationId, snapshot.projectId(), snapshot.snapshotVersion(),
                snapshot.runProfile().resourceId(), snapshot.runProfile().revisionId(),
                snapshot.runProfile().revisionNumber(), hex(snapshot.runProfile().contentDigest()),
                snapshot.environment().resourceId(), snapshot.environment().revisionId(),
                snapshot.environment().revisionNumber(), hex(snapshot.environment().contentDigest()),
                snapshot.parallelism(), snapshot.scenarioRetry().maxAttempts(),
                snapshot.scenarioRetry().delayMilliseconds(), snapshot.executionTimeoutSeconds(),
                snapshot.artifactPolicy().maxArtifactBytes(), snapshot.artifactPolicy().maxTotalBytes(),
                snapshot.engine().engine(), snapshot.engine().version(), hex(snapshot.snapshotDigest()));
        int ordinal = 0;
        for (SnapshotFeature feature : snapshot.features()) {
            jdbc.update(
                    """
                    insert into run_snapshot_features
                        (organization_id, project_id, run_id, ordinal, feature_id, feature_revision_id,
                         revision_number, logical_path, source_sha256)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    organizationId, snapshot.projectId(), snapshot.runId(), ordinal++, feature.featureId(),
                    feature.revisionId(), feature.revisionNumber(), feature.logicalPath(), hex(feature.sourceDigest()));
        }
        for (ConfigurationVariable value : snapshot.effectiveConfiguration()) {
            insertConfiguration(organizationId, snapshot, value);
        }
        for (SecretBinding binding : snapshot.secretBindings()) {
            jdbc.update(
                    """
                    insert into run_snapshot_configuration_entries
                        (organization_id, project_id, run_id, config_key, value_kind, secret_reference_id)
                    values (?, ?, ?, ?, 'SECRET_REFERENCE', ?)
                    """,
                    organizationId, snapshot.projectId(), snapshot.runId(), binding.key(), binding.secretReferenceId());
        }
        for (String tag : snapshot.selection().tags()) {
            jdbc.update(
                    """
                    insert into run_snapshot_tags (organization_id, project_id, run_id, tag)
                    values (?, ?, ?, ?)
                    """,
                    organizationId, snapshot.projectId(), snapshot.runId(), tag);
        }
        for (ArtifactType type : snapshot.artifactPolicy().types()) {
            jdbc.update(
                    """
                    insert into run_snapshot_artifact_types
                        (organization_id, project_id, run_id, artifact_type)
                    values (?, ?, ?, ?)
                    """,
                    organizationId, snapshot.projectId(), snapshot.runId(), type.name());
        }
        jdbc.update(
                "update run_snapshots set sealed = true where organization_id = ? and project_id = ? and run_id = ?",
                organizationId, snapshot.projectId(), snapshot.runId());
    }

    @Override
    public Optional<TestRun> findRun(UUID organizationId, UUID runId) {
        return jdbc.query(
                        TestRunRowMapper.SELECT_COLUMNS
                                + "  from test_runs where organization_id = ? and run_id = ?",
                        TestRunRowMapper.INSTANCE,
                        organizationId,
                        runId)
                .stream().findFirst();
    }

    @Override
    public PageResult<TestRun> listRuns(UUID organizationId, UUID projectId, int page, int size) {
        long total = jdbc.queryForObject(
                "select count(*) from test_runs where organization_id = ? and project_id = ?",
                Long.class, organizationId, projectId);
        List<TestRun> runs = jdbc.query(
                TestRunRowMapper.SELECT_COLUMNS
                        + """
                          from test_runs where organization_id = ? and project_id = ?
                         order by created_at desc, run_id desc limit ? offset ?
                        """,
                TestRunRowMapper.INSTANCE,
                organizationId, projectId, size, (long) page * size);
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new PageResult<>(runs, page, size, total, totalPages);
    }

    @Override
    public Optional<RunSnapshot> findSnapshot(UUID organizationId, UUID runId) {
        return jdbc.query(
                        """
                        select run_id, project_id, snapshot_version,
                               run_profile_id, run_profile_revision_id, run_profile_revision_number, run_profile_sha256,
                               environment_id, environment_revision_id, environment_revision_number, environment_sha256,
                               parallelism, retry_max_attempts, retry_delay_milliseconds, execution_timeout_seconds,
                               max_artifact_bytes, max_total_bytes, engine, engine_version, content_sha256
                          from run_snapshots where organization_id = ? and run_id = ? and sealed = true
                        """,
                        (resultSet, rowNumber) -> snapshot(organizationId, resultSet),
                        organizationId,
                        runId)
                .stream().findFirst();
    }

    private RunSnapshot snapshot(UUID organizationId, ResultSet resultSet) throws SQLException {
        UUID runId = resultSet.getObject("run_id", UUID.class);
        UUID projectId = resultSet.getObject("project_id", UUID.class);
        List<SnapshotFeature> features = jdbc.query(
                """
                select feature_id, feature_revision_id, revision_number, logical_path, source_sha256
                  from run_snapshot_features where organization_id = ? and project_id = ? and run_id = ?
                 order by ordinal
                """,
                (row, number) -> new SnapshotFeature(
                        row.getObject("feature_id", UUID.class), row.getObject("feature_revision_id", UUID.class),
                        row.getLong("revision_number"), row.getString("logical_path"), digest(row.getString("source_sha256"))),
                organizationId, projectId, runId);
        List<ConfigurationVariable> configuration = new ArrayList<>();
        List<SecretBinding> secrets = new ArrayList<>();
        jdbc.query(
                """
                select config_key, value_kind, string_value, integer_value, boolean_value, secret_reference_id
                  from run_snapshot_configuration_entries
                 where organization_id = ? and project_id = ? and run_id = ? order by config_key collate "C"
                """,
                rows -> {
                    String kind = rows.getString("value_kind");
                    if (kind.equals("SECRET_REFERENCE")) {
                        secrets.add(new SecretBinding(rows.getString("config_key"), rows.getObject("secret_reference_id", UUID.class)));
                    } else {
                        ConfigurationValueType type = ConfigurationValueType.valueOf(kind);
                        Object value = switch (type) {
                            case STRING -> rows.getString("string_value");
                            case INTEGER -> rows.getLong("integer_value");
                            case BOOLEAN -> rows.getBoolean("boolean_value");
                        };
                        configuration.add(new ConfigurationVariable(rows.getString("config_key"), type, value));
                    }
                },
                organizationId, projectId, runId);
        List<String> tags = jdbc.queryForList(
                "select tag from run_snapshot_tags where organization_id = ? and project_id = ? and run_id = ? order by tag collate \"C\"",
                String.class, organizationId, projectId, runId);
        List<ArtifactType> types = jdbc.queryForList(
                        "select artifact_type from run_snapshot_artifact_types where organization_id = ? and project_id = ? and run_id = ? order by artifact_type",
                        String.class, organizationId, projectId, runId)
                .stream().map(ArtifactType::valueOf).toList();
        return new RunSnapshot(
                runId, projectId, resultSet.getInt("snapshot_version"), features,
                new SnapshotRevision(
                        resultSet.getObject("environment_id", UUID.class),
                        resultSet.getObject("environment_revision_id", UUID.class),
                        resultSet.getLong("environment_revision_number"), digest(resultSet.getString("environment_sha256"))),
                new SnapshotRevision(
                        resultSet.getObject("run_profile_id", UUID.class),
                        resultSet.getObject("run_profile_revision_id", UUID.class),
                        resultSet.getLong("run_profile_revision_number"), digest(resultSet.getString("run_profile_sha256"))),
                configuration, secrets, new RunSelection(tags), resultSet.getInt("parallelism"),
                new ScenarioRetry(resultSet.getInt("retry_max_attempts"), resultSet.getInt("retry_delay_milliseconds")),
                resultSet.getInt("execution_timeout_seconds"),
                new ArtifactPolicy(types, resultSet.getLong("max_artifact_bytes"), resultSet.getLong("max_total_bytes")),
                new EngineDescriptor(resultSet.getString("engine"), resultSet.getString("engine_version")),
                digest(resultSet.getString("content_sha256")));
    }

    private void insertConfiguration(UUID organizationId, RunSnapshot snapshot, ConfigurationVariable value) {
        jdbc.update(
                """
                insert into run_snapshot_configuration_entries
                    (organization_id, project_id, run_id, config_key, value_kind,
                     string_value, integer_value, boolean_value)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId, snapshot.projectId(), snapshot.runId(), value.key(), value.type().name(),
                value.type() == ConfigurationValueType.STRING ? value.value() : null,
                value.type() == ConfigurationValueType.INTEGER ? value.value() : null,
                value.type() == ConfigurationValueType.BOOLEAN ? value.value() : null);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String hex(String digest) {
        return digest.substring("sha256:".length());
    }

    private static String digest(String hex) {
        return "sha256:" + hex;
    }
}
