package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunSchedulingService;
import com.kaas.api.controlplane.application.RunTerminationService;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.controlplane.domain.ScheduleDisposition;
import com.kaas.api.outbox.application.DispatchPublisher;
import com.kaas.api.outbox.application.OutboxRelay;
import com.kaas.api.outbox.application.OutboxRepository;
import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.PublishOutcome;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Publication failure behaviour and the production scheduler, with no broker present at all.
 *
 * <p>The publisher is stubbed here because a NACK, a confirm timeout, and a broker outage cannot be produced
 * reliably against a healthy container. Everything downstream of the stub — retry scheduling, attempt accounting,
 * terminal disposition, and the database guards — is real. The absence of a broker also proves the design
 * decision that a RabbitMQ outage must not degrade the control plane.
 */
@Testcontainers
@Import(RelayFailureAndSchedulerIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            // No broker in this suite, and no claim: a consumer would find nothing and a reconciler would
            // have nothing to reconcile, but both would add background writes to assertions about state.
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.outbox.relay.max-attempts=3",
            "kaas.outbox.relay.base-backoff=PT10S",
            "kaas.outbox.relay.max-backoff=PT40S",
            "kaas.outbox.relay.claim-ttl=PT30S",
            // claim-ttl must exceed batch-size x confirm-timeout, which the relay validates at startup.
            "kaas.outbox.relay.batch-size=5",
            "kaas.scheduling.batch-size=3",
            // Without details the health body is almost empty and any "does not leak" assertion is vacuous.
            "management.endpoint.health.show-details=always"
        })
class RelayFailureAndSchedulerIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-relay-failure");

    /** No RabbitMQ container exists in this suite: the transport edge is the only thing replaced. */
    @MockitoBean
    private DispatchPublisher publisher;

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private PendingRunScheduler pendingRunScheduler;

    @Autowired
    private RunSchedulingService scheduler;

    @Autowired
    private RunTerminationService terminations;

    /**
     * These tests share one context, one database, and one broker-less relay, and the relay batch is not scoped
     * to the run under test. Every test therefore starts from an empty pending set.
     */
    @org.junit.jupiter.api.BeforeEach
    void drainEverythingPending() {
        drainSchedulable();
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set terminal_disposition = 'PERMANENT_FAILURE',"
                        + " last_failure_code = 'TEST_ISOLATION', publish_attempts = greatest(publish_attempts, 1),"
                        + " last_attempt_at = now(), relay_claim_id = null, relay_claimed_at = null,"
                        + " relay_claim_expires_at = null"
                        + " where published_at is null and terminal_disposition is null"));
        assertThat(outbox.countPending()).isZero();
    }

    @Test
    void aTransientFailureDefersTheMessageWithGrowingBackoffAndNeverPublishesIt() throws Exception {
        UUID runId = queuedRun();
        when(publisher.publish(any())).thenReturn(PublishOutcome.transientFailure(FailureCode.PUBLISH_NACKED));

        assertThat(relay.drainOnce()).isZero();

        Map<String, Object> first = outboxRow(runId);
        assertThat(first.get("published_at")).isNull();
        assertThat(first.get("terminal_disposition")).isNull();
        assertThat(first.get("publish_attempts")).isEqualTo(1);
        assertThat(first.get("last_failure_code")).isEqualTo(FailureCode.PUBLISH_NACKED);
        assertThat(first.get("relay_claim_id")).isNull();
        Instant firstAvailable = ((Timestamp) first.get("available_at")).toInstant();
        // Availability moved into the future, so the row is not immediately reselected.
        assertThat(firstAvailable).isAfter(((Timestamp) first.get("occurred_at")).toInstant());
        assertThat(relay.drainOnce()).isZero();
        assertThat(outboxRow(runId).get("publish_attempts")).isEqualTo(1);

        // Make it due again; the second backoff must be strictly longer than the first.
        makeAvailableNow(runId);
        assertThat(relay.drainOnce()).isZero();
        Map<String, Object> second = outboxRow(runId);
        assertThat(second.get("publish_attempts")).isEqualTo(2);
        Instant secondAvailable = ((Timestamp) second.get("available_at")).toInstant();
        Instant secondAttempt = ((Timestamp) second.get("last_attempt_at")).toInstant();
        assertThat(java.time.Duration.between(secondAttempt, secondAvailable))
                .isGreaterThan(java.time.Duration.between(
                        ((Timestamp) first.get("last_attempt_at")).toInstant(), firstAvailable));
    }

    @Test
    void exhaustingTheAttemptBudgetTerminatesTheMessageAndRemovesItFromThePendingSet() throws Exception {
        UUID runId = queuedRun();
        when(publisher.publish(any()))
                .thenReturn(PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE));

        for (int attempt = 0; attempt < 3; attempt++) {
            makeAvailableNow(runId);
            relay.drainOnce();
        }

        Map<String, Object> row = outboxRow(runId);
        assertThat(row.get("publish_attempts")).isEqualTo(3);
        assertThat(row.get("terminal_disposition")).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(row.get("last_failure_code")).isEqualTo(FailureCode.BROKER_UNAVAILABLE);
        assertThat(row.get("published_at")).isNull();
        // The relay-side dead letter is retained, not deleted, and is never selected again. Scoped to this run:
        // a delta against a global count would be perturbed by any other test in the class.
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages where run_id = ?"
                                + " and published_at is null and terminal_disposition is null",
                        Long.class,
                        runId))
                .isZero();
        makeAvailableNow(runId);
        assertThat(relay.drainOnce()).isZero();
        assertThat(outboxRow(runId).get("publish_attempts")).isEqualTo(3);
    }

    @Test
    void aPermanentPublisherFailureIsTerminalOnTheFirstAttempt() throws Exception {
        UUID runId = queuedRun();
        when(publisher.publish(any())).thenReturn(PublishOutcome.permanentFailure(FailureCode.UNROUTABLE));

        assertThat(relay.drainOnce()).isZero();

        Map<String, Object> row = outboxRow(runId);
        assertThat(row.get("terminal_disposition")).isEqualTo("PERMANENT_FAILURE");
        assertThat(row.get("last_failure_code")).isEqualTo(FailureCode.UNROUTABLE);
        assertThat(row.get("publish_attempts")).isEqualTo(1);
    }

    @Test
    void aPublisherThatThrowsIsTreatedAsTransientRatherThanLosingTheMessage() throws Exception {
        UUID runId = queuedRun();
        when(publisher.publish(any())).thenThrow(new IllegalStateException("connection reset"));

        assertThat(relay.drainOnce()).isZero();

        Map<String, Object> row = outboxRow(runId);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("terminal_disposition")).isNull();
        assertThat(row.get("last_failure_code")).isEqualTo(FailureCode.BROKER_UNAVAILABLE);
        assertThat(row.get("publish_attempts")).isEqualTo(1);
    }

    @Test
    void theControlPlaneStaysAvailableAndHealthyWhileTheBrokerIsNot() throws Exception {
        UUID runId = queuedRun();
        when(publisher.publish(any()))
                .thenReturn(PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE));
        relay.drainOnce();

        // No broker exists in this suite at all, yet reads and health are unaffected.
        var run = get("/api/v1/runs/" + runId, token(organizationOf(runId)));
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(json(run).get("lifecycleState").stringValue()).isEqualTo("QUEUED");

        var health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        var readiness = get("/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        // The backlog is genuinely observable...
        JsonNode relayHealth = json(health).at("/components/outboxRelay");
        assertThat(relayHealth.get("status").stringValue()).isEqualTo("UP");
        assertThat(relayHealth.at("/details").propertyNames())
                .containsExactlyInAnyOrder("pending", "terminal", "oldestPendingAgeSeconds");
        // ...without exposing broker host, credentials, or topology anywhere in the body.
        assertThat(health.body())
                .doesNotContain("rabbit", "amqp", "5672", "password", "kaas-local-only", "kaas.dispatch");
    }

    @Test
    void theSchedulerHonoursItsBatchBoundAndStableOrdering() throws Exception {
        List<UUID> created = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            created.add(createRun());
        }

        // The batch size is three, so one pass cannot take all five.
        assertThat(pendingRunScheduler.scheduleDue()).isEqualTo(3);
        // Ordering is by creation time, so the first pass must have taken the three OLDEST runs.
        assertThat(lifecycleOf(created.subList(0, 3))).containsOnly("QUEUED");
        assertThat(lifecycleOf(created.subList(3, 5))).containsOnly("CREATED");
        assertThat(pendingRunScheduler.scheduleDue()).isEqualTo(2);
        assertThat(pendingRunScheduler.scheduleDue()).isZero();
        assertThat(lifecycleOf(created)).containsOnly("QUEUED");
    }

    @Test
    @Timeout(120)
    void concurrentSchedulerInstancesNeverScheduleTheSameRunTwice() throws Exception {
        drainSchedulable();
        int runs = 3;
        for (int index = 0; index < runs; index++) {
            createRun();
        }

        List<UUID> created = jdbc.queryForList(
                "select run_id from test_runs where lifecycle_state = 'CREATED'"
                        + " and cancellation_status = 'NOT_REQUESTED'",
                UUID.class);
        assertThat(created).hasSize(runs);

        int instances = 4;
        var barrier = new CyclicBarrier(instances);
        try (var pool = Executors.newFixedThreadPool(instances)) {
            List<Integer> scheduled = pool
                    .invokeAll(IntStream.range(0, instances)
                            .<java.util.concurrent.Callable<Integer>>mapToObj(index -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                return pendingRunScheduler.scheduleDue();
                            })
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get(60, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent scheduling failed", exception);
                        }
                    })
                    .toList();

            // Compare-and-set means the replicas share the work rather than duplicating it.
            assertThat(scheduled.stream().mapToInt(Integer::intValue).sum()).isEqualTo(runs);
        }
        // Scoped to the runs this test created: another test deliberately leaves a cancelled run CREATED.
        assertThat(lifecycleOf(created)).containsOnly("QUEUED");
        // Exactly one attempt, dispatch, and outbox message per run: no duplicate execution intent.
        for (UUID runId : created) {
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_attempts where run_id = ?", Long.class, runId))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_dispatches where run_id = ?", Long.class, runId))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                            "select count(*) from outbox_messages where run_id = ?", Long.class, runId))
                    .isEqualTo(1L);
        }
    }

    @Test
    void theSchedulerNeverTouchesARunThatIsNotAnUncancelledCreatedRun() throws Exception {
        drainSchedulable();
        UUID queued = queuedRun();
        long versionBefore = jdbc.queryForObject(
                "select run_version from test_runs where run_id = ?", Long.class, queued);

        assertThat(pendingRunScheduler.scheduleDue()).isZero();
        assertThat(jdbc.queryForObject("select run_version from test_runs where run_id = ?", Long.class, queued))
                .isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_attempts where run_id = ?", Long.class, queued))
                .isEqualTo(1L);
    }

    @Test
    void anIdempotentReplayReportsTheRunAsItIsNowRatherThanAsItWasCreated() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token(organizationId);
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        String body = runRequestBody(bearer, projectId);
        String idempotencyKey = key();

        var created = post("/api/v1/projects/" + projectId + "/runs", bearer, idempotencyKey, body);
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(created.headers().firstValue("ETag")).contains("\"run-1\"");
        UUID runId = UUID.fromString(json(created).get("runId").stringValue());
        long snapshotsBefore = jdbc.queryForObject("select count(*) from run_snapshots", Long.class);

        assertThat(pendingRunScheduler.scheduleDue()).isEqualTo(1);

        // The replay must describe the resource as it is now. Reconstructing the CREATED view would advertise
        // ETag "run-1" for a representation the origin will never serve again.
        var replayed = post("/api/v1/projects/" + projectId + "/runs", bearer, idempotencyKey, body);
        assertThat(replayed.statusCode()).isEqualTo(202);
        assertThat(replayed.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(replayed.headers().firstValue("Location"))
                .isEqualTo(created.headers().firstValue("Location"));
        assertThat(replayed.headers().firstValue("ETag")).contains("\"run-2\"");
        JsonNode replayedRun = json(replayed);
        assertThat(replayedRun.get("runId").stringValue()).isEqualTo(runId.toString());
        assertThat(replayedRun.get("lifecycleState").stringValue()).isEqualTo("QUEUED");
        assertThat(replayedRun.get("runVersion").asInt()).isEqualTo(2);
        // It agrees with the canonical read, so one resource never advertises two strong validators.
        var fetched = get("/api/v1/runs/" + runId, bearer);
        assertThat(fetched.body()).isEqualTo(replayed.body());
        assertThat(fetched.headers().firstValue("ETag")).isEqualTo(replayed.headers().firstValue("ETag"));
        // And no second run or snapshot was created.
        assertThat(jdbc.queryForObject("select count(*) from run_snapshots", Long.class))
                .isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForObject(
                        "select count(*) from test_runs where project_id = ?", Long.class,
                        UUID.fromString(projectId)))
                .isEqualTo(1L);
    }

    @Test
    void aCancelledRunIsNeverScheduledAndNeverDispatchesAnything() throws Exception {
        UUID runId = createRun();
        UUID organizationId = organizationOf(runId);
        // This test used to forge CREATED + REQUESTED with the lifecycle guard disabled, because no cancellation
        // existed. It now cancels for real, and the forged state is unreachable: cancelling unowned work is
        // immediate, so a CREATED run that has been asked to stop is already over.
        terminations.cancel(new TenantPrincipal(principalOf(runId), organizationId), runId);

        assertThat(pendingRunScheduler.scheduleDue()).isZero();

        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select run_version from test_runs where run_id = ?", Long.class, runId))
                .isEqualTo(2L);
        // Nothing was ever dispatched: no attempt, no dispatch, and above all no durable broker message for work
        // that will never run.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_attempts where run_id = ?", Long.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages where run_id = ?", Long.class, runId))
                .isZero();
        // The service refuses it too, so the repository predicate is not the only line of defence.
        assertThat(scheduler.schedule(organizationId, runId, 1).disposition())
                .isEqualTo(ScheduleDisposition.INVALID_STATE);

        // And the intermediate state the old fixture forged is now rejected by the database itself, even with
        // the lifecycle guard out of the way: a cancellation status without a request time is not a state.
        UUID other = createRun();
        assertThatThrownBy(() -> withTestRunGuardDisabled(() -> jdbc.update(
                        "update test_runs set cancellation_status = 'REQUESTED' where run_id = ?", other)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_test_runs_cancellation_timing");
    }

    private List<String> lifecycleOf(List<UUID> runIds) {
        return runIds.stream()
                .map(runId -> jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .toList();
    }

    private void withGuardDisabled(Runnable work) {
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            work.run();
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }
    }

    private void withTestRunGuardDisabled(Runnable work) {
        jdbc.update("alter table test_runs disable trigger test_runs_supported_update");
        try {
            work.run();
        } finally {
            jdbc.update("alter table test_runs enable trigger test_runs_supported_update");
        }
    }

    /** Schedules everything currently CREATED so a test starts from a known baseline. */
    private void drainSchedulable() {
        for (int pass = 0; pass < 25 && pendingRunScheduler.scheduleDue() > 0; pass++) {
            // Each pass takes one bounded batch.
        }
    }

    private void makeAvailableNow(UUID runId) {
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set available_at = now() where run_id = ?", runId));
    }

    private Map<String, Object> outboxRow(UUID runId) {
        return jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
    }

    private String principalOf(UUID runId) {
        return jdbc.queryForObject("select created_by from test_runs where run_id = ?", String.class, runId);
    }

    private UUID organizationOf(UUID runId) {
        return jdbc.queryForObject(
                "select organization_id from test_runs where run_id = ?", UUID.class, runId);
    }

    private UUID queuedRun() throws Exception {
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();
        return runId;
    }

    private UUID createRun() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token(organizationId);
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        var response = post(
                "/api/v1/projects/" + projectId + "/runs", bearer, key(), runRequestBody(bearer, projectId));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(json(response).get("runId").stringValue());
    }

    /** Builds the full authorized input chain and returns the create-run request body for that project. */
    private String runRequestBody(String bearer, String projectId) throws Exception {
        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Failure feature",
                                "logicalPath", "features/f-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: f\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Failure environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Failure profile");
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
        return json(Map.of(
                "featureRevisionIds", List.of(featureRevision),
                "runProfileRevisionId", profileRevision));
    }

    private HttpResponse<String> post(String path, String bearer, String idempotencyKey, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                .subject("relay-failure-test")
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
