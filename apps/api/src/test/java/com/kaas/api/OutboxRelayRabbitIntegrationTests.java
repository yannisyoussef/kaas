package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.outbox.application.OutboxRelay;
import com.kaas.api.outbox.application.OutboxRepository;
import com.kaas.api.outbox.domain.OutboxMessage;
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
import org.springframework.amqp.core.Message;
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
 * Drives the whole implemented flow against a real PostgreSQL and a real RabbitMQ: create run, schedule through
 * the production scheduler, relay the outbox, and confirm publication.
 *
 * <p>The broker is never mocked here. The relay is invoked directly rather than by its timer so that each pass is
 * deterministic. Consumption happens only to assert what was published: no production consumer exists, nothing
 * claims an attempt, and no execution command is ever produced.
 */
@Testcontainers
@Import(OutboxRelayRabbitIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Timers are off: every pass in this suite is invoked explicitly so assertions stay deterministic.
            "kaas.scheduling.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.outbox.relay.max-attempts=3",
            // Small batches keep the concurrency test honest: with the default batch one relay would take
            // everything. It also satisfies the startup invariant claim-ttl > batch-size x confirm-timeout.
            "kaas.outbox.relay.batch-size=2",
            "kaas.outbox.relay.base-backoff=PT30S",
            "kaas.outbox.relay.max-backoff=PT1M",
            // The claim-expiry tests pass their own short TTL explicitly, so the relay's own lease is generous
            // enough that a batch cannot expire mid-publication.
            "kaas.outbox.relay.claim-ttl=PT30S",
            "kaas.outbox.rabbit.confirm-timeout=PT10S"
        })
class OutboxRelayRabbitIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    /** Nothing a queue-time message may carry, checked against the real broker delivery. */
    private static final List<String> CLAIM_TIME_AUTHORITY = List.of(
            "assignmentEpoch", "workerId", "worker", "lease", "leaseId", "capability", "secretCapability",
            "sourceCapability", "secretValue", "presigned", "objectStoreUrl", "routingKey", "exchange",
            "docker", "image", "hostPath", "credential", "token", "source", "feature", "script");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-relay");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${kaas.outbox.rabbit.queue}")
    private String queue;

    @Value("${kaas.outbox.rabbit.exchange}")
    private String exchange;

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
    private RabbitTemplate rabbitTemplate;

    @Test
    void theWholeFlowReachesTheBrokerAndOnlyThenMarksTheOutboxPublished() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();

        // The production scheduler performs CREATED -> QUEUED through the established use case.
        assertThat(pendingRunScheduler.scheduleDue()).isGreaterThanOrEqualTo(1);
        Map<String, Object> pending = outboxRow(runId);
        assertThat(pending.get("published_at")).isNull();
        assertThat(pending.get("terminal_disposition")).isNull();
        assertThat(pending.get("publish_attempts")).isEqualTo(0);
        assertThat(pending.get("available_at")).isEqualTo(pending.get("occurred_at"));
        assertThat(pending.get("relay_claim_id")).isNull();
        assertThat(pending.get("message_type")).isEqualTo("EXECUTION_DISPATCH");

        assertThat(relay.drainOnce()).isGreaterThanOrEqualTo(1);

        // Only a broker-confirmed publication marks the row published, and the claim is released.
        Map<String, Object> published = outboxRow(runId);
        assertThat(published.get("published_at")).isNotNull();
        assertThat(published.get("terminal_disposition")).isNull();
        assertThat(published.get("last_failure_code")).isNull();
        assertThat(published.get("publish_attempts")).isEqualTo(1);
        assertThat(published.get("last_attempt_at")).isNotNull();
        assertThat(published.get("relay_claim_id")).isNull();

        // The broker holds exactly the persisted DispatchIntent, byte for byte.
        Message delivered = receive();
        assertThat(delivered).isNotNull();
        String body = new String(delivered.getBody(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(String.valueOf(published.get("payload")));
        assertThat(delivered.getMessageProperties().getMessageId())
                .isEqualTo(String.valueOf(published.get("message_id")));
        assertThat(header(delivered, "messageType")).isEqualTo("EXECUTION_DISPATCH");
        assertThat(header(delivered, "schemaVersion")).isEqualTo("1.0");
        assertThat(header(delivered, "payloadDigest")).isEqualTo("sha256:" + published.get("payload_sha256"));
        assertThat(delivered.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");

        JsonNode dispatch = objectMapper.readTree(body);
        assertThat(dispatch.propertyNames())
                .containsExactlyInAnyOrder(
                        "schemaVersion", "messageId", "messageType", "dispatchId", "occurredAt", "producer",
                        "organizationId", "projectId", "runId", "runVersion", "attemptId", "attemptNumber",
                        "runSnapshotId", "runSnapshotDigest", "queueDeadlineAt", "payloadDigest");
        assertThat(dispatch.get("runId").stringValue()).isEqualTo(runId.toString());

        // Neither the body nor the transport properties may carry claim-time execution authority.
        String scannable = (body + " " + String.join(" ", delivered.getMessageProperties().getHeaders().keySet()))
                .toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : CLAIM_TIME_AUTHORITY) {
            assertThat(scannable).as(forbidden).doesNotContain(forbidden.toLowerCase(java.util.Locale.ROOT));
        }

        // A published row is final: it is never reselected, and the relay has nothing left to do.
        assertThat(relay.drainOnce()).isZero();
        assertThat(receive()).isNull();
    }

    @Test
    void aTamperedPayloadIsNeverPublishedAndFailsPermanentlyWithoutRetry() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();

        // Corrupt the durable record the way a compromised writer or a storage fault would. The immutability
        // guard prevents this through the application, so it is forced directly. The payload is altered rather
        // than the digest because payload_sha256 participates in the foreign key back to the dispatch row.
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set payload = jsonb_set(payload, '{runVersion}', '99'::jsonb)"
                        + " where run_id = ?",
                runId));

        assertThat(relay.drainOnce()).isZero();

        Map<String, Object> row = outboxRow(runId);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("terminal_disposition")).isEqualTo("PERMANENT_FAILURE");
        assertThat(row.get("last_failure_code")).isEqualTo("INTEGRITY_DIGEST_MISMATCH");
        assertThat(row.get("publish_attempts")).isEqualTo(1);
        // Integrity failure is terminal on the first attempt: it is a security event, not a transient hiccup.
        assertThat(relay.drainOnce()).isZero();
        assertThat(outboxRow(runId).get("publish_attempts")).isEqualTo(1);
        assertThat(receive()).isNull();
    }

    @Test
    @Timeout(180)
    void concurrentRelaysEachClaimAMessageOnceAndPublishEveryOne() throws Exception {
        resetOutboxAndQueue();
        int runs = 6;
        for (int index = 0; index < runs; index++) {
            createRun();
        }
        pendingRunScheduler.scheduleDue();
        long pendingBefore = outbox.countPending();
        assertThat(pendingBefore).isGreaterThanOrEqualTo(runs);

        int relays = 4;
        var barrier = new CyclicBarrier(relays);
        try (var pool = Executors.newFixedThreadPool(relays)) {
            List<Integer> confirmed = pool
                    .invokeAll(IntStream.range(0, relays)
                            .<java.util.concurrent.Callable<Integer>>mapToObj(index -> () -> {
                                barrier.await(60, TimeUnit.SECONDS);
                                int total = 0;
                                for (int pass = 0; pass < 5; pass++) {
                                    total += relay.drainOnce();
                                }
                                return total;
                            })
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get(120, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError("concurrent relay failed", exception);
                        }
                    })
                    .toList();

            // SKIP LOCKED means the relays took disjoint work rather than fighting over the same rows, so every
            // message is accounted for. The total is a lower bound rather than an equality: publication is at
            // least once, and a lease that expires mid-flight legitimately lets another relay republish.
            assertThat(confirmed.stream().mapToInt(Integer::intValue).sum())
                    .isGreaterThanOrEqualTo((int) pendingBefore);
            // With a batch size of two and six messages, a single relay cannot have taken them all: SKIP LOCKED
            // must have handed disjoint work to at least two relays.
            assertThat(confirmed.stream().filter(count -> count > 0).count()).isGreaterThanOrEqualTo(2L);
        }
        assertThat(outbox.countPending()).isZero();

        // Each row reached the published end state exactly once, and none was double-counted or corrupted.
        // Terminal rows from other tests in this class are deliberately excluded: they are an end state too.
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages"
                                + " where published_at is null and terminal_disposition is null",
                        Long.class))
                .isZero();
        // Every published row records at least one attempt. Asserting exactly one would be wrong: publication is
        // at least once, and another test in this class legitimately publishes on its second attempt.
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages"
                                + " where published_at is not null and publish_attempts < 1",
                        Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages where relay_claim_id is not null", Long.class))
                .isZero();
    }

    @Test
    void anExpiredClaimIsReclaimableAndTheCrashedRelayCanNoLongerRecordAnOutcome() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();
        UUID outboxId = UUID.fromString(String.valueOf(outboxRow(runId).get("outbox_id")));

        // A relay claims the row with a generous lease and then "crashes": it never records an outcome.
        UUID crashedClaim = UUID.randomUUID();
        List<OutboxMessage> claimed = outbox.claimPending(crashedClaim, 10, Duration.ofMinutes(10));
        assertThat(claimed).extracting(OutboxMessage::outboxId).contains(outboxId);
        // While the lease is live nobody else can take it. The lease is long, so this cannot race.
        assertThat(outbox.claimPending(UUID.randomUUID(), 10, Duration.ofMinutes(10)))
                .extracting(OutboxMessage::outboxId)
                .doesNotContain(outboxId);

        expireClaim(outboxId);

        // A healthy relay reclaims it and publishes.
        UUID freshClaim = UUID.randomUUID();
        assertThat(outbox.claimPending(freshClaim, 10, Duration.ofSeconds(60)))
                .extracting(OutboxMessage::outboxId)
                .contains(outboxId);
        assertThat(outbox.recordPublished(outboxId, freshClaim, outbox.currentDatabaseTime())).isTrue();

        // The crashed relay coming back must not overwrite the newer disposition.
        assertThat(outbox.recordRetry(
                        outboxId, crashedClaim, outbox.currentDatabaseTime(),
                        outbox.currentDatabaseTime().plusSeconds(60), "BROKER_UNAVAILABLE"))
                .isFalse();
        assertThat(outbox.recordTerminal(
                        outboxId, crashedClaim, outbox.currentDatabaseTime(),
                        com.kaas.api.outbox.domain.TerminalDisposition.PERMANENT_FAILURE, "BROKER_UNAVAILABLE"))
                .isFalse();
        Map<String, Object> row = outboxRow(runId);
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("terminal_disposition")).isNull();
        assertThat(row.get("publish_attempts")).isEqualTo(1);
    }

    @Test
    void aCrashAfterTheBrokerConfirmsRepublishesRatherThanLosingTheMessage() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();
        UUID outboxId = UUID.fromString(String.valueOf(outboxRow(runId).get("outbox_id")));

        // Publish through the real broker, then simulate the process dying before the success write by simply
        // never recording it. This window is inherent to at-least-once and is deliberately not "solved".
        UUID lostClaim = UUID.randomUUID();
        List<OutboxMessage> claimed = outbox.claimPending(lostClaim, 10, Duration.ofMinutes(10));
        assertThat(claimed).hasSize(1);
        assertThat(publishDirectly(claimed.get(0))).isTrue();
        Message first = receive();
        assertThat(first).isNotNull();

        expireClaim(outboxId);

        // The next pass republishes the identical message. Duplicate delivery is expected and safe.
        assertThat(relay.drainOnce()).isEqualTo(1);
        Message second = receive();
        assertThat(second).isNotNull();
        assertThat(new String(second.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(new String(first.getBody(), StandardCharsets.UTF_8));
        assertThat(second.getMessageProperties().getMessageId())
                .isEqualTo(first.getMessageProperties().getMessageId());
        // Stable identity plus an identical semantic digest are what make the duplicate safe for a consumer.
        assertThat(header(second, "payloadDigest")).isEqualTo(header(first, "payloadDigest"));
        assertThat(outboxRow(runId).get("published_at")).isNotNull();
    }

    @Test
    void theOutboxAcceptsAnotherDeclaredMessageTypeWithoutADispatchReference() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();
        relay.drainOnce();
        rabbitTemplate.execute(channel -> channel.queuePurge(queue));
        Map<String, Object> dispatchRow = outboxRow(runId);

        // The generalization is real: a second declared type persists with no dispatch_id at all. No publisher
        // exists for it, which is exactly why the relay must refuse to publish it rather than guess.
        UUID messageId = UUID.randomUUID();
        jdbc.update(
                """
                insert into outbox_messages
                    (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                     message_type, schema_version, aggregate_type, aggregate_id, payload, payload_sha256,
                     occurred_at, available_at, published_at, publish_attempts, last_failure_code)
                values (?, null, ?, ?, ?, ?, 'RUN_STATE_CHANGED', '1.0', 'TEST_RUN', ?, cast(? as jsonb), ?,
                        ?, ?, null, 0, null)
                """,
                UUID.randomUUID(), messageId, dispatchRow.get("organization_id"), dispatchRow.get("project_id"),
                runId, runId, "{\"runId\":\"" + runId + "\",\"lifecycleState\":\"QUEUED\"}",
                "d".repeat(64), dispatchRow.get("occurred_at"), dispatchRow.get("occurred_at"));

        assertThat(relay.drainOnce()).isZero();
        Map<String, Object> row = jdbc.queryForMap(
                "select * from outbox_messages where message_id = ?", messageId);
        assertThat(row.get("dispatch_id")).isNull();
        assertThat(row.get("terminal_disposition")).isEqualTo("PERMANENT_FAILURE");
        assertThat(row.get("last_failure_code")).isEqualTo("UNSUPPORTED_MESSAGE_TYPE");
        assertThat(receive()).isNull();

        // An execution dispatch, by contrast, must always carry its dispatch reference.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into outbox_messages
                            (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                             message_type, schema_version, aggregate_type, aggregate_id, payload, payload_sha256,
                             occurred_at, available_at, published_at, publish_attempts, last_failure_code)
                        values (?, null, ?, ?, ?, ?, 'EXECUTION_DISPATCH', '1.0', 'TEST_RUN', ?,
                                cast('{}' as jsonb), ?, now(), now(), null, 0, null)
                        """,
                        UUID.randomUUID(), UUID.randomUUID(), dispatchRow.get("organization_id"),
                        dispatchRow.get("project_id"), runId, runId, "e".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_dispatch_reference");

        // An undeclared type cannot be stored at all. This must be a fresh INSERT: an UPDATE would be rejected by
        // the immutability guard first and would pass even if the CHECK constraint were removed.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into outbox_messages
                            (outbox_id, dispatch_id, message_id, organization_id, project_id, run_id,
                             message_type, schema_version, aggregate_type, aggregate_id, payload, payload_sha256,
                             occurred_at, available_at, published_at, publish_attempts, last_failure_code)
                        values (?, null, ?, ?, ?, ?, 'ARBITRARY', '1.0', 'TEST_RUN', ?, cast('{}' as jsonb), ?,
                                now(), now(), null, 0, null)
                        """,
                        UUID.randomUUID(), UUID.randomUUID(), dispatchRow.get("organization_id"),
                        dispatchRow.get("project_id"), runId, runId, "f".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_message_type");

        // A terminal message can be revived, but only by an explicit operator action that resets the budget.
        withGuardDisabled(() -> {});
        jdbc.update(
                "update outbox_messages set terminal_disposition = null, last_failure_code = null,"
                        + " publish_attempts = 0 where message_id = ?",
                messageId);
        assertThat(jdbc.queryForMap("select * from outbox_messages where message_id = ?", messageId)
                        .get("terminal_disposition"))
                .isNull();
    }

    @Test
    void aMandatoryMessageThatReachesNoQueueIsReturnedAndRetriedRatherThanSilentlyDropped() throws Exception {
        resetOutboxAndQueue();
        UUID runId = createRun();
        pendingRunScheduler.scheduleDue();

        // Remove the binding so the exchange has nowhere to route. Without the mandatory flag the broker would
        // discard the message silently and the relay would believe it had been delivered.
        rabbitTemplate.execute(channel -> channel.queueUnbind(queue, exchange, "execution-dispatch"));
        try {
            assertThat(relay.drainOnce()).isZero();
            Map<String, Object> row = outboxRow(runId);
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("last_failure_code")).isEqualTo("UNROUTABLE");
            assertThat(row.get("publish_attempts")).isEqualTo(1);
            // Retried rather than terminal on the first return: a topology race must not strand a run forever,
            // and the bounded attempt budget still ends in a terminal disposition.
            assertThat(row.get("terminal_disposition")).isNull();
            assertThat(((java.sql.Timestamp) row.get("available_at")).toInstant())
                    .isAfter(((java.sql.Timestamp) row.get("occurred_at")).toInstant());
            assertThat(receive()).isNull();
        } finally {
            rabbitTemplate.execute(channel -> channel.queueBind(queue, exchange, "execution-dispatch"));
        }

        // With routing restored the same message publishes normally, leaving no backlog for other tests.
        makeAvailableNow(runId);
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(outboxRow(runId).get("published_at")).isNotNull();
        assertThat(receive()).isNotNull();
    }

    private void makeAvailableNow(UUID runId) {
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set available_at = now() where run_id = ?", runId));
    }

    /** Publishes one claimed message through the real broker without recording any outcome. */
    private boolean publishDirectly(OutboxMessage message) {
        var correlation = new org.springframework.amqp.rabbit.connection.CorrelationData(
                message.messageId().toString());
        rabbitTemplate.send(
                exchange,
                "execution-dispatch",
                org.springframework.amqp.core.MessageBuilder.withBody(
                                message.payload().getBytes(StandardCharsets.UTF_8))
                        .setMessageId(message.messageId().toString())
                        .setHeader("payloadDigest", message.payloadDigest())
                        .build(),
                correlation);
        try {
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            return confirm != null && confirm.ack();
        } catch (Exception exception) {
            throw new AssertionError("direct publication failed", exception);
        }
    }

    /** Expires a lease deterministically instead of sleeping on a short TTL and hoping the timing holds. */
    private void expireClaim(UUID outboxId) {
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set relay_claim_expires_at = relay_claimed_at + interval '1 millisecond'"
                        + " where outbox_id = ?",
                outboxId));
        assertThat(jdbc.queryForObject(
                        "select relay_claim_expires_at <= now() from outbox_messages where outbox_id = ?",
                        Boolean.class,
                        outboxId))
                .isTrue();
    }

    /**
     * DISABLE TRIGGER is DDL that affects every session, so it must always be re-enabled: leaving it off would
     * silently pass every later guard assertion in this class.
     */
    private void withGuardDisabled(Runnable work) {
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            work.run();
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }
    }

    /**
     * These tests share one Spring context, one database, and one broker, so each starts from a known state:
     * everything still publishable is drained, and the queue is purged.
     */
    private void resetOutboxAndQueue() {
        // A failure test may have deferred a row into the future; bring everything due before draining.
        withGuardDisabled(() -> jdbc.update(
                "update outbox_messages set available_at = now()"
                        + " where published_at is null and terminal_disposition is null"));
        for (int pass = 0; pass < 25 && outbox.countPending() > 0; pass++) {
            relay.drainOnce();
        }
        assertThat(outbox.countPending()).isZero();
        rabbitTemplate.execute(channel -> channel.queuePurge(queue));
    }

    /** {@code getHeader} is generic, which makes the AssertJ overload ambiguous without an explicit type. */
    private static String header(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        return value == null ? null : value.toString();
    }

    private Message receive() {
        return rabbitTemplate.receive(queue, 5000);
    }

    private Map<String, Object> outboxRow(UUID runId) {
        return jdbc.queryForMap(
                "select * from outbox_messages where run_id = ? and message_type = 'EXECUTION_DISPATCH'", runId);
    }

    private UUID createRun() throws Exception {
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
                                "name", "Relay feature",
                                "logicalPath", "features/relay-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: relay\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Relay environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Relay profile");
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

        var response = post(
                "/api/v1/projects/" + projectId + "/runs",
                bearer,
                key(),
                json(Map.of(
                        "featureRevisionIds", List.of(featureRevision),
                        "runProfileRevisionId", profileRevision)));
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(json(response).get("runId").stringValue());
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
                .subject("relay-test")
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
