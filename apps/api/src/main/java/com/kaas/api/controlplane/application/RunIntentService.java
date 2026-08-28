package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.application.IdempotencyRepository.Scope;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.RunSnapshotPolicy;
import com.kaas.api.controlplane.domain.RunSnapshotPolicy.DuplicateFeatureSelectionException;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunIntentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunIntentService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{8,128}");

    private final RunIntentRepository runs;
    private final ConfigurationRepository configuration;
    private final IdempotencyRepository idempotency;
    private final Clock clock;
    private final EngineDescriptor engine;

    public RunIntentService(
            RunIntentRepository runs,
            ConfigurationRepository configuration,
            IdempotencyRepository idempotency,
            Clock clock,
            @Value("${kaas.engine.karate-version}") String karateVersion) {
        this.runs = runs;
        this.configuration = configuration;
        this.idempotency = idempotency;
        this.clock = clock;
        this.engine = new EngineDescriptor("KARATE", karateVersion);
    }

    @Transactional
    public Creation<TestRun> create(
            TenantPrincipal principal,
            UUID projectId,
            String idempotencyKey,
            List<UUID> featureRevisionIds,
            UUID runProfileRevisionId) {
        if (featureRevisionIds == null
                || featureRevisionIds.isEmpty()
                || featureRevisionIds.size() > 1000
                || featureRevisionIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw ApiException.validation("/featureRevisionIds", "Select between 1 and 1000 feature revisions.");
        }
        if (runProfileRevisionId == null) {
            throw ApiException.validation("/runProfileRevisionId", "Select one exact RunProfile revision.");
        }
        Set<UUID> distinctRevisionIds = new HashSet<>(featureRevisionIds);
        if (distinctRevisionIds.size() != featureRevisionIds.size()) {
            throw ApiException.validation("/featureRevisionIds", "Feature revision IDs must be unique.");
        }
        requireProject(principal, projectId);
        Scope scope = scope(principal, projectId, idempotencyKey);
        String canonicalFeatureIds = distinctRevisionIds.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining("\n"));
        String fingerprint = RequestFingerprint.of(
                "createRun",
                projectId.toString(),
                runProfileRevisionId.toString(),
                Integer.toString(distinctRevisionIds.size()),
                canonicalFeatureIds);

        idempotency.lock(scope);
        var existing = idempotency.find(scope);
        if (existing.isPresent()) {
            var record = existing.orElseThrow();
            if (!record.requestSha256().equals(fingerprint)) {
                throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different request.");
            }
            TestRun stored = runs.findRun(principal.organizationId(), record.resourceId())
                    .orElseThrow(ApiException::notFound);
            TestRun original = TestRun.created(
                    stored.runId(), stored.projectId(), stored.snapshotDigest(), stored.createdBy(), stored.createdAt());
            return new Creation<>(original, record.location(), true);
        }

        var profile = configuration
                .findRunProfileRevisionById(principal.organizationId(), projectId, runProfileRevisionId)
                .orElseThrow(ApiException::notFound);
        var environment = configuration
                .findEnvironmentRevisionById(principal.organizationId(), projectId, profile.environmentRevisionId())
                .orElseThrow(ApiException::notFound);
        var features = runs.findFeatureRevisions(principal.organizationId(), projectId, distinctRevisionIds);
        if (features.size() != distinctRevisionIds.size()) {
            throw ApiException.notFound();
        }

        UUID runId = UUID.randomUUID();
        RunSnapshot snapshot;
        try {
            snapshot = RunSnapshotPolicy.materialize(runId, projectId, features, environment, profile, engine);
        } catch (DuplicateFeatureSelectionException exception) {
            throw ApiException.validation(
                    "/featureRevisionIds", "Only one revision of each feature and logical path may be selected.");
        }
        Instant now = clock.instant();
        TestRun run = TestRun.created(runId, projectId, snapshot.snapshotDigest(), principal.principalId(), now);
        runs.insert(principal.organizationId(), run, snapshot);
        String location = "/api/v1/runs/" + runId;
        idempotency.insert(scope, fingerprint, runId, 202, location, now);
        LOGGER.atInfo()
                .addKeyValue("event", "RUN_CREATED")
                .addKeyValue("organizationId", principal.organizationId())
                .addKeyValue("projectId", projectId)
                .addKeyValue("runId", runId)
                .log("Persisted test run intent");
        return new Creation<>(run, location, false);
    }

    @Transactional(readOnly = true)
    public TestRun get(TenantPrincipal principal, UUID runId) {
        return runs.findRun(principal.organizationId(), runId).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResult<TestRun> list(TenantPrincipal principal, UUID projectId, int page, int size) {
        requireProject(principal, projectId);
        return runs.listRuns(principal.organizationId(), projectId, page, size);
    }

    @Transactional(readOnly = true)
    public RunSnapshot snapshot(TenantPrincipal principal, UUID runId) {
        return runs.findSnapshot(principal.organizationId(), runId).orElseThrow(ApiException::notFound);
    }

    private void requireProject(TenantPrincipal principal, UUID projectId) {
        if (!configuration.projectExists(principal.organizationId(), projectId)) {
            throw ApiException.notFound();
        }
    }

    private static Scope scope(TenantPrincipal principal, UUID projectId, String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.validation(
                    "/headers/Idempotency-Key", "Idempotency-Key must be 8 to 128 URL-safe ASCII characters.");
        }
        return new Scope(
                principal.organizationId(), principal.principalId(), "createRun", "/projects/" + projectId + "/runs", key);
    }
}
