package com.kaas.api.execution.infrastructure;

import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SecretBinding;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.execution.application.ExecutionAuthorizationRepository;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionAuthorization;
import com.kaas.api.execution.domain.ExecutionCapability;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import com.kaas.api.execution.domain.NetworkPolicyType;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcExecutionAuthorizationRepository implements ExecutionAuthorizationRepository {
    private final JdbcTemplate jdbc;

    JdbcExecutionAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant currentDatabaseTime() {
        // clock_timestamp() rather than now(): now() is the transaction's start instant, which would make every
        // issuance in one transaction share a timestamp and would compare a stale instant against a live bound.
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public Optional<SnapshotContext> loadSnapshot(UUID organizationId, UUID projectId, UUID runId) {
        var header = jdbc
                .query(
                        """
                        select s.sealed, s.parallelism, s.retry_max_attempts, s.retry_delay_milliseconds,
                               s.execution_timeout_seconds, s.max_artifact_bytes, s.max_total_bytes,
                               s.engine, s.engine_version, r.snapshot_sha256
                          from run_snapshots s
                          join test_runs r on r.run_id = s.run_id
                         where s.organization_id = ? and s.project_id = ? and s.run_id = ?
                        """,
                        (resultSet, rowNumber) -> new Object[] {
                            resultSet.getBoolean("sealed"),
                            resultSet.getInt("parallelism"),
                            resultSet.getInt("retry_max_attempts"),
                            resultSet.getInt("retry_delay_milliseconds"),
                            resultSet.getInt("execution_timeout_seconds"),
                            resultSet.getLong("max_artifact_bytes"),
                            resultSet.getLong("max_total_bytes"),
                            resultSet.getString("engine"),
                            resultSet.getString("engine_version"),
                            resultSet.getString("snapshot_sha256")
                        },
                        organizationId,
                        projectId,
                        runId)
                .stream()
                .findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = header.orElseThrow();

        List<SnapshotFeature> features = jdbc.query(
                """
                select feature_id, feature_revision_id, revision_number, logical_path, source_sha256
                  from run_snapshot_features
                 where organization_id = ? and project_id = ? and run_id = ?
                 order by ordinal
                """,
                (resultSet, rowNumber) -> new SnapshotFeature(
                        resultSet.getObject("feature_id", UUID.class),
                        resultSet.getObject("feature_revision_id", UUID.class),
                        resultSet.getLong("revision_number"),
                        resultSet.getString("logical_path"),
                        resultSet.getString("source_sha256")),
                organizationId,
                projectId,
                runId);

        // One pass over the configuration table produces both the non-secret values and the secret bindings.
        // They live in one table discriminated by value_kind, and reading them separately would be two queries
        // whose results could, under a concurrent write, describe different snapshots.
        List<ConfigurationVariable> configuration = new java.util.ArrayList<>();
        List<SecretBinding> secretBindings = new java.util.ArrayList<>();
        jdbc.query(
                """
                select config_key, value_kind, string_value, integer_value, boolean_value, secret_reference_id
                  from run_snapshot_configuration_entries
                 where organization_id = ? and project_id = ? and run_id = ?
                 order by config_key
                """,
                resultSet -> {
                    String key = resultSet.getString("config_key");
                    switch (resultSet.getString("value_kind")) {
                        case "STRING" -> configuration.add(new ConfigurationVariable(
                                key, ConfigurationValueType.STRING, resultSet.getString("string_value")));
                        case "INTEGER" -> configuration.add(new ConfigurationVariable(
                                key, ConfigurationValueType.INTEGER, resultSet.getLong("integer_value")));
                        case "BOOLEAN" -> configuration.add(new ConfigurationVariable(
                                key, ConfigurationValueType.BOOLEAN, resultSet.getBoolean("boolean_value")));
                        case "SECRET_REFERENCE" -> secretBindings.add(new SecretBinding(
                                key, resultSet.getObject("secret_reference_id", UUID.class)));
                        default -> throw new IllegalStateException("Unknown snapshot value kind.");
                    }
                },
                organizationId,
                projectId,
                runId);

        List<String> tags = jdbc.queryForList(
                """
                select tag from run_snapshot_tags
                 where organization_id = ? and project_id = ? and run_id = ? order by tag
                """,
                String.class,
                organizationId,
                projectId,
                runId);
        List<ArtifactType> artifactTypes = jdbc
                .queryForList(
                        """
                        select artifact_type from run_snapshot_artifact_types
                         where organization_id = ? and project_id = ? and run_id = ? order by artifact_type
                        """,
                        String.class,
                        organizationId,
                        projectId,
                        runId)
                .stream()
                .map(ArtifactType::valueOf)
                .toList();

        Long totalSourceBytes = jdbc.queryForObject(
                """
                select coalesce(sum(octet_length(r.source)), 0)
                  from run_snapshot_features f
                  join feature_revisions r
                    on r.organization_id = f.organization_id and r.project_id = f.project_id
                   and r.feature_id = f.feature_id and r.revision_id = f.feature_revision_id
                 where f.organization_id = ? and f.project_id = ? and f.run_id = ?
                """,
                Long.class,
                organizationId,
                projectId,
                runId);

        return Optional.of(new SnapshotContext(
                (String) row[9],
                (Boolean) row[0],
                totalSourceBytes == null ? 0L : totalSourceBytes,
                features,
                List.copyOf(secretBindings),
                List.copyOf(configuration),
                new RunSelection(tags),
                (Integer) row[1],
                new ScenarioRetry((Integer) row[2], (Integer) row[3]),
                (Integer) row[4],
                new ArtifactPolicy(artifactTypes, (Long) row[5], (Long) row[6]),
                new EngineDescriptor((String) row[7], (String) row[8])));
    }

    @Override
    public void persistAcquisition(
            UUID organizationId, UUID projectId, UUID runId, ExecutionAttempt attempt) {
        var assignment = attempt.assignment();
        // Compare-and-set on acquired_at being null, so two workers racing produce one holder. The guard
        // enforces the same thing independently; this makes losing the race a clean refusal rather than a
        // constraint violation.
        int acquired = jdbc.update(
                """
                update execution_attempts
                   set assigned_worker_id = ?, acquired_at = ?
                 where organization_id = ? and project_id = ? and run_id = ? and attempt_id = ?
                   and attempt_state = 'CLAIMED' and acquired_at is null and fenced_at is null
                   and assignment_epoch = ?
                """,
                assignment.workerId(),
                Timestamp.from(assignment.acquiredAt()),
                organizationId,
                projectId,
                runId,
                attempt.attemptId(),
                assignment.epoch());
        if (acquired != 1) {
            throw new IllegalStateException("The assignment was acquired by another worker.");
        }
    }

    @Override
    public Optional<NetworkPolicyRevision> findNetworkPolicy(UUID policyRevisionId) {
        return jdbc
                .query(
                        """
                        select policy_revision_id, policy_type, policy_version, canonical_digest,
                               created_by, created_at
                          from network_policy_revisions where policy_revision_id = ?
                        """,
                        (resultSet, rowNumber) -> new NetworkPolicyRevision(
                                resultSet.getObject("policy_revision_id", UUID.class),
                                NetworkPolicyType.valueOf(resultSet.getString("policy_type")),
                                resultSet.getInt("policy_version"),
                                resultSet.getString("canonical_digest"),
                                resultSet.getString("created_by"),
                                resultSet.getTimestamp("created_at").toInstant()),
                        policyRevisionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ExecutionAuthorization> findAuthorization(UUID attemptId, int assignmentEpoch) {
        return jdbc.query(AUTHORIZATION_SELECT + " where attempt_id = ? and assignment_epoch = ?",
                        AUTHORIZATION_MAPPER, attemptId, assignmentEpoch)
                .stream()
                .findFirst();
    }

    @Override
    public boolean persistIssuance(Issuance issuance) {
        ExecutionAuthorization authorization = issuance.authorization();
        try {
            jdbc.update(
                    """
                    insert into execution_authorizations
                        (authorization_id, organization_id, project_id, run_id, run_version, attempt_id,
                         attempt_number, assignment_epoch, worker_id, run_snapshot_sha256,
                         security_profile_version, security_assessment_digest, probe_image_digest,
                         network_policy_revision_id, issued_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    authorization.authorizationId(), authorization.organizationId(), authorization.projectId(),
                    authorization.runId(), authorization.runVersion(), authorization.attemptId(),
                    authorization.attemptNumber(), authorization.assignmentEpoch(), authorization.workerId(),
                    authorization.runSnapshotSha256(), authorization.securityProfileVersion(),
                    authorization.securityAssessmentDigest(), authorization.probeImageDigest(),
                    authorization.networkPolicyRevisionId(), Timestamp.from(authorization.issuedAt()),
                    Timestamp.from(authorization.expiresAt()));
        } catch (DuplicateKeyException lostTheRace) {
            // A concurrent request for the same assignment inserted first. The unique constraint on
            // (attempt_id, assignment_epoch) is what makes "one authorization per assignment" true under
            // concurrency rather than merely intended.
            return false;
        }
        issuance.capabilities().forEach(capability ->
                insertCapability(authorization.organizationId(), authorization.projectId(), capability));
        var command = issuance.command();
        jdbc.update(
                """
                insert into execution_commands
                    (command_id, authorization_id, organization_id, run_id, attempt_id, assignment_epoch,
                     command_digest, document, issued_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                command.commandId(), command.authorizationId(), command.organizationId(), command.runId(),
                command.attemptId(), command.assignmentEpoch(), command.commandDigest(),
                issuance.commandDocument(), Timestamp.from(command.issuedAt()),
                Timestamp.from(command.expiresAt()));
        return true;
    }

    private void insertCapability(UUID organizationId, UUID projectId, ExecutionCapability capability) {
        jdbc.update(
                """
                insert into execution_capabilities
                    (capability_id, authorization_id, organization_id, project_id, capability_type,
                     token_sha256, issued_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                capability.capabilityId(), capability.authorizationId(), organizationId, projectId,
                capability.capabilityType().name(), capability.tokenSha256(),
                Timestamp.from(capability.issuedAt()), Timestamp.from(capability.expiresAt()));
        for (ExecutionCapability.SecretScope scope : capability.secretReferenceIds()) {
            jdbc.update(
                    """
                    insert into execution_capability_secret_references
                        (capability_id, organization_id, project_id, secret_reference_id, binding_key)
                    values (?, ?, ?, ?, ?)
                    """,
                    capability.capabilityId(), organizationId, projectId, scope.secretReferenceId(),
                    scope.bindingKey());
        }
    }

    @Override
    public boolean reanchorAuthorization(UUID authorizationId, Instant expiresAt) {
        // The predicate does the work: never backwards, never on a revoked row. The trigger enforces the same
        // thing from underneath, so a caller that skipped this could not widen a window either.
        return jdbc.update(
                        """
                        update execution_authorizations set expires_at = ?
                         where authorization_id = ? and revoked_at is null and expires_at <= ?
                        """,
                        Timestamp.from(expiresAt), authorizationId, Timestamp.from(expiresAt))
                == 1;
    }

    @Override
    public int revokeForRun(UUID runId, String reason, Instant at) {
        // Capabilities first, then the authorization. The reverse order would leave an instant in which the
        // authorization was withdrawn while live capabilities still pointed at it.
        jdbc.update(
                """
                update execution_capabilities set revoked_at = ?
                 where revoked_at is null
                   and authorization_id in (select authorization_id from execution_authorizations
                                             where run_id = ? and revoked_at is null)
                """,
                Timestamp.from(at), runId);
        return jdbc.update(
                """
                update execution_authorizations set revoked_at = ?, revoked_reason = ?
                 where run_id = ? and revoked_at is null
                """,
                Timestamp.from(at), reason, runId);
    }

    @Override
    public void rotateCapabilities(UUID authorizationId, List<ExecutionCapability> replacements, Instant at) {
        // Revoke first, then insert. The reverse order would leave an instant in which two live capabilities of
        // the same type existed for one authorization, which is precisely the property rotation exists to avoid.
        for (ExecutionCapability replacement : replacements) {
            jdbc.update(
                    """
                    update execution_capabilities set revoked_at = ?
                     where authorization_id = ? and capability_type = ? and revoked_at is null
                    """,
                    Timestamp.from(at), authorizationId, replacement.capabilityType().name());
        }
        var scope = jdbc.queryForMap(
                "select organization_id, project_id from execution_authorizations where authorization_id = ?",
                authorizationId);
        replacements.forEach(replacement -> insertCapability(
                (UUID) scope.get("organization_id"), (UUID) scope.get("project_id"), replacement));
    }

    @Override
    public Optional<StoredCommand> findCommand(UUID authorizationId) {
        return jdbc
                .query(
                        """
                        select command_id, command_digest, document::text as document, issued_at, expires_at
                          from execution_commands where authorization_id = ?
                        """,
                        (resultSet, rowNumber) -> new StoredCommand(
                                resultSet.getObject("command_id", UUID.class),
                                resultSet.getString("command_digest"),
                                resultSet.getString("document"),
                                resultSet.getTimestamp("issued_at").toInstant(),
                                resultSet.getTimestamp("expires_at").toInstant()),
                        authorizationId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Redeemable> findRedeemable(String tokenSha256, CapabilityType expectedType) {
        return jdbc
                .query(
                        """
                        select c.capability_id, c.authorization_id, c.capability_type, c.token_sha256,
                               c.issued_at, c.expires_at, c.redemption_count, c.last_redeemed_at, c.revoked_at,
                               a.authorization_id as a_id, a.organization_id, a.project_id, a.run_id,
                               a.run_version, a.attempt_id, a.attempt_number, a.assignment_epoch, a.worker_id,
                               a.run_snapshot_sha256, a.security_profile_version, a.security_assessment_digest,
                               a.probe_image_digest, a.network_policy_revision_id, a.issued_at as a_issued_at,
                               a.expires_at as a_expires_at, a.revoked_at as a_revoked_at,
                               a.revoked_reason
                          from execution_capabilities c
                          join execution_authorizations a on a.authorization_id = c.authorization_id
                         where c.token_sha256 = ? and c.capability_type = ?
                        """,
                        (resultSet, rowNumber) -> new Redeemable(
                                new ExecutionCapability(
                                        resultSet.getObject("capability_id", UUID.class),
                                        resultSet.getObject("authorization_id", UUID.class),
                                        CapabilityType.valueOf(resultSet.getString("capability_type")),
                                        resultSet.getString("token_sha256"),
                                        resultSet.getTimestamp("issued_at").toInstant(),
                                        resultSet.getTimestamp("expires_at").toInstant(),
                                        resultSet.getInt("redemption_count"),
                                        instant(resultSet.getTimestamp("last_redeemed_at")),
                                        instant(resultSet.getTimestamp("revoked_at")),
                                        List.of()),
                                authorizationFrom(resultSet, "a_id", "a_issued_at", "a_expires_at", "a_revoked_at")),
                        tokenSha256,
                        expectedType.name())
                .stream()
                .findFirst();
    }

    @Override
    public boolean recordRedemption(UUID capabilityId, Instant at) {
        // The ceiling is in the predicate rather than checked beforehand, so two concurrent redemptions cannot
        // both read "63" and both write "64".
        return jdbc.update(
                        """
                        update execution_capabilities
                           set redemption_count = redemption_count + 1, last_redeemed_at = ?
                         where capability_id = ? and revoked_at is null and redemption_count < ?
                        """,
                        Timestamp.from(at), capabilityId, ExecutionCapability.MAX_REDEMPTIONS)
                == 1;
    }

    @Override
    public List<FeatureSource> loadSnapshotSources(UUID organizationId, UUID projectId, UUID runId) {
        return jdbc.query(
                """
                select f.feature_id, f.feature_revision_id, f.logical_path, f.source_sha256, r.source
                  from run_snapshot_features f
                  join feature_revisions r
                    on r.organization_id = f.organization_id
                   and r.project_id = f.project_id
                   and r.feature_id = f.feature_id
                   and r.revision_id = f.feature_revision_id
                 where f.organization_id = ? and f.project_id = ? and f.run_id = ?
                 order by f.ordinal
                """,
                (resultSet, rowNumber) -> new FeatureSource(
                        resultSet.getObject("feature_id", UUID.class),
                        resultSet.getObject("feature_revision_id", UUID.class),
                        resultSet.getString("logical_path"),
                        resultSet.getString("source_sha256"),
                        resultSet.getString("source")),
                organizationId,
                projectId,
                runId);
    }

    private static final String AUTHORIZATION_SELECT =
            """
            select authorization_id, organization_id, project_id, run_id, run_version, attempt_id,
                   attempt_number, assignment_epoch, worker_id, run_snapshot_sha256, security_profile_version,
                   security_assessment_digest, probe_image_digest, network_policy_revision_id, issued_at,
                   expires_at, revoked_at, revoked_reason
              from execution_authorizations
            """;

    private static final org.springframework.jdbc.core.RowMapper<ExecutionAuthorization> AUTHORIZATION_MAPPER =
            (resultSet, rowNumber) ->
                    authorizationFrom(resultSet, "authorization_id", "issued_at", "expires_at", "revoked_at");

    private static ExecutionAuthorization authorizationFrom(
            java.sql.ResultSet resultSet, String idColumn, String issuedColumn, String expiresColumn,
            String revokedColumn) throws java.sql.SQLException {
        return new ExecutionAuthorization(
                resultSet.getObject(idColumn, UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getLong("run_version"),
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getInt("attempt_number"),
                resultSet.getInt("assignment_epoch"),
                resultSet.getString("worker_id"),
                resultSet.getString("run_snapshot_sha256"),
                resultSet.getString("security_profile_version"),
                resultSet.getString("security_assessment_digest"),
                resultSet.getString("probe_image_digest"),
                resultSet.getObject("network_policy_revision_id", UUID.class),
                resultSet.getTimestamp(issuedColumn).toInstant(),
                resultSet.getTimestamp(expiresColumn).toInstant(),
                instant(resultSet.getTimestamp(revokedColumn)),
                resultSet.getString("revoked_reason"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
