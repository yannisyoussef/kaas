package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.application.IdempotencyRepository.Scope;
import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ConfigurationPolicy;
import com.kaas.api.controlplane.domain.ConfigurationPolicy.ConfigurationConflictException;
import com.kaas.api.controlplane.domain.ConfigurationPolicy.ValidationException;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.Environment;
import com.kaas.api.controlplane.domain.EnvironmentRevision;
import com.kaas.api.controlplane.domain.EnvironmentRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunProfile;
import com.kaas.api.controlplane.domain.RunProfileRevision;
import com.kaas.api.controlplane.domain.RunProfileRevisionSummary;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SecretBinding;
import com.kaas.api.controlplane.domain.SecretReference;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigurationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{8,128}");
    private static final Pattern SECRET_REFERENCE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

    private final ConfigurationRepository repository;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public ConfigurationService(
            ConfigurationRepository repository, IdempotencyRepository idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public Creation<SecretReference> createSecretReference(
            TenantPrincipal principal, UUID projectId, String key, String name) {
        requireProject(principal, projectId);
        String acceptedName = secretReferenceName(name);
        String operation = "createSecretReference";
        Scope scope = scope(principal, operation, "/projects/" + projectId, key);
        String fingerprint = RequestFingerprint.of(operation, projectId.toString(), acceptedName);
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> repository
                        .findSecretReference(principal.organizationId(), projectId, resourceId)
                        .orElseThrow(ApiException::notFound),
                () -> repository.insertSecretReference(
                        principal.organizationId(),
                        projectId,
                        UUID.randomUUID(),
                        acceptedName,
                        principal.principalId(),
                        now),
                SecretReference::secretReferenceId,
                reference -> "/api/v1/projects/" + projectId + "/secret-references/"
                        + reference.secretReferenceId());
    }

    @Transactional(readOnly = true)
    public SecretReference getSecretReference(
            TenantPrincipal principal, UUID projectId, UUID secretReferenceId) {
        return repository
                .findSecretReference(principal.organizationId(), projectId, secretReferenceId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<SecretReference> listSecretReferences(
            TenantPrincipal principal, UUID projectId, int page, int size) {
        requireProject(principal, projectId);
        return repository.listSecretReferences(principal.organizationId(), projectId, page, size);
    }

    @Transactional
    public Creation<CreatedEnvironment> createEnvironment(
            TenantPrincipal principal,
            UUID projectId,
            String key,
            String name,
            List<ConfigurationVariable> variables,
            List<SecretBinding> bindings) {
        requireProject(principal, projectId);
        String acceptedName = boundedName(name, "name");
        var content = environmentContent(principal, projectId, variables, bindings);
        String operation = "createEnvironment";
        Scope scope = scope(principal, operation, "/projects/" + projectId, key);
        String fingerprint = RequestFingerprint.of(operation, projectId.toString(), acceptedName, content.digest());
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> {
                    Environment environment = repository
                            .findEnvironment(principal.organizationId(), projectId, resourceId)
                            .orElseThrow(ApiException::notFound);
                    EnvironmentRevision revision = repository
                            .findEnvironmentRevisionByNumber(
                                    principal.organizationId(), projectId, resourceId, 1)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedEnvironment(environment, revision);
                },
                () -> {
                    UUID environmentId = UUID.randomUUID();
                    EnvironmentRevision revision = repository.insertEnvironmentWithInitialRevision(
                            principal.organizationId(),
                            projectId,
                            environmentId,
                            UUID.randomUUID(),
                            acceptedName,
                            content,
                            principal.principalId(),
                            now);
                    Environment environment = repository
                            .findEnvironment(principal.organizationId(), projectId, environmentId)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedEnvironment(environment, revision);
                },
                created -> created.environment().environmentId(),
                created -> "/api/v1/projects/" + projectId + "/environments/"
                        + created.environment().environmentId());
    }

    @Transactional(readOnly = true)
    public Environment getEnvironment(TenantPrincipal principal, UUID projectId, UUID environmentId) {
        return repository
                .findEnvironment(principal.organizationId(), projectId, environmentId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<Environment> listEnvironments(
            TenantPrincipal principal, UUID projectId, int page, int size) {
        requireProject(principal, projectId);
        return repository.listEnvironments(principal.organizationId(), projectId, page, size);
    }

    @Transactional
    public Creation<EnvironmentRevision> appendEnvironmentRevision(
            TenantPrincipal principal,
            UUID projectId,
            UUID environmentId,
            String key,
            List<ConfigurationVariable> variables,
            List<SecretBinding> bindings) {
        var content = environmentContent(principal, projectId, variables, bindings);
        String operation = "createEnvironmentRevision";
        Scope scope = scope(
                principal, operation, "/projects/" + projectId + "/environments/" + environmentId, key);
        String fingerprint = RequestFingerprint.of(
                operation, projectId.toString(), environmentId.toString(), content.digest());
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> repository
                        .findEnvironmentRevision(
                                principal.organizationId(), projectId, environmentId, resourceId)
                        .orElseThrow(ApiException::notFound),
                () -> repository.appendEnvironmentRevision(
                        principal.organizationId(),
                        projectId,
                        environmentId,
                        UUID.randomUUID(),
                        content,
                        principal.principalId(),
                        now),
                EnvironmentRevision::revisionId,
                revision -> "/api/v1/projects/" + projectId + "/environments/" + environmentId
                        + "/revisions/" + revision.revisionId());
    }

    @Transactional(readOnly = true)
    public EnvironmentRevision getEnvironmentRevision(
            TenantPrincipal principal, UUID projectId, UUID environmentId, UUID revisionId) {
        return repository
                .findEnvironmentRevision(principal.organizationId(), projectId, environmentId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<EnvironmentRevisionSummary> listEnvironmentRevisions(
            TenantPrincipal principal, UUID projectId, UUID environmentId, int page, int size) {
        getEnvironment(principal, projectId, environmentId);
        return repository.listEnvironmentRevisions(
                principal.organizationId(), projectId, environmentId, page, size);
    }

    @Transactional
    public Creation<CreatedRunProfile> createRunProfile(
            TenantPrincipal principal,
            UUID projectId,
            String key,
            String name,
            UUID environmentRevisionId,
            List<String> tags,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        requireProject(principal, projectId);
        String acceptedName = boundedName(name, "name");
        var content = runProfileContent(
                principal,
                projectId,
                environmentRevisionId,
                tags,
                parallelism,
                retry,
                timeoutSeconds,
                artifactPolicy,
                overrides);
        String operation = "createRunProfile";
        Scope scope = scope(principal, operation, "/projects/" + projectId, key);
        String fingerprint = RequestFingerprint.of(operation, projectId.toString(), acceptedName, content.digest());
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> {
                    RunProfile profile = repository
                            .findRunProfile(principal.organizationId(), projectId, resourceId)
                            .orElseThrow(ApiException::notFound);
                    RunProfileRevision revision = repository
                            .findRunProfileRevisionByNumber(
                                    principal.organizationId(), projectId, resourceId, 1)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedRunProfile(profile, revision);
                },
                () -> {
                    UUID profileId = UUID.randomUUID();
                    RunProfileRevision revision = repository.insertRunProfileWithInitialRevision(
                            principal.organizationId(),
                            projectId,
                            profileId,
                            UUID.randomUUID(),
                            acceptedName,
                            content,
                            principal.principalId(),
                            now);
                    RunProfile profile = repository
                            .findRunProfile(principal.organizationId(), projectId, profileId)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedRunProfile(profile, revision);
                },
                created -> created.runProfile().runProfileId(),
                created -> "/api/v1/projects/" + projectId + "/run-profiles/"
                        + created.runProfile().runProfileId());
    }

    @Transactional(readOnly = true)
    public RunProfile getRunProfile(TenantPrincipal principal, UUID projectId, UUID runProfileId) {
        return repository
                .findRunProfile(principal.organizationId(), projectId, runProfileId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<RunProfile> listRunProfiles(
            TenantPrincipal principal, UUID projectId, int page, int size) {
        requireProject(principal, projectId);
        return repository.listRunProfiles(principal.organizationId(), projectId, page, size);
    }

    @Transactional
    public Creation<RunProfileRevision> appendRunProfileRevision(
            TenantPrincipal principal,
            UUID projectId,
            UUID runProfileId,
            String key,
            UUID environmentRevisionId,
            List<String> tags,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        var content = runProfileContent(
                principal,
                projectId,
                environmentRevisionId,
                tags,
                parallelism,
                retry,
                timeoutSeconds,
                artifactPolicy,
                overrides);
        String operation = "createRunProfileRevision";
        Scope scope = scope(
                principal, operation, "/projects/" + projectId + "/run-profiles/" + runProfileId, key);
        String fingerprint = RequestFingerprint.of(
                operation, projectId.toString(), runProfileId.toString(), content.digest());
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> repository
                        .findRunProfileRevision(
                                principal.organizationId(), projectId, runProfileId, resourceId)
                        .orElseThrow(ApiException::notFound),
                () -> repository.appendRunProfileRevision(
                        principal.organizationId(),
                        projectId,
                        runProfileId,
                        UUID.randomUUID(),
                        content,
                        principal.principalId(),
                        now),
                RunProfileRevision::revisionId,
                revision -> "/api/v1/projects/" + projectId + "/run-profiles/" + runProfileId
                        + "/revisions/" + revision.revisionId());
    }

    @Transactional(readOnly = true)
    public RunProfileRevision getRunProfileRevision(
            TenantPrincipal principal, UUID projectId, UUID runProfileId, UUID revisionId) {
        return repository
                .findRunProfileRevision(principal.organizationId(), projectId, runProfileId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<RunProfileRevisionSummary> listRunProfileRevisions(
            TenantPrincipal principal, UUID projectId, UUID runProfileId, int page, int size) {
        getRunProfile(principal, projectId, runProfileId);
        return repository.listRunProfileRevisions(
                principal.organizationId(), projectId, runProfileId, page, size);
    }

    private ConfigurationPolicy.EnvironmentContent environmentContent(
            TenantPrincipal principal,
            UUID projectId,
            List<ConfigurationVariable> variables,
            List<SecretBinding> bindings) {
        try {
            var content = ConfigurationPolicy.environment(variables, bindings);
            Set<UUID> referenceIds = content.secretBindings().stream()
                    .map(SecretBinding::secretReferenceId)
                    .collect(Collectors.toSet());
            if (!repository.allSecretReferencesExist(principal.organizationId(), projectId, referenceIds)) {
                throw ApiException.notFound();
            }
            return content;
        } catch (ValidationException exception) {
            throw ApiException.validation("/" + exception.pointer(), exception.getMessage());
        }
    }

    private ConfigurationPolicy.RunProfileContent runProfileContent(
            TenantPrincipal principal,
            UUID projectId,
            UUID environmentRevisionId,
            List<String> tags,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        EnvironmentRevision environmentRevision = repository
                .findEnvironmentRevisionById(principal.organizationId(), projectId, environmentRevisionId)
                .orElseThrow(ApiException::notFound);
        try {
            return ConfigurationPolicy.runProfile(
                    environmentRevision,
                    tags,
                    parallelism,
                    retry,
                    timeoutSeconds,
                    artifactPolicy,
                    overrides);
        } catch (ValidationException exception) {
            throw ApiException.validation("/" + exception.pointer(), exception.getMessage());
        } catch (ConfigurationConflictException exception) {
            throw ApiException.validation("/configurationOverrides", "The configuration overrides conflict with the environment.");
        }
    }

    private void requireProject(TenantPrincipal principal, UUID projectId) {
        if (!repository.projectExists(principal.organizationId(), projectId)) {
            throw ApiException.notFound();
        }
    }

    private <T> Creation<T> idempotent(
            Scope scope,
            String fingerprint,
            Function<UUID, T> replayLoader,
            Supplier<T> create,
            Function<T, UUID> id,
            Function<T, String> location) {
        idempotency.lock(scope);
        var existing = idempotency.find(scope);
        if (existing.isPresent()) {
            var record = existing.orElseThrow();
            if (!record.requestSha256().equals(fingerprint)) {
                throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different request.");
            }
            T replayed = replayLoader.apply(record.resourceId());
            LOGGER.atInfo()
                    .addKeyValue("operation", scope.operation())
                    .addKeyValue("organizationId", scope.organizationId())
                    .log("Replayed idempotent configuration mutation");
            return new Creation<>(replayed, record.location(), true);
        }
        T created = create.get();
        String createdLocation = location.apply(created);
        idempotency.insert(scope, fingerprint, id.apply(created), 201, createdLocation, clock.instant());
        LOGGER.atInfo()
                .addKeyValue("operation", scope.operation())
                .addKeyValue("organizationId", scope.organizationId())
                .log("Created configuration resource");
        return new Creation<>(created, createdLocation, false);
    }

    private static Scope scope(TenantPrincipal principal, String operation, String path, String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.validation(
                    "/headers/Idempotency-Key", "Idempotency-Key must be 8 to 128 URL-safe ASCII characters.");
        }
        return new Scope(principal.organizationId(), principal.principalId(), operation, path, key);
    }

    private static String boundedName(String value, String field) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw ApiException.validation("/" + field, field + " is invalid.");
        }
        return value;
    }

    private static String secretReferenceName(String value) {
        if (value == null || !SECRET_REFERENCE_NAME.matcher(value).matches()) {
            throw ApiException.validation(
                    "/name", "name must be a portable identifier of at most 128 characters.");
        }
        return value;
    }
}
