package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.application.IdempotencyRepository.Scope;
import com.kaas.api.controlplane.domain.Feature;
import com.kaas.api.controlplane.domain.FeatureRevision;
import com.kaas.api.controlplane.domain.FeatureRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.Project;
import com.kaas.api.controlplane.domain.SourcePolicy;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlPlaneService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{8,128}");
    private static final Pattern LOGICAL_PATH = Pattern.compile(
            "^(?!/)(?!.*//)(?!.*(?:^|/)\\.{1,2}(?:/|$))(?!.*\\\\)[A-Za-z0-9._~ -]+(?:/[A-Za-z0-9._~ -]+)*\\.feature$");

    private final ControlPlaneRepository repository;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public ControlPlaneService(ControlPlaneRepository repository, IdempotencyRepository idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public Creation<Project> createProject(TenantPrincipal principal, String key, String name) {
        String acceptedName = boundedText(name, 120, "name");
        String operation = "createProject";
        Scope scope = scope(principal, operation, "/", key);
        String fingerprint = RequestFingerprint.of(operation, acceptedName);
        Instant now = clock.instant();
        repository.ensureOrganization(principal.organizationId(), now);
        return idempotent(
                scope,
                fingerprint,
                resourceId -> repository.findProject(principal.organizationId(), resourceId)
                        .orElseThrow(ApiException::notFound),
                () -> repository.insertProject(
                        principal.organizationId(), UUID.randomUUID(), acceptedName, principal.principalId(), now),
                Project::projectId,
                project -> "/api/v1/projects/" + project.projectId());
    }

    @Transactional(readOnly = true)
    public Project getProject(TenantPrincipal principal, UUID projectId) {
        return repository.findProject(principal.organizationId(), projectId).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<Project> listProjects(TenantPrincipal principal, int page, int size) {
        return repository.listProjects(principal.organizationId(), page, size);
    }

    @Transactional
    public Creation<CreatedFeature> createFeature(
            TenantPrincipal principal,
            UUID projectId,
            String key,
            String name,
            String logicalPath,
            String source) {
        getProject(principal, projectId);
        String acceptedName = boundedText(name, 160, "name");
        String acceptedPath = logicalPath(logicalPath);
        String digest = validateSource(source);
        String operation = "createFeature";
        Scope scope = scope(principal, operation, "/projects/" + projectId, key);
        String fingerprint = RequestFingerprint.of(operation, projectId.toString(), acceptedName, acceptedPath, source);
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> {
                    Feature feature = repository
                            .findFeature(principal.organizationId(), projectId, resourceId)
                            .orElseThrow(ApiException::notFound);
                    FeatureRevision revision = repository
                            .findRevisionByNumber(principal.organizationId(), projectId, resourceId, 1)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedFeature(feature, revision);
                },
                () -> {
                    UUID featureId = UUID.randomUUID();
                    FeatureRevision revision = repository.insertFeatureWithInitialRevision(
                            principal.organizationId(),
                            projectId,
                            featureId,
                            UUID.randomUUID(),
                            acceptedName,
                            acceptedPath,
                            source,
                            digest,
                            principal.principalId(),
                            now);
                    Feature feature = repository
                            .findFeature(principal.organizationId(), projectId, featureId)
                            .orElseThrow(ApiException::notFound);
                    return new CreatedFeature(feature, revision);
                },
                created -> created.feature().featureId(),
                created -> "/api/v1/projects/" + projectId + "/features/" + created.feature().featureId());
    }

    @Transactional(readOnly = true)
    public Feature getFeature(TenantPrincipal principal, UUID projectId, UUID featureId) {
        return repository.findFeature(principal.organizationId(), projectId, featureId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<Feature> listFeatures(TenantPrincipal principal, UUID projectId, int page, int size) {
        getProject(principal, projectId);
        return repository.listFeatures(principal.organizationId(), projectId, page, size);
    }

    @Transactional
    public Creation<FeatureRevision> appendRevision(
            TenantPrincipal principal, UUID projectId, UUID featureId, String key, String source) {
        String digest = validateSource(source);
        String operation = "createFeatureRevision";
        Scope scope = scope(principal, operation, "/projects/" + projectId + "/features/" + featureId, key);
        String fingerprint = RequestFingerprint.of(operation, projectId.toString(), featureId.toString(), source);
        Instant now = clock.instant();
        return idempotent(
                scope,
                fingerprint,
                resourceId -> repository
                        .findRevision(principal.organizationId(), projectId, featureId, resourceId)
                        .orElseThrow(ApiException::notFound),
                () -> repository.appendRevision(
                        principal.organizationId(),
                        projectId,
                        featureId,
                        UUID.randomUUID(),
                        source,
                        digest,
                        principal.principalId(),
                        now),
                FeatureRevision::revisionId,
                revision -> "/api/v1/projects/" + projectId + "/features/" + featureId + "/revisions/"
                        + revision.revisionId());
    }

    @Transactional(readOnly = true)
    public FeatureRevision getRevision(
            TenantPrincipal principal, UUID projectId, UUID featureId, UUID revisionId) {
        return repository.findRevision(principal.organizationId(), projectId, featureId, revisionId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<FeatureRevisionSummary> listRevisions(
            TenantPrincipal principal, UUID projectId, UUID featureId, int page, int size) {
        getFeature(principal, projectId, featureId);
        return repository.listRevisions(principal.organizationId(), projectId, featureId, page, size);
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
                    .addKeyValue("resourceId", record.resourceId())
                    .log("Replayed idempotent control-plane mutation");
            return new Creation<>(replayed, record.location(), true);
        }
        T created = create.get();
        String createdLocation = location.apply(created);
        idempotency.insert(scope, fingerprint, id.apply(created), 201, createdLocation, clock.instant());
        LOGGER.atInfo()
                .addKeyValue("operation", scope.operation())
                .addKeyValue("organizationId", scope.organizationId())
                .addKeyValue("resourceId", id.apply(created))
                .log("Created control-plane resource");
        return new Creation<>(created, createdLocation, false);
    }

    private static Scope scope(TenantPrincipal principal, String operation, String path, String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.validation(
                    "/headers/Idempotency-Key", "Idempotency-Key must be 8 to 128 URL-safe ASCII characters.");
        }
        return new Scope(principal.organizationId(), principal.principalId(), operation, path, key);
    }

    private static String boundedText(String value, int maxLength, String field) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > maxLength) {
            throw ApiException.validation("/" + field, field + " is invalid.");
        }
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw ApiException.validation("/" + field, field + " contains a forbidden control character.");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw ApiException.validation("/" + field, field + " is not valid Unicode.");
        }
        return value;
    }

    private static String logicalPath(String value) {
        if (value == null || value.length() > 512 || !LOGICAL_PATH.matcher(value).matches()) {
            throw ApiException.validation("/logicalPath", "logicalPath must be a safe relative .feature path.");
        }
        return value;
    }

    private static String validateSource(String source) {
        try {
            return SourcePolicy.validateAndDigest(source);
        } catch (SourcePolicy.SourceTooLargeException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw ApiException.validation("/source", exception.getMessage());
        }
    }
}
