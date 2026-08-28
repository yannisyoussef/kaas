package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.kaas.api.controlplane.application.AdmissionRepository;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunSchedulingRepository;
import com.kaas.api.controlplane.application.RunSchedulingService;
import com.kaas.api.controlplane.application.RunTerminationService;
import com.kaas.api.controlplane.application.SchedulingControlRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.kaas.api.security.TenantPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant admission control and durable scheduler backoff.
 *
 * <p>Every test mints a fresh organization, so the per-organization counts are naturally isolated from each other
 * despite the shared context and database.
 */
@Testcontainers
@Import(AdmissionAndSchedulerHardeningTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            // No broker in this suite, and no claim: a consumer would find nothing and a reconciler would
            // have nothing to reconcile, but both would add background writes to assertions about state.
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            // Small, explicit ceilings so the boundary is reachable in a test.
            "kaas.admission.max-active-runs-per-organization=5",
            "kaas.admission.max-queued-runs-per-organization=2",
            "kaas.scheduling.backoff.max-failures=3",
            "kaas.scheduling.backoff.base-delay=PT30S",
            "kaas.scheduling.backoff.max-delay=PT10M",
            // Pinned: at jitter 1.0 the first and second delay ranges overlap and the growth assertion flakes.
            "kaas.scheduling.backoff.jitter=0.25",
            "kaas.scheduling.batch-size=20",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
class AdmissionAndSchedulerHardeningTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final int ACTIVE_LIMIT = 5;
    private static final int QUEUED_LIMIT = 2;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-admission");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PendingRunScheduler pendingRunScheduler;

    @Autowired
    private SchedulingControlRepository control;

    @Autowired
    private RunSchedulingRepository schedulingRepository;

    @Autowired
    private AdmissionRepository admission;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private RunTerminationService runTerminationService;


    /** Spied rather than mocked so scheduling really works unless a test deliberately breaks it. */
    @MockitoSpyBean
    private RunSchedulingService scheduler;

    /**
     * {@code scheduleDue()} is global, so a run left behind by one test is scheduled, deferred, or — when a test
     * stubs the scheduler to throw — actively poisoned by the next. Per-tenant assertions do not contain that:
     * the shared resources are the batch window and the meters. Each test therefore clears the run tables.
     */
    @AfterEach
    void removeThisTestsRuns() {
        reset(scheduler);
        // Child tables first, then parents. Every one of these is deliberately immutable and undeletable at
        // runtime, so the cleanup suspends their guards; that is a statement about the fixture, not about the
        // schema, and it is exactly why the deletion is confined to this @AfterEach.
        List<String> tables = List.of(
                "run_scheduling_control",
                "outbox_messages",
                "run_lifecycle_events",
                "execution_dispatches",
                "execution_attempts",
                "run_snapshot_features",
                "run_snapshot_configuration_entries",
                "run_snapshot_tags",
                "run_snapshot_artifact_types",
                "run_snapshots",
                "test_runs");
        tables.forEach(table -> jdbc.update("alter table " + table + " disable trigger all"));
        try {
            tables.forEach(table -> jdbc.update("delete from " + table));
        } finally {
            tables.forEach(table -> jdbc.update("alter table " + table + " enable trigger all"));
        }
    }

    // ---------------------------------------------------------------- admission

    @Test
    void anOrganizationMayHoldExactlyItsActiveRunCapacityAndNoMore() throws Exception {
        Tenant tenant = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(tenant).statusCode()).as("run %d", index).isEqualTo(202);
        }

        var rejected = createRun(tenant);

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode problem = json(rejected);
        assertThat(problem.get("code").stringValue()).isEqualTo("RUN_QUOTA_EXCEEDED");
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.hasNonNull("requestId")).isTrue();
        // The response must not disclose capacity, current usage, SQL, or anything about other tenants.
        assertThat(rejected.body())
                .doesNotContain(
                        "\"limit\"", "\"capacity\"", "\"activeRuns\"", "\"maxActiveRuns\"",
                        "count(", "select ", "organization_id", tenant.organizationId().toString());
        // No Retry-After: capacity frees when this organization's own runs complete, so no honest duration exists.
        assertThat(rejected.headers().firstValue("Retry-After")).isEmpty();
        assertThat(activeRuns(tenant)).isEqualTo(ACTIVE_LIMIT);
    }

    @Test
    void aSuccessfulIdempotentReplayStillSucceedsWhileTheOrganizationIsAtCapacity() throws Exception {
        Tenant tenant = tenant();
        String body = runRequestBody(tenant);
        String replayedKey = key();
        var first = post(runsPath(tenant), tenant.bearer(), replayedKey, body);
        assertThat(first.statusCode()).isEqualTo(202);
        for (int index = 1; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(tenant).statusCode()).isEqualTo(202);
        }
        assertThat(createRun(tenant).statusCode()).isEqualTo(429);

        // The replay creates no new work, so refusing it would punish a client for retrying safely.
        var replayed = post(runsPath(tenant), tenant.bearer(), replayedKey, body);

        assertThat(replayed.statusCode()).isEqualTo(202);
        assertThat(replayed.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(json(replayed).get("runId").stringValue()).isEqualTo(json(first).get("runId").stringValue());
        // A *new* key is new work and must still be refused.
        assertThat(post(runsPath(tenant), tenant.bearer(), key(), body).statusCode()).isEqualTo(429);
        assertThat(activeRuns(tenant)).isEqualTo(ACTIVE_LIMIT);
    }

    @Test
    void aQueuedRunStillOccupiesActiveCapacity() throws Exception {
        Tenant tenant = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(tenant).statusCode()).isEqualTo(202);
        }
        // Move some of them out of CREATED. Active capacity counts every state that is not complete, so this must
        // not free a slot; counting only CREATED and QUEUED would silently stop binding once CLAIMED lands.
        pendingRunScheduler.scheduleDue();
        assertThat(queuedRuns(tenant)).isEqualTo(QUEUED_LIMIT);

        assertThat(createRun(tenant).statusCode()).isEqualTo(429);
        assertThat(activeRuns(tenant)).isEqualTo(ACTIVE_LIMIT);
    }

    @Test
    void capacityIsPerOrganizationRatherThanPerProject() throws Exception {
        Tenant saturated = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(saturated).statusCode()).isEqualTo(202);
        }

        // A second project inside the SAME organization shares the ceiling. Without this, a per-project limit
        // would pass every other admission test identically.
        Tenant sameOrganization = tenantIn(saturated.organizationId(), saturated.bearer());
        assertThat(createRun(sameOrganization).statusCode()).isEqualTo(429);
    }

    @Test
    void oneOrganizationAtCapacityDoesNotAffectAnother() throws Exception {
        Tenant saturated = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(saturated).statusCode()).isEqualTo(202);
        }
        assertThat(createRun(saturated).statusCode()).isEqualTo(429);

        Tenant independent = tenant();
        assertThat(createRun(independent).statusCode()).isEqualTo(202);
        assertThat(activeRuns(independent)).isEqualTo(1);
    }

    @Test
    @Timeout(180)
    void twentyConcurrentCreatesCannotOvershootTheCapacity() throws Exception {
        Tenant tenant = tenant();
        // One below the ceiling, so exactly one of the concurrent requests may be admitted.
        for (int index = 0; index < ACTIVE_LIMIT - 1; index++) {
            assertThat(createRun(tenant).statusCode()).isEqualTo(202);
        }
        String body = runRequestBody(tenant);

        int callers = 20;
        var barrier = new CyclicBarrier(callers);
        try (var pool = Executors.newFixedThreadPool(callers)) {
            List<Integer> statuses = pool
                    .invokeAll(IntStream.range(0, callers)
                            .<java.util.concurrent.Callable<Integer>>mapToObj(index -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                return post(runsPath(tenant), tenant.bearer(), key(), body).statusCode();
                            })
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get(120, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent creation failed", exception);
                        }
                    })
                    .toList();

            // Without serialization every caller would observe the same pre-insert count and all would be
            // admitted, overshooting by nineteen.
            assertThat(statuses).filteredOn(status -> status == 202).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 429).hasSize(callers - 1);
        }
        assertThat(activeRuns(tenant)).isEqualTo(ACTIVE_LIMIT);
    }

    @Test
    void aRequestCannotSupplyItsOwnCapacity() throws Exception {
        Tenant tenant = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            assertThat(createRun(tenant).statusCode()).isEqualTo(202);
        }

        // The request body is closed, so an attempt to raise the ceiling is rejected outright rather than
        // silently ignored; the header form is ignored and the quota still applies.
        String smuggled = json(Map.of(
                "featureRevisionIds", List.of(tenant.featureRevisionId()),
                "runProfileRevisionId", tenant.profileRevisionId(),
                "maxActiveRuns", 10_000));
        assertThat(post(runsPath(tenant), tenant.bearer(), key(), smuggled).statusCode()).isEqualTo(400);

        HttpRequest header = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + runsPath(tenant)))
                .header("Authorization", "Bearer " + tenant.bearer())
                .header("Idempotency-Key", key())
                .header("Content-Type", "application/json")
                .header("X-Kaas-Max-Active-Runs", "10000")
                .POST(HttpRequest.BodyPublishers.ofString(runRequestBody(tenant), StandardCharsets.UTF_8))
                .build();
        assertThat(client.send(header, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- queue admission

    @Test
    void theSchedulerHoldsRunsAtCreatedOnceTheQueueCeilingIsReached() throws Exception {
        Tenant tenant = tenant();
        List<UUID> runIds = new ArrayList<>();
        for (int index = 0; index < QUEUED_LIMIT + 1; index++) {
            runIds.add(UUID.fromString(json(createRun(tenant)).get("runId").stringValue()));
        }

        pendingRunScheduler.scheduleDue();
        assertThat(queuedRuns(tenant)).isEqualTo(QUEUED_LIMIT);

        // The run beyond the ceiling stays exactly as it was: still CREATED, still version 1, and with none of
        // the durable execution intent that queueing would have produced.
        UUID deferred = runIds.get(QUEUED_LIMIT);
        assertThat(lifecycleOf(deferred)).isEqualTo("CREATED");
        assertThat(versionOf(deferred)).isEqualTo(1L);
        assertThat(count("execution_attempts", deferred)).isZero();
        assertThat(count("execution_dispatches", deferred)).isZero();
        assertThat(count("outbox_messages", deferred)).isZero();
        // Deferral is not a failure, so it must not accumulate failures or risk quarantine.
        Map<String, Object> controlRow = controlRow(deferred);
        assertThat(controlRow.get("failure_count")).isEqualTo(0);
        assertThat(controlRow.get("last_failure_code")).isEqualTo("QUEUE_CAPACITY");
        assertThat(controlRow.get("quarantined_at")).isNull();

        // Repeating the pass changes nothing while the queue is still full. Making the run due again first is
        // what makes this about the ceiling rather than about the backoff that follows a deferral.
        makeEligibleNow(deferred);
        pendingRunScheduler.scheduleDue();
        assertThat(queuedRuns(tenant)).isEqualTo(QUEUED_LIMIT);
        assertThat(lifecycleOf(deferred)).isEqualTo("CREATED");
    }

    @Test
    void aDeferredRunIsScheduledOnceCapacityFrees() throws Exception {
        Tenant tenant = tenant();
        List<UUID> runIds = new ArrayList<>();
        for (int index = 0; index < QUEUED_LIMIT + 1; index++) {
            runIds.add(UUID.fromString(json(createRun(tenant)).get("runId").stringValue()));
        }
        pendingRunScheduler.scheduleDue();
        UUID deferred = runIds.get(QUEUED_LIMIT);
        assertThat(lifecycleOf(deferred)).isEqualTo("CREATED");

        // Free one slot the way capacity is now actually freed: by cancelling a queued run. Until this slice
        // nothing could reach COMPLETED, and this test had to disable two triggers to fake it.
        completeQueuedRun(runIds.get(0));
        makeEligibleNow(deferred);

        pendingRunScheduler.scheduleDue();
        assertThat(lifecycleOf(deferred)).isEqualTo("QUEUED");
        assertThat(versionOf(deferred)).isEqualTo(2L);
        // Success removes the control row, so no stale eligibility can outlive the transition it gated.
        assertThat(count("run_scheduling_control", deferred)).isZero();
    }

    @Test
    @Timeout(180)
    void concurrentSchedulerReplicasCannotOvershootTheQueueCeiling() throws Exception {
        Tenant tenant = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            createRun(tenant);
        }

        int replicas = 4;
        var barrier = new CyclicBarrier(replicas);
        try (var pool = Executors.newFixedThreadPool(replicas)) {
            pool.invokeAll(IntStream.range(0, replicas)
                            .<java.util.concurrent.Callable<Integer>>mapToObj(index -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                return pendingRunScheduler.scheduleDue();
                            })
                            .toList())
                    .forEach(future -> {
                        try {
                            future.get(120, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent scheduling failed", exception);
                        }
                    });
        }

        assertThat(queuedRuns(tenant)).isEqualTo(QUEUED_LIMIT);
    }

    // ---------------------------------------------------------------- durable backoff

    @Test
    void aSchedulingFailurePersistsItsDelayAndSurvivesARestart() throws Exception {
        Tenant tenant = tenant();
        UUID runId = UUID.fromString(json(createRun(tenant)).get("runId").stringValue());
        doThrow(new org.springframework.dao.QueryTimeoutException("database busy"))
                .when(scheduler)
                .schedule(any(), any(), org.mockito.ArgumentMatchers.anyLong());

        pendingRunScheduler.scheduleDue();

        Map<String, Object> first = controlRow(runId);
        assertThat(first.get("failure_count")).isEqualTo(1);
        assertThat(first.get("last_failure_code")).isEqualTo("DATABASE_UNAVAILABLE");
        // The delay is computed by the recording statement itself. Base is 30s and jitter is pinned at 0.25, so
        // the first delay must land in [30s, 37.5s] - asserting the real curve rather than a Java copy of it.
        assertThat(delayOf(runId))
                .isBetween(Duration.ofSeconds(30), Duration.ofMillis(37_500));
        assertThat(first.get("quarantined_at")).isNull();
        assertThat(((java.sql.Timestamp) first.get("next_attempt_at")).toInstant())
                .isAfter(((java.sql.Timestamp) first.get("last_attempt_at")).toInstant());
        // The run itself is untouched: backoff is technical state and must never mutate the aggregate.
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
        assertThat(versionOf(runId)).isEqualTo(1L);

        // The delay is durable, so the run is not offered again in this process...
        assertThat(schedulingRepository.findSchedulable(50, QUEUED_LIMIT))
                .extracting(run -> run.runId())
                .doesNotContain(runId);
        // ...and the reason is durable rather than remembered: the eligibility decision above is made entirely
        // in SQL against the control row, so a process with no memory of this failure reaches the same answer.
        // The old in-memory cooldown lived in a map that a restart emptied.
        assertThat(jdbc.queryForObject(
                        "select count(*) from run_scheduling_control where run_id = ? and next_attempt_at > now()",
                        Long.class,
                        runId))
                .isEqualTo(1L);
        pendingRunScheduler.scheduleDue();
        assertThat(controlRow(runId).get("failure_count")).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
    }

    @Test
    void theDelayGrowsWithEachFailureAndQuarantinesOnceTheBudgetIsSpent() throws Exception {
        Tenant tenant = tenant();
        UUID runId = UUID.fromString(json(createRun(tenant)).get("runId").stringValue());
        doThrow(new org.springframework.dao.QueryTimeoutException("database busy"))
                .when(scheduler)
                .schedule(any(), any(), org.mockito.ArgumentMatchers.anyLong());

        pendingRunScheduler.scheduleDue();
        Duration firstDelay = delayOf(runId);
        makeEligibleNow(runId);
        pendingRunScheduler.scheduleDue();
        Duration secondDelay = delayOf(runId);

        assertThat(controlRow(runId).get("failure_count")).isEqualTo(2);
        assertThat(secondDelay).isGreaterThan(firstDelay);
        // Doubling, still jittered: [60s, 75s].
        assertThat(secondDelay).isBetween(Duration.ofSeconds(60), Duration.ofMillis(75_000));

        // The third failure spends the budget. The run is quarantined for an operator, not terminalized: it is
        // still CREATED with no outcome, because infrastructure trouble is not a verdict on a test run.
        makeEligibleNow(runId);
        pendingRunScheduler.scheduleDue();
        assertThat(controlRow(runId).get("failure_count")).isEqualTo(3);
        assertThat(controlRow(runId).get("quarantined_at")).isNotNull();
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
        assertThat(versionOf(runId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select test_outcome is null and infrastructure_outcome is null"
                                + " from test_runs where run_id = ?",
                        Boolean.class,
                        runId))
                .isTrue();

        // A quarantined run is withheld entirely: no hot loop, even once its delay has elapsed.
        makeEligibleNow(runId);
        assertThat(schedulingRepository.findSchedulable(50, QUEUED_LIMIT))
                .extracting(run -> run.runId())
                .doesNotContain(runId);
        pendingRunScheduler.scheduleDue();
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");

        // An operator recovers it by deleting the control row; nothing about the run needed repairing.
        reset(scheduler);
        assertThat(control.clear(runId)).isTrue();
        pendingRunScheduler.scheduleDue();
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    void aRunWhoseTrustedInputIsImpossibleIsQuarantinedImmediatelyRatherThanRetried() throws Exception {
        Tenant tenant = tenant();
        UUID runId = UUID.fromString(json(createRun(tenant)).get("runId").stringValue());
        doThrow(new IllegalArgumentException("trusted organization, run, and expected version are required"))
                .when(scheduler)
                .schedule(any(), any(), org.mockito.ArgumentMatchers.anyLong());

        pendingRunScheduler.scheduleDue();

        Map<String, Object> controlRow = controlRow(runId);
        assertThat(controlRow.get("failure_count")).isEqualTo(1);
        assertThat(controlRow.get("last_failure_code")).isEqualTo("INVALID_RUN_STATE");
        // Retrying something that cannot be valid is guaranteed waste, so it does not wait out the budget.
        assertThat(controlRow.get("quarantined_at")).isNotNull();
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
    }

    @Test
    void backoffStateOnlyEverDescribesARunSomethingStillIntendsToActOn() throws Exception {
        Tenant tenant = tenant();
        UUID runId = UUID.fromString(json(createRun(tenant)).get("runId").stringValue());
        pendingRunScheduler.scheduleDue();
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");

        // A QUEUED run is legitimate control state now: the queue-deadline reaper backs off through the same
        // table rather than growing a second retry framework, and it acts on runs that have already been queued.
        jdbc.update(
                """
                insert into run_scheduling_control (run_id, organization_id, project_id, failure_count,
                        next_attempt_at, last_attempt_at, last_failure_code)
                values (?, ?, ?, 1, now(), now(), 'DATABASE_UNAVAILABLE')
                """,
                runId, tenant.organizationId(), tenant.projectId());

        // Terminating the run must take that state with it. Nothing intends to act on a finished run, and a
        // leftover row would keep a quarantine visible for work that no longer exists.
        completeQueuedRun(runId);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
        assertThat(count("run_scheduling_control", runId)).isZero();

        // And the guard refuses to let it come back, so a stale writer cannot re-arm eligibility for a run that
        // is over.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into run_scheduling_control (run_id, organization_id, project_id, failure_count,
                                next_attempt_at, last_attempt_at, last_failure_code)
                        values (?, ?, ?, 1, now(), now(), 'STALE')
                        """,
                        runId, tenant.organizationId(), tenant.projectId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("only applies to a run awaiting scheduling or termination");
    }

    @Test
    void admissionAndSchedulerMetricsCarryNoTenantIdentity() throws Exception {
        Tenant tenant = tenant();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            createRun(tenant);
        }
        assertThat(createRun(tenant).statusCode()).isEqualTo(429);

        var rejected = meters.find("kaas.run.admission.rejected").counter();
        assertThat(rejected).isNotNull();
        assertThat(rejected.count()).isGreaterThanOrEqualTo(1.0);
        // Reason only. Tenant, project, and run identity would be unbounded label cardinality.
        assertThat(rejected.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactly("reason");
        assertThat(meters.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("kaas."))
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey)
                        .doesNotContain("organizationId", "projectId", "runId", "principalId", "messageId"));
    }

    // ---------------------------------------------------------------- helpers

    private void makeEligibleNow(UUID runId) {
        jdbc.update("update run_scheduling_control set next_attempt_at = now() where run_id = ?", runId);
    }

    /**
     * Frees capacity by cancelling a queued run through the implemented use case.
     *
     * <p>This used to disable {@code test_runs_supported_update} and {@code test_run_scheduling_bundle_complete}
     * and forge a COMPLETED row, because no implemented transition could leave QUEUED and the schema actively
     * forbade it. Both guards were rewritten in this slice, so the fake is gone: capacity is now released by the
     * same path a tenant uses.
     */
    private void completeQueuedRun(UUID runId) {
        Map<String, Object> run = jdbc.queryForMap(
                "select organization_id, created_by from test_runs where run_id = ?", runId);
        runTerminationService.cancel(
                new TenantPrincipal(String.valueOf(run.get("created_by")), (UUID) run.get("organization_id")),
                runId);
    }

    private Duration delayOf(UUID runId) {
        Map<String, Object> row = controlRow(runId);
        return Duration.between(
                ((java.sql.Timestamp) row.get("last_attempt_at")).toInstant(),
                ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant());
    }

    private Map<String, Object> controlRow(UUID runId) {
        return jdbc.queryForMap("select * from run_scheduling_control where run_id = ?", runId);
    }

    private String lifecycleOf(UUID runId) {
        return jdbc.queryForObject("select lifecycle_state from test_runs where run_id = ?", String.class, runId);
    }

    private long versionOf(UUID runId) {
        return jdbc.queryForObject("select run_version from test_runs where run_id = ?", Long.class, runId);
    }

    private int count(String table, UUID runId) {
        return jdbc.queryForObject("select count(*) from " + table + " where run_id = ?", Integer.class, runId);
    }

    private long activeRuns(Tenant tenant) {
        return jdbc.queryForObject(
                "select count(*) from test_runs where organization_id = ?"
                        + " and lifecycle_state <> 'COMPLETED'",
                Long.class,
                tenant.organizationId());
    }

    private long queuedRuns(Tenant tenant) {
        return jdbc.queryForObject(
                "select count(*) from test_runs where organization_id = ? and lifecycle_state = 'QUEUED'",
                Long.class,
                tenant.organizationId());
    }

    private record Tenant(
            UUID organizationId, UUID projectId, String bearer, String featureRevisionId,
            String profileRevisionId) {}

    private static String runsPath(Tenant tenant) {
        return "/api/v1/projects/" + tenant.projectId() + "/runs";
    }

    private HttpResponse<String> createRun(Tenant tenant) throws Exception {
        return post(runsPath(tenant), tenant.bearer(), key(), runRequestBody(tenant));
    }

    private String runRequestBody(Tenant tenant) throws Exception {
        return json(Map.of(
                "featureRevisionIds", List.of(tenant.featureRevisionId()),
                "runProfileRevisionId", tenant.profileRevisionId()));
    }

    /** Builds a fresh organization with the full authorized input chain a run needs. */
    private Tenant tenant() throws Exception {
        UUID organizationId = UUID.randomUUID();
        return tenantIn(organizationId, token(organizationId));
    }

    /** A second project inside an existing organization, for proving the ceiling is not per project. */
    private Tenant tenantIn(UUID organizationId, String bearer) throws Exception {
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Admission feature",
                                "logicalPath", "features/a-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: a\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Admission environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Admission profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = json(post(
                        "/api/v1/projects/" + projectId + "/run-profiles", bearer, key(), json(profile)))
                .at("/initialRevision/revisionId")
                .stringValue();
        return new Tenant(organizationId, UUID.fromString(projectId), bearer, featureRevision, profileRevision);
    }

    private HttpResponse<String> post(String path, String bearer, String idempotencyKey, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String token(UUID organizationId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("admission-test")
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("org_id", organizationId.toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean
        @Primary
        NimbusJwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
