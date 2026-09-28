package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.consumer.application.DispatchConsumptionService;
import com.kaas.api.consumer.application.DispatchMessage;
import com.kaas.api.consumer.application.WorkerAssignmentService;
import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.QueueDeadlineReaper;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.outbox.application.OutboxRelay;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEASURES what happens to a dispatch the broker loses after accepting it (KAAS-DEPLOY-001, Decision B).
 *
 * <p>This is a measurement of a known gap, not a proof of recovery, and it must not be read as one. The
 * property it establishes is narrow:
 *
 * <pre>
 *   LOSS IS DETECTED THROUGH THE QUEUE DEADLINE AND FAILS CLOSED.
 *   THE LOST WORK IS NOT RECONSTRUCTED.
 * </pre>
 *
 * <p>Once the relay records a dispatch as published, that publication is final: nothing republishes it. If the
 * broker then loses the message, the run stays QUEUED -- durably, visibly -- until its queue deadline, and the
 * reaper ends it TIMED_OUT / QUEUE_DEADLINE. That is honest and observable. It is also the loss of a run a tenant
 * asked for. Reconstructing unclaimed lost dispatches from PostgreSQL is KAAS-MSG-001, deliberately not done here.
 *
 * <h2>Why the loss is real</h2>
 *
 * <p>Skipping publication and watching the deadline fire would prove only that deadlines fire. So the order is
 * the point: the relay publishes with a broker confirm and records it; the broker is observed HOLDING the message;
 * the broker's copy is then destroyed; and only then is the absence of any claim, and the deadline, observed.
 *
 * <p>The production consumer is off so the message cannot be consumed before it is lost -- the window a broker
 * failure opens in production, held open deterministically.
 */
@Testcontainers
@Import(BrokerLossMeasurementTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.outbox.rabbit.confirm-timeout=PT10S",
            // A shortened queue deadline. The production value is minutes; the behaviour does not depend on it.
            "kaas.scheduling.queue-timeout=PT4S"
        })
class BrokerLossMeasurementTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String RUNNER = "kaas.worker.broker-loss";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-broker-loss");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${kaas.outbox.rabbit.queue}")
    private String queue;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PendingRunScheduler scheduler;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private QueueDeadlineReaper reaper;

    @Autowired
    private DispatchConsumptionService consumption;

    @Autowired
    private WorkerAssignmentService assignments;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin rabbitAdmin;

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
        } finally {
            for (String table : EVIDENCE_TABLES) {
                jdbc.update("alter table " + table + " enable trigger all");
            }
        }
        rabbitAdmin.purgeQueue(queue, false);
    }

    private static final List<String> EVIDENCE_TABLES = List.of(
            "dispatch_inbox", "outbox_messages", "run_lifecycle_events", "execution_dispatches",
            "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    @Test
    @Timeout(120)
    void aPublishedDispatchTheBrokerLosesIsNeverRebuiltAndTheRunEndsAtItsQueueDeadline() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();

        // 1. PUBLISHED, and recorded as published only after the broker confirmed it.
        assertThat(relay.drainOnce()).isEqualTo(1);
        Map<String, Object> outbox = outboxRowFor(runId);
        assertThat(outbox.get("published_at")).as("the relay recorded the publication").isNotNull();

        // 2. THE BROKER HELD IT. Observed, not assumed: the message is in the queue.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 1);
        int depthBeforeLoss = depthOf(queue);

        // 3. LOST. The broker's only copy is destroyed, as a broker failure without durable state would.
        rabbitAdmin.purgeQueue(queue, false);
        int depthAfterLoss = depthOf(queue);
        assertThat(depthAfterLoss).isZero();

        // 4. NOTHING REBUILDS IT. The relay does not republish -- its record says the work is done -- and no
        //    delivery was ever recorded, so no worker can claim the run. It sits QUEUED, durably and visibly.
        int republished = relay.drainOnce() + relay.drainOnce();
        assertThat(republished).isZero();
        assertThat(outboxRowFor(runId).get("published_at")).isEqualTo(outbox.get("published_at"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from dispatch_inbox where run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(assignments.workAvailable(RUNNER)).isFalse();
        assertThat(assignments.claimNext(RUNNER)).isEmpty();
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("WAITING_FOR_CLAIM");

        // 5. THE DEADLINE ENDS IT. Before it passes the reaper leaves the run alone; after, it terminates it.
        assertThat(reaper.reapExpired()).isZero();
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select queue_deadline_at < clock_timestamp() from test_runs where run_id = ?",
                        Boolean.class, runId)));
        assertThat(reaper.reapExpired()).isEqualTo(1);

        Map<String, Object> run = jdbc.queryForMap("select * from test_runs where run_id = ?", runId);
        assertThat(run.get("lifecycle_state")).isEqualTo("COMPLETED");
        assertThat(run.get("infrastructure_outcome")).isEqualTo("TIMED_OUT");
        assertThat(run.get("termination_reason")).isEqualTo("QUEUE_DEADLINE");
        // Terminal is terminal: a late copy of the message, if the broker somehow produced one, is stale.
        assertThat(assignments.claimNext(RUNNER)).isEmpty();
        // And nothing anywhere is left silently waiting.
        assertThat(jdbc.queryForObject(
                        "select count(*) from test_runs where lifecycle_state = 'QUEUED'", Integer.class))
                .isZero();

        writeEvidence(Map.of(
                "published_before_loss", "true",
                "broker_depth_before_loss", Integer.toString(depthBeforeLoss),
                "broker_depth_after_loss", Integer.toString(depthAfterLoss),
                "republished_after_loss", Integer.toString(republished),
                "claims_after_loss", "0",
                "terminal_outcome", run.get("infrastructure_outcome") + "/" + run.get("termination_reason"),
                "queue_deadline_fail_closed", "true",
                // THE FINDING. Not "VALID", not "recovered": the work was lost.
                "rabbitmq_loss_recovery", "false",
                "follow_up", "KAAS-MSG-001"));
    }

    @Test
    @Timeout(120)
    void theSamePathWithTheMessageIntactIsDeliveredAndClaimable() throws Exception {
        // The control. Identical up to the loss, and the message survives: the broker delivers it, the consumer
        // records it, and a worker claims it. Without this, the measurement above could pass on a path that
        // never delivered anything to begin with.
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(relay.drainOnce()).isEqualTo(1);

        Message delivered = rabbit.receive(queue, 10_000);
        assertThat(delivered).isNotNull();
        assertThat(consumption.consume(new DispatchMessage(
                        UUID.fromString(delivered.getMessageProperties().getMessageId()),
                        String.valueOf(delivered.getMessageProperties().getHeaders().get("messageType")),
                        String.valueOf(delivered.getMessageProperties().getHeaders().get("schemaVersion")),
                        delivered.getBody())))
                .isEqualTo(InboxDisposition.DELIVERED);

        assertThat(assignments.workAvailable(RUNNER)).isTrue();
        assertThat(assignments.claimNext(RUNNER)).hasValueSatisfying(
                assignment -> assertThat(assignment.runId()).isEqualTo(runId));
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
    }

    private Map<String, Object> outboxRowFor(UUID runId) {
        return jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
    }

    private int depthOf(String name) {
        var properties = rabbitAdmin.getQueueProperties(name);
        return properties == null ? 0 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    /** Structured evidence the deployment-readiness gate prints and archives. Keys are sorted; values are words. */
    private static void writeEvidence(Map<String, String> values) throws Exception {
        // The directory the BUILD names, as for every gate's evidence. A run without one fails rather than
        // writing somewhere the gate would never look.
        String configured = System.getProperty("kaas.evidence.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("kaas.evidence.dir is set by the deploymentReadinessTest task.");
        }
        Path directory = Path.of(configured);
        Files.createDirectories(directory);
        StringBuilder text = new StringBuilder();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        Files.writeString(directory.resolve("broker-loss.properties"), text.toString());
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
