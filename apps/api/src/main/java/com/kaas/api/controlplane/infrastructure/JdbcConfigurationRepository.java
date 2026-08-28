package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.ConfigurationRepository;
import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationPolicy.EnvironmentContent;
import com.kaas.api.controlplane.domain.ConfigurationPolicy.RunProfileContent;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.Environment;
import com.kaas.api.controlplane.domain.EnvironmentRevision;
import com.kaas.api.controlplane.domain.EnvironmentRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunProfile;
import com.kaas.api.controlplane.domain.RunProfileRevision;
import com.kaas.api.controlplane.domain.RunProfileRevisionSummary;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SecretBinding;
import com.kaas.api.controlplane.domain.SecretReference;
import com.kaas.api.shared.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConfigurationRepository implements ConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    JdbcConfigurationRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Override
    public boolean projectExists(UUID organizationId, UUID projectId) {
        return jdbc.queryForObject(
                        "select count(*) from projects where organization_id = ? and project_id = ?",
                        Long.class,
                        organizationId,
                        projectId)
                == 1;
    }

    @Override
    public SecretReference insertSecretReference(
            UUID organizationId,
            UUID projectId,
            UUID referenceId,
            String name,
            String principalId,
            Instant now) {
        try {
            jdbc.update(
                    """
                    insert into secret_references
                        (secret_reference_id, organization_id, project_id, name, created_by, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    referenceId,
                    organizationId,
                    projectId,
                    name,
                    principalId,
                    Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict(
                    "SECRET_REFERENCE_NAME_CONFLICT", "A secret reference with that name already exists.");
        }
        return new SecretReference(referenceId, projectId, name, principalId, now);
    }

    @Override
    public Optional<SecretReference> findSecretReference(
            UUID organizationId, UUID projectId, UUID referenceId) {
        return jdbc.query(
                        """
                        select secret_reference_id, project_id, name, created_by, created_at
                          from secret_references
                         where organization_id = ? and project_id = ? and secret_reference_id = ?
                        """,
                        JdbcConfigurationRepository::secretReference,
                        organizationId,
                        projectId,
                        referenceId)
                .stream()
                .findFirst();
    }

    @Override
    public PageResult<SecretReference> listSecretReferences(
            UUID organizationId, UUID projectId, int page, int size) {
        long total = count(
                "select count(*) from secret_references where organization_id = ? and project_id = ?",
                organizationId,
                projectId);
        List<SecretReference> items = jdbc.query(
                """
                select secret_reference_id, project_id, name, created_by, created_at
                  from secret_references
                 where organization_id = ? and project_id = ?
                 order by created_at desc, secret_reference_id desc
                 limit ? offset ?
                """,
                JdbcConfigurationRepository::secretReference,
                organizationId,
                projectId,
                size,
                (long) page * size);
        return page(items, page, size, total);
    }

    @Override
    public boolean allSecretReferencesExist(
            UUID organizationId, UUID projectId, Set<UUID> referenceIds) {
        if (referenceIds.isEmpty()) {
            return true;
        }
        long count = namedJdbc.queryForObject(
                """
                select count(*)
                  from secret_references
                 where organization_id = :organizationId
                   and project_id = :projectId
                   and secret_reference_id in (:referenceIds)
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("projectId", projectId)
                        .addValue("referenceIds", referenceIds),
                Long.class);
        return count == referenceIds.size();
    }

    @Override
    public EnvironmentRevision insertEnvironmentWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            String name,
            EnvironmentContent content,
            String principalId,
            Instant now) {
        try {
            jdbc.update(
                    """
                    insert into environments
                        (environment_id, organization_id, project_id, name, next_revision_number,
                         version, created_by, created_at)
                    values (?, ?, ?, ?, 2, 0, ?, ?)
                    """,
                    environmentId,
                    organizationId,
                    projectId,
                    name,
                    principalId,
                    Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("ENVIRONMENT_NAME_CONFLICT", "An environment with that name already exists.");
        }
        insertEnvironmentRevision(
                organizationId, projectId, environmentId, revisionId, 1, content, principalId, now);
        return findEnvironmentRevision(organizationId, projectId, environmentId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Override
    public Optional<Environment> findEnvironment(
            UUID organizationId, UUID projectId, UUID environmentId) {
        return jdbc.query(
                        """
                        select environment_id, project_id, name, created_by, created_at
                          from environments
                         where organization_id = ? and project_id = ? and environment_id = ?
                        """,
                        JdbcConfigurationRepository::environment,
                        organizationId,
                        projectId,
                        environmentId)
                .stream()
                .findFirst();
    }

    @Override
    public PageResult<Environment> listEnvironments(
            UUID organizationId, UUID projectId, int page, int size) {
        long total = count(
                "select count(*) from environments where organization_id = ? and project_id = ?",
                organizationId,
                projectId);
        List<Environment> items = jdbc.query(
                """
                select environment_id, project_id, name, created_by, created_at
                  from environments
                 where organization_id = ? and project_id = ?
                 order by created_at desc, environment_id desc
                 limit ? offset ?
                """,
                JdbcConfigurationRepository::environment,
                organizationId,
                projectId,
                size,
                (long) page * size);
        return page(items, page, size, total);
    }

    @Override
    public EnvironmentRevision appendEnvironmentRevision(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            EnvironmentContent content,
            String principalId,
            Instant now) {
        long revisionNumber = allocateEnvironmentRevision(organizationId, projectId, environmentId);
        insertEnvironmentRevision(
                organizationId,
                projectId,
                environmentId,
                revisionId,
                revisionNumber,
                content,
                principalId,
                now);
        return findEnvironmentRevision(organizationId, projectId, environmentId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Override
    public Optional<EnvironmentRevision> findEnvironmentRevision(
            UUID organizationId, UUID projectId, UUID environmentId, UUID revisionId) {
        return environmentRevisionHeader(
                        """
                        select revision_id, environment_id, project_id, revision_number,
                               content_sha256, created_by, created_at
                          from environment_revisions
                         where organization_id = ? and project_id = ?
                           and environment_id = ? and revision_id = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        environmentId,
                        revisionId)
                .map(header -> environmentRevision(organizationId, header));
    }

    @Override
    public Optional<EnvironmentRevision> findEnvironmentRevisionById(
            UUID organizationId, UUID projectId, UUID revisionId) {
        return environmentRevisionHeader(
                        """
                        select revision_id, environment_id, project_id, revision_number,
                               content_sha256, created_by, created_at
                          from environment_revisions
                         where organization_id = ? and project_id = ?
                           and revision_id = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        revisionId)
                .map(header -> environmentRevision(organizationId, header));
    }

    @Override
    public Optional<EnvironmentRevision> findEnvironmentRevisionByNumber(
            UUID organizationId, UUID projectId, UUID environmentId, long revisionNumber) {
        return environmentRevisionHeader(
                        """
                        select revision_id, environment_id, project_id, revision_number,
                               content_sha256, created_by, created_at
                          from environment_revisions
                         where organization_id = ? and project_id = ?
                           and environment_id = ? and revision_number = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        environmentId,
                        revisionNumber)
                .map(header -> environmentRevision(organizationId, header));
    }

    @Override
    public PageResult<EnvironmentRevisionSummary> listEnvironmentRevisions(
            UUID organizationId, UUID projectId, UUID environmentId, int page, int size) {
        long total = count(
                """
                select count(*) from environment_revisions
                 where organization_id = ? and project_id = ? and environment_id = ? and sealed = true
                """,
                organizationId,
                projectId,
                environmentId);
        List<EnvironmentRevisionSummary> items = jdbc.query(
                """
                select revision_id, environment_id, revision_number, content_sha256, created_by, created_at
                  from environment_revisions
                 where organization_id = ? and project_id = ? and environment_id = ? and sealed = true
                 order by revision_number desc
                 limit ? offset ?
                """,
                (resultSet, rowNumber) -> new EnvironmentRevisionSummary(
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getObject("environment_id", UUID.class),
                        resultSet.getLong("revision_number"),
                        digest(resultSet.getString("content_sha256")),
                        resultSet.getString("created_by"),
                        resultSet.getTimestamp("created_at").toInstant()),
                organizationId,
                projectId,
                environmentId,
                size,
                (long) page * size);
        return page(items, page, size, total);
    }

    @Override
    public RunProfileRevision insertRunProfileWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            String name,
            RunProfileContent content,
            String principalId,
            Instant now) {
        try {
            jdbc.update(
                    """
                    insert into run_profiles
                        (run_profile_id, organization_id, project_id, name, next_revision_number,
                         version, created_by, created_at)
                    values (?, ?, ?, ?, 2, 0, ?, ?)
                    """,
                    runProfileId,
                    organizationId,
                    projectId,
                    name,
                    principalId,
                    Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict(
                    "RUN_PROFILE_NAME_CONFLICT", "A run profile with that name already exists.");
        }
        insertRunProfileRevision(
                organizationId, projectId, runProfileId, revisionId, 1, content, principalId, now);
        return findRunProfileRevision(organizationId, projectId, runProfileId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Override
    public Optional<RunProfile> findRunProfile(
            UUID organizationId, UUID projectId, UUID runProfileId) {
        return jdbc.query(
                        """
                        select run_profile_id, project_id, name, created_by, created_at
                          from run_profiles
                         where organization_id = ? and project_id = ? and run_profile_id = ?
                        """,
                        JdbcConfigurationRepository::runProfile,
                        organizationId,
                        projectId,
                        runProfileId)
                .stream()
                .findFirst();
    }

    @Override
    public PageResult<RunProfile> listRunProfiles(
            UUID organizationId, UUID projectId, int page, int size) {
        long total = count(
                "select count(*) from run_profiles where organization_id = ? and project_id = ?",
                organizationId,
                projectId);
        List<RunProfile> items = jdbc.query(
                """
                select run_profile_id, project_id, name, created_by, created_at
                  from run_profiles
                 where organization_id = ? and project_id = ?
                 order by created_at desc, run_profile_id desc
                 limit ? offset ?
                """,
                JdbcConfigurationRepository::runProfile,
                organizationId,
                projectId,
                size,
                (long) page * size);
        return page(items, page, size, total);
    }

    @Override
    public RunProfileRevision appendRunProfileRevision(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            RunProfileContent content,
            String principalId,
            Instant now) {
        long revisionNumber = allocateRunProfileRevision(organizationId, projectId, runProfileId);
        insertRunProfileRevision(
                organizationId,
                projectId,
                runProfileId,
                revisionId,
                revisionNumber,
                content,
                principalId,
                now);
        return findRunProfileRevision(organizationId, projectId, runProfileId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Override
    public Optional<RunProfileRevision> findRunProfileRevision(
            UUID organizationId, UUID projectId, UUID runProfileId, UUID revisionId) {
        return runProfileRevisionHeader(
                        """
                        select revision_id, run_profile_id, project_id, revision_number,
                               environment_revision_id, parallelism, retry_max_attempts,
                               retry_delay_milliseconds, execution_timeout_seconds,
                               max_artifact_bytes, max_total_bytes, content_sha256,
                               created_by, created_at
                          from run_profile_revisions
                         where organization_id = ? and project_id = ?
                           and run_profile_id = ? and revision_id = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        runProfileId,
                        revisionId)
                .map(header -> runProfileRevision(organizationId, header));
    }

    @Override
    public Optional<RunProfileRevision> findRunProfileRevisionById(
            UUID organizationId, UUID projectId, UUID revisionId) {
        return runProfileRevisionHeader(
                        """
                        select revision_id, run_profile_id, project_id, revision_number,
                               environment_revision_id, parallelism, retry_max_attempts,
                               retry_delay_milliseconds, execution_timeout_seconds,
                               max_artifact_bytes, max_total_bytes, content_sha256,
                               created_by, created_at
                          from run_profile_revisions
                         where organization_id = ? and project_id = ?
                           and revision_id = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        revisionId)
                .map(header -> runProfileRevision(organizationId, header));
    }

    @Override
    public Optional<RunProfileRevision> findRunProfileRevisionByNumber(
            UUID organizationId, UUID projectId, UUID runProfileId, long revisionNumber) {
        return runProfileRevisionHeader(
                        """
                        select revision_id, run_profile_id, project_id, revision_number,
                               environment_revision_id, parallelism, retry_max_attempts,
                               retry_delay_milliseconds, execution_timeout_seconds,
                               max_artifact_bytes, max_total_bytes, content_sha256,
                               created_by, created_at
                          from run_profile_revisions
                         where organization_id = ? and project_id = ?
                           and run_profile_id = ? and revision_number = ? and sealed = true
                        """,
                        organizationId,
                        projectId,
                        runProfileId,
                        revisionNumber)
                .map(header -> runProfileRevision(organizationId, header));
    }

    @Override
    public PageResult<RunProfileRevisionSummary> listRunProfileRevisions(
            UUID organizationId, UUID projectId, UUID runProfileId, int page, int size) {
        long total = count(
                """
                select count(*) from run_profile_revisions
                 where organization_id = ? and project_id = ? and run_profile_id = ? and sealed = true
                """,
                organizationId,
                projectId,
                runProfileId);
        List<RunProfileRevisionSummary> items = jdbc.query(
                """
                select revision_id, run_profile_id, revision_number, environment_revision_id,
                       content_sha256, created_by, created_at
                  from run_profile_revisions
                 where organization_id = ? and project_id = ? and run_profile_id = ? and sealed = true
                 order by revision_number desc
                 limit ? offset ?
                """,
                (resultSet, rowNumber) -> new RunProfileRevisionSummary(
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getObject("run_profile_id", UUID.class),
                        resultSet.getLong("revision_number"),
                        resultSet.getObject("environment_revision_id", UUID.class),
                        digest(resultSet.getString("content_sha256")),
                        resultSet.getString("created_by"),
                        resultSet.getTimestamp("created_at").toInstant()),
                organizationId,
                projectId,
                runProfileId,
                size,
                (long) page * size);
        return page(items, page, size, total);
    }

    private void insertEnvironmentRevision(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            long revisionNumber,
            EnvironmentContent content,
            String principalId,
            Instant now) {
        jdbc.update(
                """
                insert into environment_revisions
                    (revision_id, organization_id, project_id, environment_id, revision_number,
                     content_sha256, sealed, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, false, ?, ?)
                """,
                revisionId,
                organizationId,
                projectId,
                environmentId,
                revisionNumber,
                digestHex(content.digest()),
                principalId,
                Timestamp.from(now));
        for (ConfigurationVariable variable : content.variables()) {
            insertEnvironmentVariable(organizationId, projectId, environmentId, revisionId, variable);
        }
        for (SecretBinding binding : content.secretBindings()) {
            jdbc.update(
                    """
                    insert into environment_revision_entries
                        (organization_id, project_id, environment_id, environment_revision_id,
                         config_key, value_kind, secret_reference_id)
                    values (?, ?, ?, ?, ?, 'SECRET_REFERENCE', ?)
                    """,
                    organizationId,
                    projectId,
                    environmentId,
                    revisionId,
                    binding.key(),
                    binding.secretReferenceId());
        }
        jdbc.update(
                """
                update environment_revisions set sealed = true
                 where organization_id = ? and project_id = ? and environment_id = ? and revision_id = ?
                """,
                organizationId,
                projectId,
                environmentId,
                revisionId);
    }

    private void insertEnvironmentVariable(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            ConfigurationVariable variable) {
        jdbc.update(
                """
                insert into environment_revision_entries
                    (organization_id, project_id, environment_id, environment_revision_id,
                     config_key, value_kind, string_value, integer_value, boolean_value)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId,
                projectId,
                environmentId,
                revisionId,
                variable.key(),
                variable.type().name(),
                variable.type() == ConfigurationValueType.STRING ? variable.value() : null,
                variable.type() == ConfigurationValueType.INTEGER ? variable.value() : null,
                variable.type() == ConfigurationValueType.BOOLEAN ? variable.value() : null);
    }

    private long allocateEnvironmentRevision(UUID organizationId, UUID projectId, UUID environmentId) {
        try {
            long revisionNumber = jdbc.queryForObject(
                    """
                    select next_revision_number from environments
                     where organization_id = ? and project_id = ? and environment_id = ?
                     for update
                    """,
                    Long.class,
                    organizationId,
                    projectId,
                    environmentId);
            jdbc.update(
                    """
                    update environments
                       set next_revision_number = next_revision_number + 1, version = version + 1
                     where organization_id = ? and project_id = ? and environment_id = ?
                    """,
                    organizationId,
                    projectId,
                    environmentId);
            return revisionNumber;
        } catch (EmptyResultDataAccessException exception) {
            throw ApiException.notFound();
        }
    }

    private Optional<EnvironmentRevisionHeader> environmentRevisionHeader(String sql, Object... parameters) {
        return jdbc.query(sql, JdbcConfigurationRepository::environmentRevisionHeader, parameters)
                .stream()
                .findFirst();
    }

    private EnvironmentRevision environmentRevision(UUID organizationId, EnvironmentRevisionHeader header) {
        List<ConfigurationVariable> variables = new ArrayList<>();
        List<SecretBinding> bindings = new ArrayList<>();
        jdbc.query(
                """
                select config_key, value_kind, string_value, integer_value, boolean_value, secret_reference_id
                  from environment_revision_entries
                 where organization_id = ? and project_id = ?
                   and environment_id = ? and environment_revision_id = ?
                 order by config_key collate "C"
                """,
                resultSet -> {
                    String kind = resultSet.getString("value_kind");
                    if (kind.equals("SECRET_REFERENCE")) {
                        bindings.add(new SecretBinding(
                                resultSet.getString("config_key"),
                                resultSet.getObject("secret_reference_id", UUID.class)));
                    } else {
                        variables.add(variable(resultSet));
                    }
                },
                organizationId,
                header.projectId(),
                header.environmentId(),
                header.revisionId());
        return new EnvironmentRevision(
                header.revisionId(),
                header.environmentId(),
                header.projectId(),
                header.revisionNumber(),
                variables,
                bindings,
                digest(header.digest()),
                header.createdBy(),
                header.createdAt());
    }

    private void insertRunProfileRevision(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            long revisionNumber,
            RunProfileContent content,
            String principalId,
            Instant now) {
        jdbc.update(
                """
                insert into run_profile_revisions
                    (revision_id, organization_id, project_id, run_profile_id, revision_number,
                     environment_id, environment_revision_id, parallelism, retry_max_attempts,
                     retry_delay_milliseconds, execution_timeout_seconds, max_artifact_bytes,
                     max_total_bytes, content_sha256, sealed, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?)
                """,
                revisionId,
                organizationId,
                projectId,
                runProfileId,
                revisionNumber,
                content.environmentId(),
                content.environmentRevisionId(),
                content.parallelism(),
                content.scenarioRetry().maxAttempts(),
                content.scenarioRetry().delayMilliseconds(),
                content.executionTimeoutSeconds(),
                content.artifactPolicy().maxArtifactBytes(),
                content.artifactPolicy().maxTotalBytes(),
                digestHex(content.digest()),
                principalId,
                Timestamp.from(now));
        for (String tag : content.selection().tags()) {
            jdbc.update(
                    """
                    insert into run_profile_revision_tags
                        (organization_id, project_id, run_profile_id, run_profile_revision_id, tag)
                    values (?, ?, ?, ?, ?)
                    """,
                    organizationId,
                    projectId,
                    runProfileId,
                    revisionId,
                    tag);
        }
        for (ArtifactType type : content.artifactPolicy().types()) {
            jdbc.update(
                    """
                    insert into run_profile_revision_artifact_types
                        (organization_id, project_id, run_profile_id, run_profile_revision_id, artifact_type)
                    values (?, ?, ?, ?, ?)
                    """,
                    organizationId,
                    projectId,
                    runProfileId,
                    revisionId,
                    type.name());
        }
        for (ConfigurationVariable override : content.configurationOverrides()) {
            insertRunProfileOverride(organizationId, projectId, runProfileId, revisionId, override);
        }
        jdbc.update(
                """
                update run_profile_revisions set sealed = true
                 where organization_id = ? and project_id = ? and run_profile_id = ? and revision_id = ?
                """,
                organizationId,
                projectId,
                runProfileId,
                revisionId);
    }

    private void insertRunProfileOverride(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            ConfigurationVariable override) {
        jdbc.update(
                """
                insert into run_profile_revision_overrides
                    (organization_id, project_id, run_profile_id, run_profile_revision_id,
                     config_key, value_kind, string_value, integer_value, boolean_value)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId,
                projectId,
                runProfileId,
                revisionId,
                override.key(),
                override.type().name(),
                override.type() == ConfigurationValueType.STRING ? override.value() : null,
                override.type() == ConfigurationValueType.INTEGER ? override.value() : null,
                override.type() == ConfigurationValueType.BOOLEAN ? override.value() : null);
    }

    private long allocateRunProfileRevision(UUID organizationId, UUID projectId, UUID runProfileId) {
        try {
            long revisionNumber = jdbc.queryForObject(
                    """
                    select next_revision_number from run_profiles
                     where organization_id = ? and project_id = ? and run_profile_id = ?
                     for update
                    """,
                    Long.class,
                    organizationId,
                    projectId,
                    runProfileId);
            jdbc.update(
                    """
                    update run_profiles
                       set next_revision_number = next_revision_number + 1, version = version + 1
                     where organization_id = ? and project_id = ? and run_profile_id = ?
                    """,
                    organizationId,
                    projectId,
                    runProfileId);
            return revisionNumber;
        } catch (EmptyResultDataAccessException exception) {
            throw ApiException.notFound();
        }
    }

    private Optional<RunProfileRevisionHeader> runProfileRevisionHeader(String sql, Object... parameters) {
        return jdbc.query(sql, JdbcConfigurationRepository::runProfileRevisionHeader, parameters)
                .stream()
                .findFirst();
    }

    private RunProfileRevision runProfileRevision(UUID organizationId, RunProfileRevisionHeader header) {
        List<String> tags = jdbc.queryForList(
                """
                select tag from run_profile_revision_tags
                 where organization_id = ? and project_id = ?
                   and run_profile_id = ? and run_profile_revision_id = ?
                 order by tag collate "C"
                """,
                String.class,
                organizationId,
                header.projectId(),
                header.runProfileId(),
                header.revisionId());
        List<ArtifactType> artifactTypes = jdbc.queryForList(
                        """
                        select artifact_type from run_profile_revision_artifact_types
                         where organization_id = ? and project_id = ?
                           and run_profile_id = ? and run_profile_revision_id = ?
                         order by artifact_type
                        """,
                        String.class,
                        organizationId,
                        header.projectId(),
                        header.runProfileId(),
                        header.revisionId())
                .stream()
                .map(ArtifactType::valueOf)
                .toList();
        List<ConfigurationVariable> overrides = jdbc.query(
                """
                select config_key, value_kind, string_value, integer_value, boolean_value
                  from run_profile_revision_overrides
                 where organization_id = ? and project_id = ?
                   and run_profile_id = ? and run_profile_revision_id = ?
                 order by config_key collate "C"
                """,
                (resultSet, rowNumber) -> variable(resultSet),
                organizationId,
                header.projectId(),
                header.runProfileId(),
                header.revisionId());
        return new RunProfileRevision(
                header.revisionId(),
                header.runProfileId(),
                header.projectId(),
                header.revisionNumber(),
                header.environmentRevisionId(),
                new RunSelection(tags),
                header.parallelism(),
                new ScenarioRetry(header.maxAttempts(), header.delayMilliseconds()),
                header.timeoutSeconds(),
                new ArtifactPolicy(artifactTypes, header.maxArtifactBytes(), header.maxTotalBytes()),
                overrides,
                digest(header.digest()),
                header.createdBy(),
                header.createdAt());
    }

    private static SecretReference secretReference(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SecretReference(
                resultSet.getObject("secret_reference_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static Environment environment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Environment(
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static RunProfile runProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RunProfile(
                resultSet.getObject("run_profile_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static EnvironmentRevisionHeader environmentRevisionHeader(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new EnvironmentRevisionHeader(
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getLong("revision_number"),
                resultSet.getString("content_sha256"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static RunProfileRevisionHeader runProfileRevisionHeader(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RunProfileRevisionHeader(
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getObject("run_profile_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getLong("revision_number"),
                resultSet.getObject("environment_revision_id", UUID.class),
                resultSet.getInt("parallelism"),
                resultSet.getInt("retry_max_attempts"),
                resultSet.getInt("retry_delay_milliseconds"),
                resultSet.getInt("execution_timeout_seconds"),
                resultSet.getLong("max_artifact_bytes"),
                resultSet.getLong("max_total_bytes"),
                resultSet.getString("content_sha256"),
                resultSet.getString("created_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static ConfigurationVariable variable(ResultSet resultSet) throws SQLException {
        ConfigurationValueType type = ConfigurationValueType.valueOf(resultSet.getString("value_kind"));
        Object value = switch (type) {
            case STRING -> resultSet.getString("string_value");
            case INTEGER -> resultSet.getLong("integer_value");
            case BOOLEAN -> resultSet.getBoolean("boolean_value");
        };
        return new ConfigurationVariable(resultSet.getString("config_key"), type, value);
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private static <T> PageResult<T> page(List<T> items, int page, int size, long total) {
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new PageResult<>(items, page, size, total, totalPages);
    }

    private static String digestHex(String digest) {
        return digest.substring("sha256:".length());
    }

    private static String digest(String hex) {
        return "sha256:" + hex;
    }

    private record EnvironmentRevisionHeader(
            UUID revisionId,
            UUID environmentId,
            UUID projectId,
            long revisionNumber,
            String digest,
            String createdBy,
            Instant createdAt) {}

    private record RunProfileRevisionHeader(
            UUID revisionId,
            UUID runProfileId,
            UUID projectId,
            long revisionNumber,
            UUID environmentRevisionId,
            int parallelism,
            int maxAttempts,
            int delayMilliseconds,
            int timeoutSeconds,
            long maxArtifactBytes,
            long maxTotalBytes,
            String digest,
            String createdBy,
            Instant createdAt) {}
}
