package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.consumer.application.DispatchConsumptionService;
import com.kaas.api.consumer.application.DispatchMessage;
import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The runner's work intake (KAAS-DEPLOY-001): a worker claims delivered work for itself, over the internal API,
 * in the name its service token carries -- and in no other.
 *
 * <p>Driven over real HTTP, through the internal security chain, against a real database. The broker is not
 * needed here: a delivery is the consumer's recorded decision, and the consumption use case is invoked directly
 * with the exact bytes the relay would have published. What a real broker adds is proved in
 * {@code DispatchConsumerInboxTests}.
 */
@Testcontainers
@Import(WorkerClaimEndpointTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.admission.max-active-runs-per-organization=6",
            "kaas.admission.max-queued-runs-per-organization=6",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
class WorkerClaimEndpointTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String RUNNER_A = "kaas.worker.runner-a";
    private static final String RUNNER_B = "kaas.worker.runner-b";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-claim-endpoint");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PendingRunScheduler scheduler;

    @Autowired
    private DispatchConsumptionService consumption;

    @AfterEach
    void clearRuns() {
        for (String table : EVIDENCE_TABLES) {
            jdbc.update("alter table " + table + " disable trigger all");
        }
        try {
            for (String table : EVIDENCE_TABLES) {
                jdbc.update("delete from " + table);
            }
            jdbc.update("delete from api_idempotency_keys");
            jdbc.update("delete from worker_presence");
        } finally {
            for (String table : EVIDENCE_TABLES) {
                jdbc.update("alter table " + table + " enable trigger all");
            }
        }
    }

    private static final List<String> EVIDENCE_TABLES = List.of(
            "dispatch_inbox", "outbox_messages", "run_lifecycle_events", "execution_dispatches",
            "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    // ---------------------------------------------------------------- the claim

    @Test
    void aWorkerClaimsDeliveredWorkInItsOwnNameAndLearnsOnlyWhatItNeedsToBegin() throws Exception {
        UUID runId = deliveredRun();

        HttpResponse<String> response = claim(RUNNER_A, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode body = objectMapper.readTree(response.body());
        // Exactly three fields: the run, the attempt, the fencing token. No capability, secret, credential,
        // image or tenant-selected option -- those arrive, if at all, through the execution authorization.
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("runId", "attemptId", "assignmentEpoch");
        assertThat(body.get("runId").stringValue()).isEqualTo(runId.toString());
        assertThat(body.get("attemptId").stringValue()).isEqualTo(attemptOf(runId).toString());
        assertThat(body.get("assignmentEpoch").intValue()).isEqualTo(1);

        // The existing claim, unchanged: QUEUED to CLAIMED, one version, one event, a lease, in A's name.
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        Map<String, Object> attempt = jdbc.queryForMap("select * from execution_attempts where run_id = ?", runId);
        assertThat(attempt.get("attempt_state")).isEqualTo("CLAIMED");
        assertThat(attempt.get("assigned_worker_id")).isEqualTo(RUNNER_A);
        assertThat(attempt.get("assignment_epoch")).isEqualTo(1);
        assertThat(attempt.get("lease_expires_at")).isNotNull();
    }

    @Test
    void runnerBCannotAlsoClaimTheRunRunnerAHolds() throws Exception {
        UUID runId = deliveredRun();
        assertThat(claim(RUNNER_A, null).statusCode()).isEqualTo(200);

        assertThat(claim(RUNNER_B, null).statusCode()).isEqualTo(204);
        assertThat(claim(RUNNER_A, null).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject(
                        "select assigned_worker_id from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo(RUNNER_A);
        assertThat(versionOf(runId)).isEqualTo(3L);
    }

    @Test
    @Timeout(120)
    void underContentionOneRunHasExactlyOneOwner() throws Exception {
        UUID runId = deliveredRun();
        int contenders = 12;
        CyclicBarrier start = new CyclicBarrier(contenders);
        var executor = Executors.newFixedThreadPool(contenders);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        try {
            List<CompletableFuture<Void>> all = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                String worker = "kaas.worker.contender-" + index;
                all.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        statuses.add(claim(worker, null).statusCode());
                    } catch (Exception failed) {
                        throw new IllegalStateException(failed);
                    }
                }, executor));
            }
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).hasSize(contenders);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 204).count()).isEqualTo(contenders - 1);
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
    }

    @Test
    void twoDeliveredRunsGoToTwoWorkersRatherThanQueueingBehindOneLock() throws Exception {
        UUID first = deliveredRun();
        UUID second = deliveredRun();

        JsonNode a = objectMapper.readTree(claim(RUNNER_A, null).body());
        JsonNode b = objectMapper.readTree(claim(RUNNER_B, null).body());

        assertThat(List.of(a.get("runId").stringValue(), b.get("runId").stringValue()))
                .containsExactlyInAnyOrder(first.toString(), second.toString());
    }

    // ---------------------------------------------------------------- identity

    @Test
    void aWorkerIdInTheBodyIsRefusedAndCannotChooseWhoseNameTheClaimIsWrittenIn() throws Exception {
        UUID runId = deliveredRun();

        // B presents its own token and asks for the claim to be written in A's name.
        HttpResponse<String> impersonation = claim(RUNNER_B, "{\"workerId\":\"" + RUNNER_A + "\"}");

        assertThat(impersonation.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(impersonation.body()).get("code").stringValue()).isEqualTo("UNKNOWN_FIELD");
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");

        // And B claiming normally writes B, whatever A is doing.
        assertThat(claim(RUNNER_B, "{}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select assigned_worker_id from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo(RUNNER_B);
    }

    @Test
    void onlyAnAuthenticatedWorkerIdentityMayClaim() throws Exception {
        UUID runId = deliveredRun();

        // No token, a tenant's token, a token for a platform service outside the worker namespace, the egress
        // proxy's own narrowly scoped identity, and a token that is both a service and a tenant.
        assertThat(send("/internal/v1/assignments", null, "{}").statusCode()).isEqualTo(401);
        assertThat(send("/internal/v1/assignments", token("tenant-user", UUID.randomUUID()), "{}").statusCode())
                .isEqualTo(401);
        assertThat(send("/internal/v1/assignments", token("kaas.scheduler", null), "{}").statusCode())
                .isEqualTo(403);
        assertThat(send("/internal/v1/assignments", token("kaas.egress-proxy", null), "{}").statusCode())
                .isEqualTo(403);
        assertThat(send("/internal/v1/assignments", token(RUNNER_A, UUID.randomUUID()), "{}").statusCode())
                .isEqualTo(401);
        // Expired credentials are refused: the refreshable identity is only as good as its expiry.
        assertThat(send("/internal/v1/assignments", expiredToken(RUNNER_A), "{}").statusCode()).isEqualTo(401);

        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from worker_presence", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- what is claimable

    @Test
    void aRunTheBrokerNeverDeliveredCannotBeClaimed() throws Exception {
        // Scheduled and published to the outbox, but no delivery recorded: RabbitMQ is still the delivery path,
        // and nothing here lets a worker reach a run the broker never handed over.
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();

        assertThat(claim(RUNNER_A, null).statusCode()).isEqualTo(204);
        assertThat(await(RUNNER_A, 0).statusCode()).isEqualTo(204);
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    void aDeliveredRunThatWasCancelledIsNotOffered() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(consumption.consume(deliveryFor(runId))).isEqualTo(InboxDisposition.DELIVERED);
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        assertThat(claim(RUNNER_A, null).statusCode()).isEqualTo(204);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
    }

    // ---------------------------------------------------------------- the long poll

    @Test
    @Timeout(60)
    void theWaitReturnsAsSoonAsWorkIsDeliveredAndClaimsNothing() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage delivery = deliveryFor(runId);

        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return await(RUNNER_A, 15_000);
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        Thread.sleep(1_000);
        assertThat(waiting).isNotDone();
        assertThat(consumption.consume(delivery)).isEqualTo(InboxDisposition.DELIVERED);

        HttpResponse<String> answered = waiting.get(10, TimeUnit.SECONDS);
        assertThat(answered.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        // Waiting is not owning: abandoning a wait can never leave a claim behind.
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("WAITING_FOR_CLAIM");
    }

    @Test
    @Timeout(60)
    void anIdleWaitIsBoundedAndAnswersEmpty() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = await(RUNNER_A, 1_500);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1_400)).isLessThan(Duration.ofSeconds(10));
        // The worker was seen, which is what the deployment check counts.
        assertThat(jdbc.queryForObject(
                        "select count(*) from worker_presence where worker_id = ?", Integer.class, RUNNER_A))
                .isEqualTo(1);
    }

    @Test
    void aWaitBeyondTheServerBoundOrOfTheWrongShapeIsRefused() throws Exception {
        assertThat(send("/internal/v1/assignments/waits", token(RUNNER_A, null), "{\"waitMillis\":20001}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("/internal/v1/assignments/waits", token(RUNNER_A, null), "{\"waitMillis\":-1}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("/internal/v1/assignments/waits", token(RUNNER_A, null), "{\"waitMillis\":\"10\"}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("/internal/v1/assignments/waits", token(RUNNER_A, null),
                        "{\"waitMillis\":0,\"workerId\":\"kaas.worker.other\"}")
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void theInternalIntakeIsAbsentFromThePublicContract() throws Exception {
        String contract = java.nio.file.Files.readString(
                java.nio.file.Path.of(System.getProperty("user.dir")).resolve("../../docs/api/openapi-v1.yaml")
                        .normalize());
        assertThat(contract).doesNotContain("/internal/").doesNotContain("assignments");
    }

    // ---------------------------------------------------------------- helpers

    private UUID deliveredRun() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(consumption.consume(deliveryFor(runId))).isEqualTo(InboxDisposition.DELIVERED);
        return runId;
    }

    /** The bytes the relay would publish for this run's dispatch, under the identity it would publish them. */
    private DispatchMessage deliveryFor(UUID runId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select message_id, payload::text as payload from execution_dispatches where run_id = ?", runId);
        return new DispatchMessage(
                (UUID) row.get("message_id"), "EXECUTION_DISPATCH", "1.0",
                ((String) row.get("payload")).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> claim(String worker, String body) throws Exception {
        return send("/internal/v1/assignments", token(worker, null), body == null ? "{}" : body);
    }

    private HttpResponse<String> await(String worker, long waitMillis) throws Exception {
        return send("/internal/v1/assignments/waits", token(worker, null), "{\"waitMillis\":" + waitMillis + "}");
    }

    private HttpResponse<String> send(String path, String bearer, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String expiredToken(String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(900)))
                .notBeforeTime(Date.from(now.minusSeconds(900)))
                .expirationTime(Date.from(now.minusSeconds(300)));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
    }

    private ExecutionDispatch dispatchFor(UUID runId) throws Exception {
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId).get("payload"));
        return objectMapper.readValue(payload, ExecutionDispatch.class);
    }

    private static ExecutionDispatch withOrganization(ExecutionDispatch dispatch, UUID organizationId) {
        return new ExecutionDispatch(
                dispatch.schemaVersion(), dispatch.messageId(), dispatch.messageType(), dispatch.dispatchId(),
                dispatch.occurredAt(), dispatch.producer(), organizationId, dispatch.projectId(), dispatch.runId(),
                dispatch.runVersion(), dispatch.attemptId(), dispatch.attemptNumber(), dispatch.runSnapshotId(),
                dispatch.runSnapshotDigest(), dispatch.queueDeadlineAt(), dispatch.payloadDigest());
    }

    private static ExecutionDispatch withMessageId(ExecutionDispatch dispatch, UUID messageId) {
        return new ExecutionDispatch(
                dispatch.schemaVersion(), messageId, dispatch.messageType(), dispatch.dispatchId(),
                dispatch.occurredAt(), dispatch.producer(), dispatch.organizationId(), dispatch.projectId(),
                dispatch.runId(), dispatch.runVersion(), dispatch.attemptId(), dispatch.attemptNumber(),
                dispatch.runSnapshotId(), dispatch.runSnapshotDigest(), dispatch.queueDeadlineAt(),
                dispatch.payloadDigest());
    }

    private static ExecutionDispatch withAttempt(ExecutionDispatch dispatch, UUID attemptId) {
        return new ExecutionDispatch(
                dispatch.schemaVersion(), dispatch.messageId(), dispatch.messageType(), dispatch.dispatchId(),
                dispatch.occurredAt(), dispatch.producer(), dispatch.organizationId(), dispatch.projectId(),
                dispatch.runId(), dispatch.runVersion(), attemptId, dispatch.attemptNumber(),
                dispatch.runSnapshotId(), dispatch.runSnapshotDigest(), dispatch.queueDeadlineAt(),
                dispatch.payloadDigest());
    }

    private void assertRejected(String reason, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(reason);
    }

    private UUID attemptOf(UUID runId) {
        return jdbc.queryForObject("select attempt_id from execution_attempts where run_id = ?", UUID.class, runId);
    }

    private Instant leaseExpiry(UUID runId) {
        return jdbc.queryForObject(
                        "select lease_expires_at from execution_attempts where run_id = ?",
                        java.sql.Timestamp.class, runId)
                .toInstant();
    }

    private String stopReasonOf(UUID runId) {
        return jdbc.queryForObject("select stop_reason from test_runs where run_id = ?", String.class, runId);
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

    private HttpResponse<String> heartbeat(UUID runId, UUID attemptId, int epoch, String bearer) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs/" + runId
                                + "/attempts/" + attemptId + "/heartbeat"))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"assignmentEpoch\":" + epoch + "}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> cancel(String bearer, UUID runId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + "/api/v1/runs/" + runId + "/cancellations"))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"reason\":\"USER_REQUESTED\"}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private UUID createRun(Tenant tenant) throws Exception {
        HttpResponse<String> response = create(tenant);
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(json(response).get("runId").stringValue());
    }

    private HttpResponse<String> create(Tenant tenant) throws Exception {
        return post(
                "/api/v1/projects/" + tenant.projectId() + "/runs",
                tenant.bearer(),
                key(),
                json(Map.of(
                        "featureRevisionIds", List.of(tenant.featureRevisionId()),
                        "runProfileRevisionId", tenant.profileRevisionId())));
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

    private record Tenant(
            UUID organizationId, UUID projectId, String bearer, String featureRevisionId,
            String profileRevisionId) {}

    private Tenant tenant() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = tenantToken(organizationId);
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Claim feature",
                                "logicalPath", "features/c-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: a\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Claim environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Claim profile");
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

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String tenantToken(UUID organizationId) throws Exception {
        return token("claim-test", organizationId);
    }

    /** A platform service credential: reserved subject, and deliberately no organization at all. */
    private static String serviceToken(String subject) throws Exception {
        return token(subject, null);
    }

    /** Both at once, which must be refused rather than resolved in the caller's favour. */
    private static String hybridToken(String subject, UUID organizationId) throws Exception {
        return token(subject, organizationId);
    }

    private static String token(String subject, UUID organizationId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)));
        if (organizationId != null) {
            claims.claim("org_id", organizationId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
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
