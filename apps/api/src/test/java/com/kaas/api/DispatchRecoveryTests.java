package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.consumer.application.DispatchConsumptionService;
import com.kaas.api.consumer.application.DispatchMessage;
import com.kaas.api.consumer.application.WorkerAssignmentService;
import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.outbox.application.DispatchPublisher;
import com.kaas.api.outbox.application.DispatchRecovery;
import com.kaas.api.outbox.application.DispatchRecoveryRepository;
import com.kaas.api.outbox.application.OutboxMessageVerifier;
import com.kaas.api.outbox.application.OutboxRelay;
import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.PublishOutcome;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
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
 * PostgreSQL-authoritative reconstruction of a dispatch RabbitMQ lost after publication (KAAS-MSG-001).
 *
 * <p>Against a real PostgreSQL and a real RabbitMQ. The production consumer and the recovery timer are off, so
 * every delivery, loss and recovery pass here is driven explicitly and nothing races a background thread. What
 * the consumer does with a message is exactly what the production listener does: it calls the same
 * {@link DispatchConsumptionService#consume} with the broker's own message id, headers and body.
 */
@Testcontainers
@Import(DispatchRecoveryTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.dispatch.recovery.enabled=false",
            "kaas.outbox.rabbit.confirm-timeout=PT3S",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.dispatch.recovery.grace=PT2S",
            "kaas.dispatch.recovery.max-interval=PT4S",
            "kaas.dispatch.recovery.max-publications=3",
            "kaas.dispatch.recovery.batch-size=2",
            "kaas.dispatch.recovery.claim-ttl=PT7S",
            "kaas.dispatch.recovery.base-backoff=PT3S",
            "kaas.dispatch.recovery.max-backoff=PT6S",
            "kaas.admission.max-active-runs-per-organization=6",
            "kaas.admission.max-queued-runs-per-organization=6"
        })
class DispatchRecoveryTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String RUNNER = "kaas.worker.recovery";
    private static final Duration GRACE = Duration.ofSeconds(2);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-dispatch-recovery");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}") private int port;
    @Value("${kaas.outbox.rabbit.queue}") private String queue;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private OutboxRelay relay;
    @Autowired private DispatchRecovery recovery;
    @Autowired private DispatchRecoveryRepository recoveries;
    @Autowired private DispatchPublisher publisher;
    @Autowired private OutboxMessageVerifier verifier;
    @Autowired private DispatchConsumptionService consumption;
    @Autowired private WorkerAssignmentService assignments;
    @Autowired private RunClaimService claims;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private MeterRegistry meters;

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
            "dispatch_recoveries", "dispatch_inbox", "outbox_messages", "run_lifecycle_events", "execution_dispatches",
            "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    // ------------------------------------------------------------------ the blocker, closed

    @Test
    @Timeout(120)
    void aPublishedDispatchTheBrokerLosesIsRepublishedAsTheSameDispatchAndDeliveredOnce() throws Exception {
        UUID runId = scheduledRun();
        Map<String, Object> dispatchBefore = dispatchRow(runId);
        Map<String, Object> outboxBefore = outboxRow(runId);

        // 1. PUBLISHED and confirmed: the relay recorded it.
        assertThat(relay.drainOnce()).isEqualTo(1);
        Map<String, Object> outboxPublished = outboxRow(runId);
        assertThat(outboxPublished.get("published_at")).isNotNull();
        // 2. The broker HELD it -- observed, and taken, so its exact bytes can be compared later.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 1);
        int depthBeforeLoss = depthOf(queue);
        Message original = rabbit.receive(queue, 5_000);
        assertThat(original).isNotNull();
        // 3. LOST: the only copy is gone and the consumer never saw it.
        int depthAfterLoss = depthOf(queue);
        assertThat(depthAfterLoss).isZero();
        assertThat(inboxCount(runId)).isZero();

        // 4. Not before the grace period: a message may merely be late.
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(depthOf(queue)).isZero();
        Thread.sleep(GRACE.plusMillis(300).toMillis());
        long eligible = recovery.eligibleNow();
        assertThat(eligible).isEqualTo(1);

        // 5. RECOVERED from PostgreSQL.
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        Message recovered = rabbit.receive(queue, 5_000);
        assertThat(recovered).isNotNull();

        // The SAME dispatch: identity, bytes, headers and transport contract.
        var o = original.getMessageProperties();
        var r = recovered.getMessageProperties();
        assertThat(r.getMessageId()).isEqualTo(o.getMessageId()).isEqualTo(outboxBefore.get("message_id").toString());
        assertThat(recovered.getBody()).isEqualTo(original.getBody());
        assertThat(r.getHeaders()).containsEntry("payloadDigest", o.getHeaders().get("payloadDigest"))
                .containsEntry("messageType", o.getHeaders().get("messageType"))
                .containsEntry("schemaVersion", o.getHeaders().get("schemaVersion"));
        assertThat(r.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(r.getContentType()).isEqualTo(o.getContentType());
        assertThat(r.getCorrelationId()).isEqualTo(o.getCorrelationId());
        assertThat(r.getReceivedRoutingKey()).isEqualTo(o.getReceivedRoutingKey());
        JsonNode originalBody = objectMapper.readTree(original.getBody());
        JsonNode recoveredBody = objectMapper.readTree(recovered.getBody());
        for (String field : List.of("dispatchId", "messageId", "runId", "attemptId", "attemptNumber",
                "runSnapshotId", "runSnapshotDigest", "queueDeadlineAt", "payloadDigest")) {
            assertThat(recoveredBody.get(field)).as(field).isEqualTo(originalBody.get(field));
        }

        // Nothing about the logical dispatch was created or changed: the dispatch row and the outbox row (other
        // than the relay's own first publication) are byte-identical, published_at is the FIRST publication.
        assertThat(dispatchRow(runId)).isEqualTo(dispatchBefore);
        Map<String, Object> outboxAfter = outboxRow(runId);
        assertThat(outboxAfter.get("published_at")).isEqualTo(outboxPublished.get("published_at"));
        assertThat(outboxAfter.get("publish_attempts")).isEqualTo(outboxPublished.get("publish_attempts"));
        assertThat(outboxAfter.get("payload")).isEqualTo(outboxBefore.get("payload"));
        Map<String, Object> history = recoveryRow(runId);
        assertThat(history.get("recovery_publications")).isEqualTo(1);
        assertThat(history.get("recovery_attempts")).isEqualTo(1);
        assertThat(history.get("first_recovered_at")).isNotNull();
        assertThat(history.get("claim_id")).isNull();

        // 6. The consumer admits the RECOVERED copy -- the original is gone -- and a runner claims it, once.
        assertThat(consume(recovered)).isEqualTo(InboxDisposition.DELIVERED);
        var claimed = assignments.claimNext(RUNNER);
        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().attemptId()).isEqualTo(UUID.fromString(originalBody.get("attemptId").stringValue()));
        assertThat(claimed.orElseThrow().assignmentEpoch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from execution_attempts where run_id = ?", Integer.class, runId))
                .as("no second attempt").isEqualTo(1);
        // And it is never recovered again: it was delivered.
        Thread.sleep(GRACE.multipliedBy(3).toMillis());
        assertThat(recovery.recoverOnce()).isZero();

        evidence(Map.ofEntries(
                Map.entry("initial_publish_confirmed", "true"),
                Map.entry("broker_depth_before_loss", Integer.toString(depthBeforeLoss)),
                Map.entry("broker_depth_after_loss", Integer.toString(depthAfterLoss)),
                Map.entry("broker_message_destroyed", "true"),
                Map.entry("dispatch_recovery_eligible", Boolean.toString(eligible == 1)),
                Map.entry("recovery_publish_confirmed", "true"),
                Map.entry("recovery_publications", history.get("recovery_publications").toString()),
                Map.entry("stable_message_identity", Boolean.toString(r.getMessageId().equals(o.getMessageId()))),
                Map.entry("stable_dispatch_identity",
                        Boolean.toString(recoveredBody.get("dispatchId").equals(originalBody.get("dispatchId")))),
                Map.entry("stable_payload_digest", Boolean.toString(
                        r.getHeaders().get("payloadDigest").equals(o.getHeaders().get("payloadDigest")))),
                Map.entry("stable_payload_bytes", Boolean.toString(java.util.Arrays.equals(recovered.getBody(),
                        original.getBody()))),
                Map.entry("delivered_after_recovery", "true"),
                Map.entry("runner_claimed_after_recovery", "true"),
                Map.entry("execution_attempts", "1"),
                Map.entry("rabbitmq_loss_recovery", "true")));
    }

    // ------------------------------------------------------------------ what is never recovered

    @Test
    @Timeout(60)
    void aDeliveredButUnclaimedRunIsNeverRepublished() throws Exception {
        UUID runId = scheduledRun();
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.DELIVERED);

        Thread.sleep(GRACE.multipliedBy(2).toMillis());
        assertThat(recovery.eligibleNow()).isZero();
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(depthOf(queue)).isZero();
        assertThat(recoveryRows(runId)).isZero();
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    @Timeout(60)
    void aDispatchTheRelayNeverPublishedIsTheRelaysNotRecoverys() throws Exception {
        UUID runId = scheduledRun();
        Thread.sleep(GRACE.multipliedBy(2).toMillis());

        assertThat(recovery.recoverOnce()).isZero();
        assertThat(recoveryRows(runId)).isZero();
        assertThat(outboxRow(runId).get("published_at")).isNull();
        assertThatThrownBy(() -> jdbc.update("""
                insert into dispatch_recoveries (message_id, outbox_id, organization_id, run_id, created_at,
                    recovery_attempts, recovery_publications, next_attempt_at)
                select message_id, outbox_id, organization_id, run_id, now(), 0, 0, now()
                  from outbox_messages where run_id = ?
                """, runId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("for a published execution dispatch");
    }

    @Test
    @Timeout(60)
    void aRunAWorkerOwnsIsNeverRepublishedEvenWithNoInboxRecord() throws Exception {
        UUID runId = scheduledRun();
        assertThat(relay.drainOnce()).isEqualTo(1);
        rabbit.receive(queue, 5_000);
        // Claimed directly -- as the pre-KAAS-DEPLOY-001 consumer did -- so there is NO inbox row. The attempt's
        // own state, not the inbox, says no transport recovery is wanted.
        assertThat(claims.claim(dispatchFor(runId), RUNNER).disposition().name()).isEqualTo("CLAIMED");

        Thread.sleep(GRACE.multipliedBy(2).toMillis());
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(depthOf(queue)).isZero();
    }

    @Test
    @Timeout(60)
    void aCancelledRunIsNeverResurrected() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(relay.drainOnce()).isEqualTo(1);
        rabbit.receive(queue, 5_000);
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        Thread.sleep(GRACE.multipliedBy(2).toMillis());
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(depthOf(queue)).isZero();
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ races

    @Test
    @Timeout(90)
    void aDelayedOriginalAndItsRecoveryConvergeOnOneDecisionOneClaimOneExecution() throws Exception {
        UUID runId = scheduledRun();
        assertThat(relay.drainOnce()).isEqualTo(1);
        // The original is merely late: it stays in the queue past the grace period, and recovery fires.
        Thread.sleep(GRACE.plusMillis(300).toMillis());
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 2);

        Message first = rabbit.receive(queue, 5_000);
        Message second = rabbit.receive(queue, 5_000);
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(second.getMessageProperties().getMessageId());
        assertThat(first.getBody()).isEqualTo(second.getBody());
        double duplicatesBefore = duplicates();

        assertThat(consume(first)).isEqualTo(InboxDisposition.DELIVERED);
        assertThat(consume(second)).isEqualTo(InboxDisposition.DELIVERED);

        assertThat(jdbc.queryForObject("select count(*) from dispatch_inbox where run_id = ?", Integer.class, runId))
                .as("one decision").isEqualTo(1);
        assertThat(jdbc.queryForObject("select delivery_count from dispatch_inbox where run_id = ?", Integer.class, runId))
                .isEqualTo(2);
        assertThat(duplicates()).isEqualTo(duplicatesBefore + 1);
        assertThat(assignments.claimNext(RUNNER)).isPresent();
        assertThat(assignments.claimNext("kaas.worker.second")).as("one claim").isEmpty();
        assertThat(count("run_lifecycle_events", runId)).as("scheduled, claimed -- nothing else").isEqualTo(2);
        evidence(Map.of("duplicate_race_decisions", "1", "duplicate_race_deliveries", "2",
                "duplicate_race_claims", "1"), "duplicate-race.properties");
    }

    @Test
    @Timeout(90)
    void twoRecoveryInstancesRacingForOneLostDispatchPublishItOnce() throws Exception {
        UUID runId = lostDispatch();
        DispatchRecovery other = secondInstance(publisher, recoveries);
        CyclicBarrier start = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> passes = new ArrayList<>();
        for (DispatchRecovery instance : List.of(recovery, other)) {
            passes.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (Exception interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                return instance.recoverOnce();
            }));
        }
        int published = passes.stream().mapToInt(CompletableFuture::join).sum();

        assertThat(published).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 1);
        assertThat(recoveryRow(runId).get("recovery_publications")).isEqualTo(1);
        // Hammering both instances cannot storm the broker: the next republication waits a longer interval.
        for (int pass = 0; pass < 10; pass++) {
            recovery.recoverOnce();
            other.recoverOnce();
        }
        assertThat(depthOf(queue)).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void aRecoveryThatDiesAfterTheBrokerConfirmIsRepublishedAndTheConsumerDecidesOnce() throws Exception {
        UUID runId = lostDispatch();
        // The process "dies" between the confirm and the record: the claim is never released.
        DispatchRecoveryRepository dying = new DelegatingRecoveries(recoveries) {
            @Override
            public boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt) {
                throw new IllegalStateException("simulated process death after the broker confirm");
            }
        };
        assertThat(secondInstance(publisher, dying).recoverOnce()).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 1);
        assertThat(recoveryRow(runId).get("claim_id")).as("the dead claim is still held").isNotNull();
        assertThat(recovery.recoverOnce()).as("not while the dead lease lives").isZero();

        // After the lease expires the dispatch is still eligible -- nothing recorded it -- and is republished.
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> recovery.recoverOnce() == 1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 2);

        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.DELIVERED);
        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.DELIVERED);
        assertThat(inboxCount(runId)).isEqualTo(1);
        assertThat(assignments.claimNext(RUNNER)).isPresent();
        assertThat(assignments.claimNext("kaas.worker.second")).isEmpty();
        evidence(Map.of("crash_after_confirm_republished", "true", "crash_after_confirm_decisions", "1",
                "crash_after_confirm_claims", "1"), "crash-recovery.properties");
    }

    @Test
    @Timeout(60)
    void cancellationThatCommitsBeforeTheRepublishWinsAndNothingIsPublished() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(relay.drainOnce()).isEqualTo(1);
        rabbit.receive(queue, 5_000);
        Thread.sleep(GRACE.plusMillis(300).toMillis());
        // Cancelled after the claim and before the publish: the re-check sees it.
        DispatchRecoveryRepository cancelling = new DelegatingRecoveries(recoveries) {
            @Override
            public Optional<String> ineligibility(String consumer, UUID messageId) {
                try {
                    assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
                return super.ineligibility(consumer, messageId);
            }
        };

        assertThat(secondInstance(publisher, cancelling).recoverOnce()).isZero();
        assertThat(depthOf(queue)).isZero();
        assertThat(recoveryRow(runId).get("recovery_attempts")).as("released, not attempted").isEqualTo(0);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
    }

    @Test
    @Timeout(60)
    void aRepublishThatEscapesJustBeforeCancellationIsRefusedByTheConsumer() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(relay.drainOnce()).isEqualTo(1);
        rabbit.receive(queue, 5_000);
        Thread.sleep(GRACE.plusMillis(300).toMillis());
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        // The copy is on the broker; the tenant cancels before the consumer reaches it.
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.STALE);
        assertThat(assignments.claimNext(RUNNER)).isEmpty();
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select termination_reason from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("USER_REQUESTED");
        evidence(Map.of("cancellation_before_republish_published", "false",
                "cancellation_after_republish_consumer", "STALE", "cancelled_run_resurrected", "false"),
                "cancellation-race.properties");
    }

    // ------------------------------------------------------------------ outage, restart, bounds

    @Test
    @Timeout(60)
    void aBrokerOutageDuringRecoveryBacksOffAndNeverTerminatesTheRun() throws Exception {
        UUID runId = lostDispatch();
        AtomicInteger publishes = new AtomicInteger();
        DispatchPublisher down = message -> {
            publishes.incrementAndGet();
            return PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        };
        DispatchRecovery failing = secondInstance(down, recoveries);

        assertThat(failing.recoverOnce()).isZero();
        Map<String, Object> history = recoveryRow(runId);
        assertThat(history.get("last_failure_code")).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(history.get("recovery_attempts")).isEqualTo(1);
        assertThat(history.get("recovery_publications")).isEqualTo(0);
        // No hot loop: until the backoff passes, nothing is attempted however often the pass runs.
        for (int pass = 0; pass < 20; pass++) {
            failing.recoverOnce();
        }
        assertThat(publishes.get()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        // The broker comes back; after the backoff the ordinary instance delivers.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> recovery.recoverOnce() == 1);
        assertThat(recoveryRow(runId).get("last_failure_code")).isNull();
    }

    @Test
    @Timeout(90)
    void aFreshInstanceResumesRecoveryFromPostgresAfterTheOldOneDied() throws Exception {
        UUID runId = lostDispatch();
        // Instance A claims and dies before publishing. Nothing it held survives but the row.
        UUID deadClaim = UUID.randomUUID();
        assertThat(recoveries.claimEligible("kaas.dispatch-consumer", deadClaim, 5, GRACE, 3, Duration.ofSeconds(4)))
                .hasSize(1);
        // Instance B, with no memory of any of it.
        DispatchRecovery fresh = secondInstance(publisher, recoveries);
        assertThat(fresh.recoverOnce()).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> fresh.recoverOnce() == 1);
        assertThat(recoveryRow(runId).get("recovery_publications")).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void republicationIsSpacedAndBoundedWhileNothingConsumes() throws Exception {
        UUID runId = lostDispatch();
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            recovery.recoverOnce();
            Thread.sleep(200);
        }
        // Three at most, however many passes ran: max-publications, with growing intervals between them.
        Map<String, Object> history = recoveryRow(runId);
        assertThat(history.get("recovery_publications")).isEqualTo(3);
        assertThat(depthOf(queue)).isEqualTo(3);
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    @Timeout(120)
    void aBrokerRestartThatKeepsTheMessageNeedsNoRecovery() throws Exception {
        UUID runId = scheduledRun();
        assertThat(relay.drainOnce()).isEqualTo(1);
        // A restart of the broker application: the durable queue and the persistent message survive it.
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> depthOf(queue) == 1);
        // Consumed within the grace period, so recovery never has a reason to act.
        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.DELIVERED);
        Thread.sleep(GRACE.multipliedBy(2).toMillis());
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(recoveryRows(runId)).isZero();
    }

    // ------------------------------------------------------------------ evidence stays evidence

    @Test
    @Timeout(60)
    void recoveryHistoryAndTheDeliveredMarkerAreRetainedAndOnlyGrow() throws Exception {
        UUID runId = lostDispatch();
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        assertThat(consume(rabbit.receive(queue, 5_000))).isEqualTo(InboxDisposition.DELIVERED);

        assertRejected("dispatch recoveries are retained as transport evidence",
                "delete from dispatch_recoveries where run_id = ?", runId);
        assertRejected("history only grows",
                "update dispatch_recoveries set recovery_publications = 0 where run_id = ?", runId);
        assertRejected("identity is immutable",
                "update dispatch_recoveries set run_id = gen_random_uuid() where run_id = ?", runId);
        // No republication recorded without an attempt, and so without the claim that made it.
        assertRejected("one attempt at a time",
                "update dispatch_recoveries set recovery_publications = recovery_publications + 1 where run_id = ?",
                runId);
        assertRejected("recorded by the claim that made it", """
                update dispatch_recoveries set recovery_attempts = recovery_attempts + 1,
                       recovery_publications = recovery_publications + 1, last_attempt_at = now(),
                       last_recovered_at = now() where run_id = ?
                """, runId);
        // No history erased or rewritten between attempts.
        assertRejected("one attempt at a time",
                "update dispatch_recoveries set last_recovered_at = null where run_id = ?", runId);
        assertRejected("one attempt at a time",
                "update dispatch_recoveries set next_attempt_at = now() - interval '1 hour' where run_id = ?", runId);
        assertRejected("one attempt at a time",
                "update dispatch_recoveries set last_failure_code = 'FORGED' where run_id = ?", runId);
        // The delivered marker recovery relies on cannot be pruned out from under it.
        assertRejected("inbox decisions are retained", "delete from dispatch_inbox where run_id = ?", runId);
        // And the dispatch itself stays immutable -- to its own guard. A no-op update, so that no CHECK constraint
        // could refuse it instead: attempt_number = 2 would have been refused by ck_execution_dispatches_attempt_number
        // even with the guard gone, and proved nothing.
        assertRejected("execution dispatch identity and payload are immutable",
                "update execution_dispatches set occurred_at = occurred_at where run_id = ?", runId);
    }

    @Test
    @Timeout(60)
    void recoveryMetricsCarryNoIdentity() throws Exception {
        lostDispatch();
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        var series = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("kaas.dispatch.recovery"))
                .toList();
        assertThat(series).isNotEmpty();
        assertThat(series).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getValue()).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-")));
    }

    // ------------------------------------------------------------------ helpers

    private UUID scheduledRun() throws Exception {
        UUID runId = createRun(tenant());
        scheduler.scheduleDue();
        return runId;
    }

    /** Published, confirmed, held by the broker, destroyed before the consumer saw it, past its grace. */
    private UUID lostDispatch() throws Exception {
        UUID runId = scheduledRun();
        assertThat(relay.drainOnce()).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> depthOf(queue) == 1);
        rabbitAdmin.purgeQueue(queue, false);
        assertThat(depthOf(queue)).isZero();
        Thread.sleep(GRACE.plusMillis(300).toMillis());
        return runId;
    }

    private DispatchRecovery secondInstance(DispatchPublisher withPublisher, DispatchRecoveryRepository withRepository) {
        return new DispatchRecovery(withRepository, withPublisher, verifier, meters, true, "kaas.dispatch-consumer",
                GRACE, Duration.ofSeconds(4), 3, 2, Duration.ofSeconds(7), Duration.ofSeconds(3),
                Duration.ofSeconds(6), Duration.ofSeconds(3), Duration.ofMinutes(5));
    }

    private InboxDisposition consume(Message message) {
        assertThat(message).isNotNull();
        var properties = message.getMessageProperties();
        return consumption.consume(new DispatchMessage(
                UUID.fromString(properties.getMessageId()),
                String.valueOf(properties.getHeaders().get("messageType")),
                String.valueOf(properties.getHeaders().get("schemaVersion")),
                message.getBody()));
    }

    private double duplicates() {
        return meters.find("kaas.dispatch.duplicate").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private Map<String, Object> dispatchRow(UUID runId) {
        return jdbc.queryForMap("select to_jsonb(d)::text as row from execution_dispatches d where run_id = ?", runId);
    }

    private Map<String, Object> outboxRow(UUID runId) {
        return jdbc.queryForMap("select *, payload::text as payload from outbox_messages where run_id = ?", runId);
    }

    private Map<String, Object> recoveryRow(UUID runId) {
        return jdbc.queryForMap("select * from dispatch_recoveries where run_id = ?", runId);
    }

    private int recoveryRows(UUID runId) {
        return jdbc.queryForObject("select count(*) from dispatch_recoveries where run_id = ?", Integer.class, runId);
    }

    private int inboxCount(UUID runId) {
        return jdbc.queryForObject("select count(*) from dispatch_inbox where run_id = ?", Integer.class, runId);
    }

    private int depthOf(String name) {
        var properties = rabbitAdmin.getQueueProperties(name);
        return properties == null ? 0 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private static void evidence(Map<String, String> values) throws Exception {
        evidence(values, "dispatch-recovery.properties");
    }

    /** Structured evidence the deployment-readiness gate reads. Values are words and counts only. */
    static void evidence(Map<String, String> values, String file) throws Exception {
        String configured = System.getProperty("kaas.evidence.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("kaas.evidence.dir is set by the deploymentReadinessTest task.");
        }
        Path directory = Path.of(configured);
        Files.createDirectories(directory);
        StringBuilder text = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        Files.writeString(directory.resolve(file), text.toString());
    }

    /** A pass-through repository a test can override one method of. */
    static class DelegatingRecoveries implements DispatchRecoveryRepository {
        private final DispatchRecoveryRepository delegate;

        DelegatingRecoveries(DispatchRecoveryRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Claimed> claimEligible(String consumer, UUID claimId, int batchSize, Duration grace,
                int maxPublications, Duration claimTtl) {
            return delegate.claimEligible(consumer, claimId, batchSize, grace, maxPublications, claimTtl);
        }

        @Override
        public Optional<String> ineligibility(String consumer, UUID messageId) {
            return delegate.ineligibility(consumer, messageId);
        }

        @Override
        public boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt) {
            return delegate.recordPublished(messageId, claimId, nextAttemptAt);
        }

        @Override
        public boolean recordFailed(UUID messageId, UUID claimId, String failureCode, Instant nextAttemptAt) {
            return delegate.recordFailed(messageId, claimId, failureCode, nextAttemptAt);
        }

        @Override
        public boolean release(UUID messageId, UUID claimId) {
            return delegate.release(messageId, claimId);
        }

        @Override
        public long countEligible(String consumer, Duration grace, int maxPublications) {
            return delegate.countEligible(consumer, grace, maxPublications);
        }

        @Override
        public long countCapped(String consumer, int maxPublications) {
            return delegate.countCapped(consumer, maxPublications);
        }

        @Override
        public Instant currentDatabaseTime() {
            return delegate.currentDatabaseTime();
        }
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
