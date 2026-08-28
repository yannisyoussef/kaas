package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.kaas.api.consumer.application.DispatchConsumptionService;
import com.kaas.api.consumer.application.DispatchInboxRepository;
import com.kaas.api.consumer.application.DispatchMessage;
import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.controlplane.application.PendingRunScheduler;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.amqp.core.MessageProperties;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * The transport boundary, against a real broker: what a delivered message is allowed to do, and what happens the
 * second, third, and hostile times it arrives.
 *
 * <p>The production consumer is enabled here, because the property being proved is that a run really does travel
 * PostgreSQL to RabbitMQ to a claim with nobody driving it. Where a test needs a specific delivery it publishes
 * one itself and drives the consumption use case directly, so the assertion does not depend on whichever
 * consumer thread happened to win.
 */
@Testcontainers
@Import(DispatchConsumerInboxTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            // Explicitly on. The shipped default is off until a heartbeating worker exists, but this suite's
            // whole subject is what the production consumer does with a real delivery.
            "kaas.consumer.enabled=true",
            // Reconciliation stays *enabled*, because running the consumer without it is a configuration the
            // application now refuses to start in — claimed runs would hold admission capacity with nothing able
            // to release them. Its timer is simply parked beyond the life of the suite so no background pass can
            // fence a run mid-assertion.
            "kaas.claim.reconcile.initial-delay=PT1H",
            "kaas.outbox.relay.enabled=false",
            "kaas.outbox.relay.batch-size=5",
            "kaas.outbox.rabbit.confirm-timeout=PT10S",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.consumer.transient-backoff=PT0S"
        })
class DispatchConsumerInboxTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-consumer");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${kaas.outbox.rabbit.queue}")
    private String queue;

    @Value("${kaas.outbox.rabbit.dead-letter-queue}")
    private String deadLetterQueue;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PendingRunScheduler scheduler;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DispatchConsumptionService consumption;

    /** Spied rather than mocked, so consumption really works unless a test deliberately breaks it. */
    @MockitoSpyBean
    private DispatchInboxRepository inbox;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * The relay and the scheduler both act globally, and the consumer runs on its own threads. Without this, a
     * run left behind by one test is published and consumed inside another, and an assertion about one message's
     * delivery count silently becomes an assertion about the whole class's history.
     */
    @AfterEach
    void clearRuns() {
        reset(inbox);
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
        // Drain both queues so the next test starts from an empty broker. Draining only the dispatch queue left
        // every dead-letter assertion dependent on which test happened to run first, which is an assertion about
        // JUnit's method ordering rather than about the system.
        for (String name : List.of(queue, deadLetterQueue)) {
            while (rabbit.receive(name, 200) != null) {
                // discard
            }
        }
    }

    private static final List<String> EVIDENCE_TABLES = List.of(
            "dispatch_inbox", "outbox_messages", "run_lifecycle_events", "execution_dispatches",
            "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    @Test
    @Timeout(180)
    void aRunTravelsFromPostgresThroughRabbitToAClaimWithNobodyDrivingIt() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();

        // The relay publishes; the production consumer does the rest on its own thread.
        assertThat(relay.drainOnce()).isEqualTo(1);

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "CLAIMED".equals(lifecycleOf(runId)));

        Map<String, Object> attempt = jdbc.queryForMap("select * from execution_attempts where run_id = ?", runId);
        assertThat(attempt.get("attempt_state")).isEqualTo("CLAIMED");
        assertThat(attempt.get("assignment_epoch")).isEqualTo(1);
        // The worker identity is the server's configuration, never anything the message carried.
        assertThat(attempt.get("assigned_worker_id")).isEqualTo("kaas.worker.local");

        Map<String, Object> inbox = jdbc.queryForMap("select * from dispatch_inbox where run_id = ?", runId);
        assertThat(inbox.get("disposition")).isEqualTo("CLAIMED");
        assertThat(inbox.get("delivery_count")).isEqualTo(1);
        assertThat(inbox.get("consumer")).isEqualTo("kaas.dispatch-consumer");

        // Acknowledged — observed positively. Queue depth alone would prove nothing: it counts *ready* messages,
        // so a delivered-but-unacknowledged message is already absent from it and the assertion is satisfied from
        // the moment of delivery, before the transaction has even committed. The count that distinguishes
        // "acknowledged" from "still in flight" is the unacknowledged one.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> unacknowledged(queue) == 0 && depthOf(queue) == 0);
        assertThat(depthOf(deadLetterQueue)).isZero();
    }

    @Test
    @Timeout(180)
    void aFailureBeforeTheDecisionIsDurableReturnsTheWorkToTheBrokerRatherThanLosingIt() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // Fail once, inside the transaction, after the claim has been attempted but before the decision is
        // durable. If the listener acknowledged before committing, this message would be gone; because it
        // acknowledges only after, the broker still owns it and redelivers.
        doThrow(new IllegalStateException("simulated failure before the decision was durable"))
                .doCallRealMethod()
                .when(inbox)
                .record(any());

        assertThat(relay.drainOnce()).isEqualTo(1);

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "CLAIMED".equals(lifecycleOf(runId)));

        // The work survived the failure and was done exactly once.
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from dispatch_inbox where run_id = ?", Integer.class, runId))
                .isEqualTo(1);
        // Acknowledged in the end, and never dead-lettered: nothing was wrong with the message.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> unacknowledged(queue) == 0 && depthOf(queue) == 0);
        assertThat(depthOf(deadLetterQueue)).isZero();
    }

    @Test
    @Timeout(180)
    void aRealBrokerRedeliveryIsAbsorbedRatherThanClaimedTwice() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage delivered = deliveryFor(runId);
        assertThat(relay.drainOnce()).isEqualTo(1);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "CLAIMED".equals(lifecycleOf(runId)));

        // Exactly what the broker does after a consumer dies between commit and acknowledgement: the same bytes
        // under the same identity, arriving again through the real listener rather than an in-process loop.
        publishRaw(
                delivered.transportMessageId(), "EXECUTION_DISPATCH", "1.0",
                new String(delivered.body(), StandardCharsets.UTF_8));

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                                "select delivery_count from dispatch_inbox where run_id = ?",
                                Integer.class, runId)
                        == 2);
        // One claim, one version, one event — and the redelivery acknowledged, not dead-lettered.
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> unacknowledged(queue) == 0 && depthOf(queue) == 0);
        assertThat(depthOf(deadLetterQueue)).isZero();
    }

    @Test
    void aRedeliveryOfTheSameMessageIsADecidedNoOpRatherThanASecondClaim() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage delivery = deliveryFor(runId);

        assertThat(consumption.consume(delivery)).isEqualTo(InboxDisposition.CLAIMED);
        long claimedVersion = versionOf(runId);

        // This is the crash window: the database committed and the broker was never acknowledged, so RabbitMQ
        // redelivers. The inbox is what turns that into a no-op instead of a second claim.
        assertThat(consumption.consume(delivery)).isEqualTo(InboxDisposition.CLAIMED);
        assertThat(consumption.consume(delivery)).isEqualTo(InboxDisposition.CLAIMED);

        assertThat(versionOf(runId)).isEqualTo(claimedVersion);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from dispatch_inbox where run_id = ?", Integer.class, runId))
                .isEqualTo(1);
        // The decision is untouched; only the count of times the broker offered it moves.
        Map<String, Object> inbox = jdbc.queryForMap("select * from dispatch_inbox where run_id = ?", runId);
        assertThat(inbox.get("delivery_count")).isEqualTo(3);
        assertThat(inbox.get("disposition")).isEqualTo("CLAIMED");
        assertThat(inbox.get("last_received_at")).isNotNull();
    }

    @Test
    void aKnownIdentityCarryingDifferentBytesIsAnIntegrityConflictAndNotADuplicate() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage delivery = deliveryFor(runId);
        assertThat(consumption.consume(delivery)).isEqualTo(InboxDisposition.CLAIMED);
        String recordedDigest = jdbc.queryForObject(
                "select payload_digest from dispatch_inbox where run_id = ?", String.class, runId);

        // Same message identity, different content. Not a duplicate — two different messages claiming to be one.
        // The replacement is whitespace-tolerant because the payload comes back through jsonb, which reformats
        // it: a literal match would silently do nothing and the test would pass on an untampered body.
        String tampered = retarget(new String(delivery.body(), StandardCharsets.UTF_8), "attemptNumber", "2");
        assertThat(tampered).isNotEqualTo(new String(delivery.body(), StandardCharsets.UTF_8));
        assertThat(consumption.consume(new DispatchMessage(
                        delivery.transportMessageId(), "EXECUTION_DISPATCH", "1.0",
                        tampered.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(InboxDisposition.CONFLICT);

        // The recorded decision is not overwritten to accommodate the newcomer.
        Map<String, Object> inbox = jdbc.queryForMap("select * from dispatch_inbox where run_id = ?", runId);
        assertThat(inbox.get("payload_digest")).isEqualTo(recordedDigest);
        assertThat(inbox.get("disposition")).isEqualTo("CLAIMED");
        assertThat(versionOf(runId)).isEqualTo(3L);
    }

    @Test
    void aMessageThatCannotBeUnderstoodOrTrustedIsPermanentlyRefusedForItsOwnReason() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage valid = deliveryFor(runId);
        String body = new String(valid.body(), StandardCharsets.UTF_8);

        // Every case carries the identity its body claims, so no earlier check can fire and answer for it, and
        // each reason is read back keyed by that identity. Asserting only "REJECTED" — or asserting the set of
        // reasons rather than the mapping — lets an input be refused for a reason that has nothing to do with
        // what it was testing, which is exactly how the strict-unknown-property check came to prove nothing.
        assertRefused("{not json", "MALFORMED_PAYLOAD");
        assertRefused(body, "1.0", "EXECUTION_DISPATCH", "\u00ff\u00fe", "MALFORMED_ENCODING");
        assertRefused(body, "2.0", "EXECUTION_DISPATCH", null, "UNSUPPORTED_SCHEMA_VERSION");
        assertRefused(body, "1.0", "RUN_STATE_CHANGED", null, "UNSUPPORTED_MESSAGE_TYPE");
        assertRefused("{\"x\":\"" + "a".repeat(70_000) + "\"}", "BODY_TOO_LARGE");

        // Jackson drops unknown properties by default, which would let a field the digest does not cover ride
        // along to a more trusting reader. This is the only test of that, so it must be the reason that fires.
        String extended = body.replaceFirst("\\{", "{\"executeAsRoot\": true,");
        assertThat(extended).isNotEqualTo(body);
        assertRefused(extended, "UNKNOWN_PROPERTY");

        // Tampering has to actually change the bytes: the payload arrives through jsonb, which reformats it.
        String retargeted = retarget(body, "runVersion", "3");
        assertThat(retargeted).isNotEqualTo(body);
        assertRefused(retargeted, "DIGEST_MISMATCH");

        // And a transport identity that disagrees with the body is a reason to disbelieve the message.
        UUID foreign = UUID.randomUUID();
        assertThat(consumption.consume(delivery(foreign, "EXECUTION_DISPATCH", "1.0", body)))
                .isEqualTo(InboxDisposition.REJECTED);
        assertThat(reasonFor(foreign)).isEqualTo("TRANSPORT_IDENTITY_MISMATCH");

        // Nothing was claimed by any of them.
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("WAITING_FOR_CLAIM");
        // A message refused by the validator records no tenant identity, because the only source for one was the
        // payload it just refused to believe.
        assertThat(jdbc.queryForObject(
                        "select count(*) from dispatch_inbox where disposition = 'REJECTED'"
                                + " and reason <> 'UNKNOWN_DISPATCH'"
                                + " and (organization_id is not null or run_id is not null)",
                        Integer.class))
                .isZero();
    }

    @Test
    void aWellFormedDispatchThisControlPlaneNeverProducedIsRefusedRatherThanClaimed() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage real = deliveryFor(runId);

        // Syntactically perfect and correctly self-digested, but minted by somebody else: its identity resolves
        // to no dispatch this control plane ever wrote. Every other refusal in this class is a validator
        // refusal; this is the one that has to be caught by corroboration against the durable record.
        Map<String, Object> forged = objectMapper.readValue(
                new String(real.body(), StandardCharsets.UTF_8), Map.class);
        UUID foreignMessageId = UUID.randomUUID();
        forged.put("messageId", foreignMessageId.toString());
        forged.put("payloadDigest", "sha256:" + "b".repeat(64));
        String reserialized = objectMapper.writeValueAsString(forged);

        // It fails the digest first, which is the honest answer for a body whose digest does not cover it.
        assertThat(consumption.consume(delivery(foreignMessageId, "EXECUTION_DISPATCH", "1.0", reserialized)))
                .isEqualTo(InboxDisposition.REJECTED);
        assertThat(reasonFor(foreignMessageId)).isEqualTo("DIGEST_MISMATCH");
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    void theInboxDecisionItselfIsEvidenceAndCannotBeRewrittenOrDeleted() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(consumption.consume(deliveryFor(runId))).isEqualTo(InboxDisposition.CLAIMED);

        // Deleting a decision turns the next redelivery of that message back into an undecided one — which is a
        // second claim. The guard is load-bearing and was previously asserted nowhere.
        assertRejected(
                "inbox decisions are retained as consumption evidence",
                "delete from dispatch_inbox where run_id = ?", runId);
        assertRejected(
                "only redelivery accounting may change an inbox decision",
                "update dispatch_inbox set disposition = 'STALE' where run_id = ?", runId);
        assertRejected(
                "only redelivery accounting may change an inbox decision",
                "update dispatch_inbox set payload_digest = 'raw:' || repeat('a', 64) where run_id = ?", runId);
        assertRejected(
                "a new inbox record is its own first delivery",
                """
                insert into dispatch_inbox (inbox_id, consumer, message_id, payload_digest, organization_id,
                        project_id, run_id, disposition, reason, first_received_at, last_received_at,
                        decided_at, delivery_count)
                values (gen_random_uuid(), 'other-consumer', gen_random_uuid(), 'raw:' || repeat('c', 64), null, null,
                        null, 'REJECTED', 'SEEDED', now(), now(), now(), 7)
                """);
    }

    @Test
    void aDispatchForWorkThatIsAlreadyOverIsStaleAndIsNeverClaimed() throws Exception {
        Tenant cancelled = tenant();
        UUID cancelledRun = createRun(cancelled);
        scheduler.scheduleDue();
        DispatchMessage cancelledDelivery = deliveryFor(cancelledRun);
        assertThat(cancel(cancelled.bearer(), cancelledRun).statusCode()).isEqualTo(200);

        assertThat(consumption.consume(cancelledDelivery)).isEqualTo(InboxDisposition.STALE);

        assertThat(lifecycleOf(cancelledRun)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?",
                        String.class, cancelledRun))
                .isEqualTo("WAITING_FOR_CLAIM");
        Map<String, Object> inbox = jdbc.queryForMap(
                "select * from dispatch_inbox where run_id = ?", cancelledRun);
        assertThat(inbox.get("disposition")).isEqualTo("STALE");
        // A stale message is expected distributed-system behaviour, so it keeps its tenant identity for audit
        // and is acknowledged rather than dead-lettered.
        assertThat(inbox.get("organization_id")).isNotNull();
    }

    @Test
    @Timeout(180)
    void aRefusedMessageIsDeadLetteredWhileAStaleOneIsSimplyAcknowledged() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        DispatchMessage valid = deliveryFor(runId);
        int deadLettersBefore = depthOf(deadLetterQueue);

        // Published straight at the queue so the production consumer picks it up: garbage that no redelivery can
        // fix must not loop forever, and must end up somewhere an operator can look.
        publishRaw(UUID.randomUUID(), "EXECUTION_DISPATCH", "1.0", "{\"broken\":");

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> depthOf(deadLetterQueue) > deadLettersBefore);
        assertThat(depthOf(queue)).isZero();

        // A stale delivery, by contrast, is ordinary. It is acknowledged and never dead-lettered — filling an
        // operator queue with expected races would bury the real failures.
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);
        int deadLettersAfterRejection = depthOf(deadLetterQueue);
        publishRaw(
                valid.transportMessageId(), "EXECUTION_DISPATCH", "1.0",
                new String(valid.body(), StandardCharsets.UTF_8));

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> jdbc.queryForObject(
                                "select count(*) from dispatch_inbox where run_id = ? and disposition = 'STALE'",
                                Integer.class, runId)
                        == 1);
        assertThat(depthOf(deadLetterQueue)).isEqualTo(deadLettersAfterRejection);
        assertThat(depthOf(queue)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private DispatchMessage deliveryFor(UUID runId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select message_id, payload from execution_dispatches where run_id = ?", runId);
        return new DispatchMessage(
                (UUID) row.get("message_id"),
                "EXECUTION_DISPATCH",
                "1.0",
                String.valueOf(row.get("payload")).getBytes(StandardCharsets.UTF_8));
    }

    private void assertRefused(String body, String expectedReason) throws Exception {
        assertRefused(body, "1.0", "EXECUTION_DISPATCH", null, expectedReason);
    }

    /**
     * Consumes one refusable body under its own fresh identity and asserts the reason recorded for exactly that
     * identity, so no other case in the test can answer for it.
     */
    private void assertRefused(
            String body, String version, String type, String rawOverride, String expectedReason) throws Exception {
        UUID messageId = UUID.randomUUID();
        byte[] raw = rawOverride == null
                ? body.getBytes(StandardCharsets.UTF_8)
                : new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd};
        assertThat(consumption.consume(new DispatchMessage(messageId, type, version, raw)))
                .as(expectedReason)
                .isEqualTo(InboxDisposition.REJECTED);
        assertThat(reasonFor(messageId)).as(expectedReason).isEqualTo(expectedReason);
    }

    private String reasonFor(UUID messageId) {
        return jdbc.queryForObject(
                "select reason from dispatch_inbox where message_id = ?", String.class, messageId);
    }

    private void assertRejected(String reason, String sql, Object... args) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, args))
                .as(sql)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining(reason);
    }

    /** Replaces a numeric JSON field's value regardless of how jsonb chose to space the document. */
    private static String retarget(String body, String field, String value) {
        return body.replaceAll("\"" + field + "\"\\s*:\\s*\\d+", "\"" + field + "\": " + value);
    }

    private static DispatchMessage delivery(UUID messageId, String type, String version, String body) {
        return new DispatchMessage(messageId, type, version, body.getBytes(StandardCharsets.UTF_8));
    }

    private void publishRaw(UUID messageId, String type, String version, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId == null ? null : messageId.toString());
        properties.setHeader("messageType", type);
        properties.setHeader("schemaVersion", version);
        properties.setContentType("application/json");
        rabbit.send(queue, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /**
     * Messages the broker has delivered and not had acknowledged, read from the management API.
     *
     * <p>This is the only count that separates "the consumer finished with it" from "the consumer is still
     * holding it". Queue depth cannot: it counts *ready* messages, so a delivered-but-unacknowledged message is
     * already absent from it and any assertion built on depth alone is satisfied from the moment of delivery —
     * before the transaction has even committed.
     */
    private int unacknowledged(String name) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + "/api/queues/%2F/" + name))
                        .header(
                                "Authorization",
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString((RABBIT.getAdminUsername() + ":"
                                                                + RABBIT.getAdminPassword())
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "The management API is required to observe acknowledgement: " + response.statusCode());
        }
        var node = objectMapper.readTree(response.body()).get("messages_unacknowledged");
        // The management plugin omits the counters until its statistics collector has run. Absence means "not
        // known yet", never "zero" — reporting zero here would reintroduce exactly the vacuity this helper
        // exists to remove, so callers poll until the broker actually answers.
        return node == null ? UNKNOWN : node.asInt();
    }

    private static final int UNKNOWN = -1;

    private int depthOf(String name) {
        var properties = rabbitAdmin.getQueueProperties(name);
        return properties == null ? 0 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
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

    private UUID createRun(Tenant tenant) throws Exception {
        HttpResponse<String> response = post(
                "/api/v1/projects/" + tenant.projectId() + "/runs",
                tenant.bearer(),
                key(),
                json(Map.of(
                        "featureRevisionIds", List.of(tenant.featureRevisionId()),
                        "runProfileRevisionId", tenant.profileRevisionId())));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(json(response).get("runId").stringValue());
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
        String bearer = token(organizationId);
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Consumer feature",
                                "logicalPath", "features/x-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: a\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Consumer environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Consumer profile");
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

    private static String token(UUID organizationId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("consumer-test")
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
