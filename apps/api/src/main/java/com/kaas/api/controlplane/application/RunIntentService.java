package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.application.IdempotencyRepository.Scope;
import com.kaas.api.controlplane.domain.AdmissionPolicy;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.RunSnapshotPolicy;
import com.kaas.api.controlplane.domain.RunSnapshotPolicy.DuplicateFeatureSelectionException;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final AdmissionRepository admission;
    private final AdmissionPolicy admissionPolicy;
    private final Clock clock;
    private final EngineDescriptor engine;
    private final Counter rejectedRuns;

    public RunIntentService(
            RunIntentRepository runs,
            ConfigurationRepository configuration,
            IdempotencyRepository idempotency,
            AdmissionRepository admission,
            MeterRegistry meters,
            Clock clock,
            @Value("${kaas.engine.karate-version}") String karateVersion,
            @Value("${kaas.admission.max-active-runs-per-organization}") int maxActiveRuns,
            @Value("${kaas.admission.max-queued-runs-per-organization}") int maxQueuedRuns) {
        this.runs = runs;
        this.configuration = configuration;
        this.idempotency = idempotency;
        this.admission = admission;
        this.admissionPolicy = new AdmissionPolicy(maxActiveRuns, maxQueuedRuns);
        this.clock = clock;
        this.engine = new EngineDescriptor("KARATE", karateVersion);
        // Dimensioned by reason only. Tenant, project, and run identity would be unbounded label cardinality.
        this.rejectedRuns = Counter.builder("kaas.run.admission.rejected")
                .tag("reason", "ACTIVE_RUN_CAPACITY")
                .register(meters);
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
            // The replay returns the CURRENT canonical representation rather than a reconstructed CREATED one.
            // Rebuilding it would advertise ETag "run-1" for a run the origin now serves as "run-2", putting two
            // strong validators in circulation for one resource. Durable identity, Location, and the original
            // creation semantics are unchanged, and no second run or snapshot is created.
            TestRun stored = runs.findRun(principal.organizationId(), record.resourceId())
                    .orElseThrow(ApiException::notFound);
            return new Creation<>(stored, record.location(), true);
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
        // Admission is checked only on this path, after the replay above has already returned. A successful
        // replay must keep working when the organization is at its ceiling: it creates no new work, and failing
        // it would make a retry-safe client worse off than one that never retried. A new key is new work and
        // obeys the current policy.
        //
        // The lock is taken here rather than earlier so the critical section is the decision plus the write, not
        // the revision lookups and snapshot materialisation before them. Every request blocked on this lock holds
        // a pooled connection, so a long critical section would let one busy organization exhaust the pool and
        // stall reads for every other tenant. It is still taken after the per-key idempotency lock, and only ever
        // in that order, so concurrent creates cannot deadlock. Holding it across the count and the insert is
        // what makes the count decisive: twenty simultaneous requests at the limit would otherwise each observe
        // the same pre-insert count and all be admitted.
        admission.lockOrganization(principal.organizationId());
        if (!admissionPolicy.admitsAnotherActiveRun(admission.countActiveRuns(principal.organizationId()))) {
            rejectedRuns.increment();
            LOGGER.atWarn()
                    .addKeyValue("event", "RUN_ADMISSION_REJECTED")
                    .addKeyValue("organizationId", principal.organizationId())
                    .addKeyValue("projectId", projectId)
                    .addKeyValue("reason", "ACTIVE_RUN_CAPACITY")
                    .log("Refused a run that would exceed the organization's active capacity");
            throw ApiException.tooManyRequests(
                    "RUN_QUOTA_EXCEEDED",
                    "This organization already holds the maximum number of runs that are not yet complete.");
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
