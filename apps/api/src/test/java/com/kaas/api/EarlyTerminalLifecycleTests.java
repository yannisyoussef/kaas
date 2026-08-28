package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.QueueDeadlineReaper;
import com.kaas.api.controlplane.application.RunTerminationService;
import com.kaas.api.outbox.application.OutboxRelay;
import com.kaas.api.outbox.application.OutboxRepository;
import com.kaas.api.security.TenantPrincipal;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Early run cancellation and queue-deadline reaping — the first transitions that can make a run terminal.
 *
 * <p>Before this slice the admission ceiling was an availability ceiling: nothing could ever leave, so an
 * organization that legitimately filled its quota could never create another run. Several of these tests are
 * therefore about capacity being released, not only about the transition being written.
 *
 * <p>Every test mints a fresh organization, so per-organization counts stay isolated despite the shared context.
 */
@Testcontainers
@Import(EarlyTerminalLifecycleTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Both timers are driven by hand so a pass never races an assertion.
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            // Short enough that a real deadline can actually pass inside a test.
            "kaas.scheduling.queue-timeout=PT2S",
            "kaas.admission.max-active-runs-per-organization=4",
            "kaas.admission.max-queued-runs-per-organization=2",
            "kaas.scheduling.backoff.max-failures=3",
            "kaas.scheduling.backoff.base-delay=PT30S",
            "kaas.scheduling.backoff.max-delay=PT10M",
            "kaas.scheduling.backoff.jitter=0.25",
            "kaas.scheduling.batch-size=20",
            // Reaping has its own budget in production precisely because it parks capacity; the test pins its
            // own small one rather than inheriting the scheduler's.
            "kaas.reaping.backoff.max-failures=3",
            "kaas.reaping.backoff.base-delay=PT30S",
            "kaas.reaping.backoff.max-delay=PT10M",
            "kaas.reaping.backoff.jitter=0.25",
            "kaas.reaping.batch-size=20",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
class EarlyTerminalLifecycleTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final int ACTIVE_LIMIT = 4;
    private static final int QUEUED_LIMIT = 2;
    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(2);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-terminal");

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
    private QueueDeadlineReaper reaper;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private MeterRegistry meterRegistry;

    /** Spied rather than mocked, so termination really happens unless a test deliberately breaks it. */
    @MockitoSpyBean
    private RunTerminationService terminations;

    /**
     * Reaping is global, so a run left behind by one test would be reaped inside another and make an assertion
     * about "nothing happened" pass for the wrong reason.
     */
    @AfterEach
    void clearRuns() {
        reset(terminations);
        jdbc.update("delete from run_scheduling_control");
        for (String table : List.of(
                "outbox_messages", "run_lifecycle_events", "execution_dispatches", "execution_attempts",
                "run_snapshot_tags", "run_snapshot_artifact_types", "run_snapshot_configuration_entries",
                "run_snapshot_features", "run_snapshots", "test_runs")) {
            jdbc.update("alter table " + table + " disable trigger all");
        }
        try {
            for (String table : List.of(
                    "outbox_messages", "run_lifecycle_events", "execution_dispatches", "execution_attempts",
                    "run_snapshot_tags", "run_snapshot_artifact_types", "run_snapshot_configuration_entries",
                    "run_snapshot_features", "run_snapshots", "api_idempotency_keys", "test_runs")) {
                jdbc.update("delete from " + table);
            }
        } finally {
            for (String table : List.of(
                    "outbox_messages", "run_lifecycle_events", "execution_dispatches", "execution_attempts",
                    "run_snapshot_tags", "run_snapshot_artifact_types", "run_snapshot_configuration_entries",
                    "run_snapshot_features", "run_snapshots", "test_runs")) {
                jdbc.update("alter table " + table + " enable trigger all");
            }
        }
    }

    @Test
    void cancellingACreatedRunEndsItImmediatelyAndNeverDispatchesAnything() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);

        HttpResponse<String> response = cancel(tenant.bearer(), runId);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode run = json(response);
        assertThat(run.get("lifecycleState").stringValue()).isEqualTo("COMPLETED");
        assertThat(run.get("cancellationStatus").stringValue()).isEqualTo("ACKNOWLEDGED");
        assertThat(run.get("infrastructureOutcome").stringValue()).isEqualTo("CANCELLED");
        assertThat(run.get("terminationReason").stringValue()).isEqualTo("USER_REQUESTED");
        assertThat(run.get("terminationPhase").stringValue()).isEqualTo("CANCELLATION");
        // Nothing ran, so there is no test result and nothing for a quality gate to judge.
        assertThat(run.get("testOutcome").stringValue()).isEqualTo("NOT_AVAILABLE");
        assertThat(run.get("qualityGateStatus").stringValue()).isEqualTo("NOT_EVALUATED");
        assertThat(run.get("runVersion").asInt()).isEqualTo(2);
        // The ETag tracks the semantic version, so a cached representation cannot survive the transition.
        assertThat(response.headers().firstValue("ETag")).contains("\"run-2\"");

        // Cancelling before scheduling is the whole point: no attempt, no dispatch, and above all no durable
        // broker message for work nobody will ever do.
        assertThat(count("execution_attempts", runId)).isZero();
        assertThat(count("execution_dispatches", runId)).isZero();
        assertThat(count("outbox_messages", runId)).isZero();
        // A CREATED run never had an attempt, so its terminal event cannot reference one.
        Map<String, Object> event = jdbc.queryForMap(
                "select * from run_lifecycle_events where run_id = ?", runId);
        assertThat(event.get("attempt_id")).isNull();
        assertThat(event.get("previous_state")).isEqualTo("CREATED");
        assertThat(event.get("lifecycle_state")).isEqualTo("COMPLETED");
        assertThat(event.get("sequence")).isEqualTo(1L);
        assertThat(event.get("run_version")).isEqualTo(2L);

        // And the scheduler will never pick it up again.
        assertThat(scheduler.scheduleDue()).isZero();
    }

    @Test
    void aTerminalRunStopsHoldingAdmissionCapacity() throws Exception {
        Tenant tenant = tenant();
        List<UUID> runIds = new ArrayList<>();
        for (int index = 0; index < ACTIVE_LIMIT; index++) {
            runIds.add(createRun(tenant));
        }
        // At the ceiling, the next creation is refused. This is the state an organization was previously stuck
        // in forever, because no run could ever become terminal.
        assertThat(create(tenant).statusCode()).isEqualTo(429);

        assertThat(cancel(tenant.bearer(), runIds.get(0)).statusCode()).isEqualTo(200);

        // The ceiling itself is the assertion, not a count restated from the production query — a copy of that
        // query would move in lockstep with it and could never detect a change in what "active" means.
        assertThat(create(tenant).statusCode()).isEqualTo(202);
        assertThat(create(tenant).statusCode()).isEqualTo(429);
    }

    @Test
    void cancellingAQueuedRunWithdrawsItsUnpublishedDispatchWithoutSpendingAnAttempt() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        assertThat(scheduler.scheduleDue()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        long deadLettersBefore = seedDeadLetter(tenant);

        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        Map<String, Object> message = jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
        assertThat(message.get("terminal_disposition")).isEqualTo("SUPPRESSED_CANCELLED");
        assertThat(message.get("published_at")).isNull();
        // A withdrawal is not a delivery failure. Nothing was attempted, so nothing may claim an attempt was
        // spent or that a broker refused anything.
        assertThat(message.get("publish_attempts")).isEqualTo(0);
        assertThat(message.get("last_attempt_at")).isNull();
        assertThat(message.get("last_failure_code")).isNull();
        // The message is retained, because it is evidence of what the control plane decided.
        assertThat(count("outbox_messages", runId)).isEqualTo(1);

        // A suppressed message is not a dead letter: counting it as one would make every cancellation look like
        // a broker fault and drag the relay's health down with it. The baseline is a real dead letter, so this
        // has to actually exclude the suppression rather than count nothing.
        assertThat(deadLettersBefore).isPositive();
        assertThat(outbox.countTerminal()).isEqualTo(deadLettersBefore);

        // And the relay will not touch it again. Asserting drainOnce() returns zero would prove nothing — it
        // counts broker-confirmed messages and there is no broker in this class, so it returns zero for a live
        // row too. What separates the two is what the pass does to the row: a live one is claimed and an attempt
        // is burned. A withdrawn one must come back untouched.
        relay.drainOnce();
        Map<String, Object> afterDrain = jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
        assertThat(afterDrain.get("published_at")).isNull();
        assertThat(afterDrain.get("publish_attempts")).isEqualTo(0);
        assertThat(afterDrain.get("last_attempt_at")).isNull();
        assertThat(afterDrain.get("last_failure_code")).isNull();
        assertThat(afterDrain.get("relay_claim_id")).isNull();
        assertThat(afterDrain.get("terminal_disposition")).isEqualTo("SUPPRESSED_CANCELLED");

        // The queued bundle itself is preserved: it records what really happened before the run was cancelled.
        assertThat(count("execution_attempts", runId)).isEqualTo(1);
        assertThat(count("execution_dispatches", runId)).isEqualTo(1);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(versionOf(runId)).isEqualTo(3L);
    }

    @Test
    void aDispatchAlreadyClaimedByTheRelayIsLeftToPublishRatherThanRacedForWithdrawal() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // A claimed row is mid-publication. Suppressing it would be pretending the control plane can recall a
        // message it may already have handed to the broker, so it is deliberately left alone.
        jdbc.update(
                """
                update outbox_messages
                   set relay_claim_id = gen_random_uuid(), relay_claimed_at = now(),
                       relay_claim_expires_at = now() + interval '5 min'
                 where run_id = ?
                """,
                runId);

        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        Map<String, Object> message = jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
        assertThat(message.get("terminal_disposition")).isNull();
        assertThat(message.get("relay_claim_id")).isNotNull();
        // The run is still terminal. Cancellation does not wait on a message it cannot recall.
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void cancellationIsIdempotentByStateAndWritesNothingTheSecondTime() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);

        HttpResponse<String> first = cancel(tenant.bearer(), runId);
        HttpResponse<String> second = cancel(tenant.bearer(), runId);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("ETag")).isEqualTo(first.headers().firstValue("ETag"));
        // The run is the idempotency scope: repeating cannot produce a second version or a second event.
        assertThat(versionOf(runId)).isEqualTo(2L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(1);
    }

    @Test
    void cancellationIsTenantScopedAndConcealsThatTheRunExists() throws Exception {
        Tenant owner = tenant();
        UUID runId = createRun(owner);
        String intruder = token(UUID.randomUUID());

        HttpResponse<String> response = cancel(intruder, runId);

        // Not 403: telling another organization that this run exists is itself the leak, so the answer is the
        // same one a completely unknown identifier would get. The run id appears only as the path that was
        // requested, which the caller already knew.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(response.body())
                .doesNotContain(owner.organizationId().toString())
                .doesNotContain(owner.projectId().toString())
                .doesNotContain("CREATED");
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
        assertThat(count("run_lifecycle_events", runId)).isZero();
    }

    @Test
    void aRunThatExpiredIsNeverReportedAsCancelled() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        awaitAllDeadlines();

        assertThat(reaper.reapExpired()).isEqualTo(1);

        JsonNode run = json(get("/api/v1/runs/" + runId, tenant.bearer()));
        assertThat(run.get("lifecycleState").stringValue()).isEqualTo("COMPLETED");
        assertThat(run.get("infrastructureOutcome").stringValue()).isEqualTo("TIMED_OUT");
        assertThat(run.get("terminationReason").stringValue()).isEqualTo("QUEUE_DEADLINE");
        assertThat(run.get("terminationPhase").stringValue()).isEqualTo("QUEUE");
        // Nobody asked for it, so nothing about it may claim anybody did.
        assertThat(run.get("cancellationStatus").stringValue()).isEqualTo("NOT_REQUESTED");
        assertThat(run.get("cancellationRequestedAt").isNull()).isTrue();
        assertThat(run.get("cancellationAcknowledgedAt").isNull()).isTrue();

        // Its dispatch is withdrawn under the reason that actually applies.
        assertThat(jdbc.queryForObject(
                        "select terminal_disposition from outbox_messages where run_id = ?", String.class, runId))
                .isEqualTo("SUPPRESSED_QUEUE_TIMEOUT");
        // The terminal event carries the attempt the run really had, and the reaper's own identity.
        Map<String, Object> event = jdbc.queryForMap(
                "select * from run_lifecycle_events where run_id = ? and sequence = 2", runId);
        assertThat(event.get("attempt_id")).isNotNull();
        assertThat(event.get("actor")).isEqualTo("kaas.queue-reaper");

        // Asking to cancel a run that already timed out is a conflict, not a false cancellation: reporting it as
        // cancelled would put a cause nobody caused into an audited record.
        HttpResponse<String> conflict = cancel(tenant.bearer(), runId);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("RUN_ALREADY_TERMINAL");
        assertThat(jdbc.queryForObject(
                        "select infrastructure_outcome from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("TIMED_OUT");
    }

    @Test
    void reapingReleasesQueueCapacitySoTheSchedulerCanMoveOn() throws Exception {
        Tenant tenant = tenant();
        List<UUID> runIds = new ArrayList<>();
        for (int index = 0; index < QUEUED_LIMIT + 1; index++) {
            runIds.add(createRun(tenant));
        }
        scheduler.scheduleDue();
        assertThat(queuedRuns(tenant)).isEqualTo(QUEUED_LIMIT);
        UUID deferred = runIds.stream().filter(id -> "CREATED".equals(lifecycleOf(id))).findFirst().orElseThrow();
        awaitAllDeadlines();

        assertThat(reaper.reapExpired()).isEqualTo(QUEUED_LIMIT);
        jdbc.update("delete from run_scheduling_control where run_id = ?", deferred);

        assertThat(scheduler.scheduleDue()).isEqualTo(1);
        assertThat(lifecycleOf(deferred)).isEqualTo("QUEUED");
    }

    @Test
    @Timeout(180)
    void concurrentCancellationAndReapingProduceExactlyOneTerminalTransition() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        awaitAllDeadlines();
        int racers = 8;
        int cancellers = racers / 2;
        AtomicInteger reaped = new AtomicInteger();
        List<Integer> cancelStatuses = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier start = new CyclicBarrier(racers);

        try (var pool = Executors.newFixedThreadPool(racers)) {
            var futures = IntStream.range(0, racers)
                    .mapToObj(index -> pool.submit(() -> {
                        try {
                            start.await(60, TimeUnit.SECONDS);
                            if (index % 2 == 0) {
                                cancelStatuses.add(cancel(tenant.bearer(), runId).statusCode());
                            } else {
                                reaped.addAndGet(reaper.reapExpired());
                            }
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }))
                    .toList();
            for (var future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        }

        // Whoever won, there is exactly one terminal transition, one version bump, and one event.
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(reaped.get()).isLessThanOrEqualTo(1);
        // Exactly one disposition was written, and it agrees with the transition that actually happened.
        String outcome = jdbc.queryForObject(
                "select infrastructure_outcome from test_runs where run_id = ?", String.class, runId);
        assertThat(jdbc.queryForObject(
                        "select terminal_disposition from outbox_messages where run_id = ?", String.class, runId))
                .isEqualTo("CANCELLED".equals(outcome) ? "SUPPRESSED_CANCELLED" : "SUPPRESSED_QUEUE_TIMEOUT");

        // Losing is a contract, not just an absence. Every canceller must have come back with a decided answer:
        // 200 if it observed the cancellation, 409 if the reaper got there first. A 500 would mean a racer hit a
        // constraint instead of a compare-and-set, and asserting only on the winner would never notice.
        assertThat(cancelStatuses).hasSize(cancellers).allSatisfy(status -> assertThat(status).isIn(200, 409));
        if ("CANCELLED".equals(outcome)) {
            // A cancellation won, so every canceller — the winner and the repeats it made idempotent — saw 200.
            assertThat(cancelStatuses).containsOnly(200);
        } else {
            // The reaper won, so no cancellation may claim to have taken effect.
            assertThat(cancelStatuses).containsOnly(409);
        }
        // And the race leaves no residue: a terminal run must never keep eligibility state.
        assertThat(count("run_scheduling_control", runId)).isZero();
    }

    @Test
    void aReapingFailureBacksOffDurablyAndEventuallyQuarantinesInsteadOfLooping() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        awaitAllDeadlines();
        doThrow(new org.springframework.dao.QueryTimeoutException("database unavailable"))
                .when(terminations)
                .expire(any(), any());

        assertThat(reaper.reapExpired()).isZero();

        Map<String, Object> control = jdbc.queryForMap(
                "select * from run_scheduling_control where run_id = ?", runId);
        assertThat(control.get("failure_count")).isEqualTo(1);
        assertThat(control.get("last_failure_code")).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(control.get("quarantined_at")).isNull();
        // The run is untouched. Infrastructure being unhealthy says nothing about the run itself.
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");

        // A durable delay is what stops the next tick turning this into a hot loop.
        assertThat(reaper.reapExpired()).isZero();
        assertThat(jdbc.queryForObject(
                        "select failure_count from run_scheduling_control where run_id = ?", Integer.class, runId))
                .isEqualTo(1);

        // Spending the budget quarantines it for an operator rather than retrying forever.
        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("update run_scheduling_control set next_attempt_at = now() where run_id = ?", runId);
            reaper.reapExpired();
        }
        assertThat(jdbc.queryForObject(
                        "select quarantined_at is not null from run_scheduling_control where run_id = ?",
                        Boolean.class, runId))
                .isTrue();
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");

        // Recovery is deleting the control row: nothing about the run itself needed repairing.
        reset(terminations);
        jdbc.update("delete from run_scheduling_control where run_id = ?", runId);
        assertThat(reaper.reapExpired()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void theDatabaseRejectsEveryTerminalShapeExceptTheImplementedOnes() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        String guard = "only scheduling and early terminal transitions are supported";

        // A terminal state with no completion time is not a state. The guard rejects it first; the CHECK behind
        // it is proved separately below, because a defence that is never reached is not a defence.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3,"
                        + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'CANCELLED'"
                        + " where run_id = ?",
                runId);
        // A timeout that claims someone cancelled it, and a cancellation that claims it timed out, are both lies
        // the schema refuses to store.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'TIMED_OUT', termination_reason = 'USER_REQUESTED',"
                        + " termination_phase = 'CANCELLATION' where run_id = ?",
                runId);
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'TIMED_OUT', termination_reason = 'QUEUE_DEADLINE',"
                        + " termination_phase = 'QUEUE', cancellation_status = 'ACKNOWLEDGED',"
                        + " cancellation_requested_at = now(), cancellation_acknowledged_at = now()"
                        + " where run_id = ?",
                runId);
        // The guard still fails closed on everything past QUEUED.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'CLAIMED', run_version = 3 where run_id = ?", runId);
        // Terminalization ends a run's history; it must not rewrite the scheduling record it is ending.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'CANCELLED', termination_reason = 'USER_REQUESTED',"
                        + " termination_phase = 'CANCELLATION', cancellation_status = 'ACKNOWLEDGED',"
                        + " cancellation_requested_at = now(), cancellation_acknowledged_at = now(),"
                        + " queue_deadline_at = now() + interval '1 day' where run_id = ?",
                runId);
        // A completed run may not silently pass a quality gate nothing evaluated.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'CANCELLED', termination_reason = 'USER_REQUESTED',"
                        + " termination_phase = 'CANCELLATION', cancellation_status = 'ACKNOWLEDGED',"
                        + " cancellation_requested_at = now(), cancellation_acknowledged_at = now(),"
                        + " quality_gate_status = 'PASSED' where run_id = ?",
                runId);

        // Suppression is offered only for an unclaimed, unpublished row, and only without spending an attempt.
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_CANCELLED',"
                        + " publish_attempts = 1, last_attempt_at = now() where run_id = ?",
                runId);
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_BECAUSE_I_SAID_SO'"
                        + " where run_id = ?",
                runId);
        // The guard rejects an invented disposition first, so the CHECK behind it needs the guard out of the way
        // to be reached at all. It is real defence: the vocabulary is closed independently of the transition.
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            assertRejected(
                    "ck_outbox_terminal_disposition",
                    "update outbox_messages set terminal_disposition = 'SUPPRESSED_BECAUSE_I_SAID_SO',"
                            + " publish_attempts = 1, last_attempt_at = now(), last_failure_code = 'X'"
                            + " where run_id = ?",
                    runId);
            // A withdrawal means "not published", never "not attempted". A dispatch that failed once and was
            // then withdrawn keeps its real history — the opposite constraint made cancelling such a run
            // impossible, and platform-wide during a broker outage.
            jdbc.update(
                    "update outbox_messages set terminal_disposition = 'SUPPRESSED_CANCELLED',"
                            + " publish_attempts = 2, last_attempt_at = now(),"
                            + " last_failure_code = 'BROKER_UNAVAILABLE' where run_id = ?",
                    runId);
            assertThat(jdbc.queryForObject(
                            "select publish_attempts from outbox_messages where run_id = ?", Integer.class, runId))
                    .isEqualTo(2);
            // What it must never mean is "published". That is the one thing the relaxed constraint still
            // guarantees, and it is the whole content of "withdrawn before publication".
            assertRejected(
                    "ck_outbox_attempt_accounting",
                    "update outbox_messages set published_at = now() where run_id = ?",
                    runId);
            jdbc.update("update outbox_messages set terminal_disposition = null, publish_attempts = 0,"
                    + " last_attempt_at = null, last_failure_code = null where run_id = ?", runId);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }

        // A message that really was sent can never be recast as one that was withdrawn.
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update(
                    "update outbox_messages set published_at = now(), publish_attempts = 1,"
                            + " last_attempt_at = now() where run_id = ?",
                    runId);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_CANCELLED' where run_id = ?",
                runId);
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update(
                    "update outbox_messages set published_at = null, publish_attempts = 0,"
                            + " last_attempt_at = null where run_id = ?",
                    runId);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }

        // Now cancel for real, and prove the withdrawal cannot be undone: replaying a dispatch whose run is over
        // would send a worker to execute something nobody is waiting for.
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = null, available_at = now() where run_id = ?",
                runId);
        // And a terminal run cannot gain a new attempt afterwards. V4's per-row guard is the reachable rejection
        // here — it requires the run to be QUEUED and to name this exact attempt — with the deferred bundle
        // check's own "cannot gain additional scheduling children" sitting behind it as defence in depth.
        assertRejected(
                "initial execution attempt requires its exact QUEUED run",
                """
                insert into execution_attempts
                    (attempt_id, organization_id, project_id, run_id, attempt_number, attempt_state,
                     created_by, created_at)
                values (?, ?, ?, ?, 2, 'WAITING_FOR_CLAIM', 'kaas.scheduler', now())
                """,
                UUID.randomUUID(), tenant.organizationId(), tenant.projectId(), runId);
    }

    @Test
    void aDispatchThatAlreadyFailedToPublishIsStillWithdrawn() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // A broker outage puts every pending dispatch into retry backoff. Requiring a withdrawn message to have
        // spent no attempts made cancelling such a run impossible — and during an outage that is every run, at
        // exactly the moment the queue is backing up and the admission ceiling binds.
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update(
                    "update outbox_messages set publish_attempts = 3, last_attempt_at = now(),"
                            + " last_failure_code = 'BROKER_UNAVAILABLE',"
                            + " available_at = now() + interval '1 minute' where run_id = ?",
                    runId);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }

        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        Map<String, Object> message = jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
        assertThat(message.get("terminal_disposition")).isEqualTo("SUPPRESSED_CANCELLED");
        // The history it really has is kept. "Withdrawn before publication" is the claim; "never attempted" is
        // not, and erasing three real attempts to make the record look tidier would be a second lie.
        assertThat(message.get("publish_attempts")).isEqualTo(3);
        assertThat(message.get("last_failure_code")).isEqualTo("BROKER_UNAVAILABLE");
        // It is still not a dead letter: nothing about it says delivery failed permanently.
        assertThat(outbox.countTerminal()).isZero();
    }

    @Test
    void aDispatchAbandonedByACrashedRelayIsWithdrawnRatherThanLeftToPublishLater() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // A relay that dies mid-batch leaves a claim behind. The relay itself reclaims such a row once the lease
        // expires, so it is publishable — and if cancellation could not withdraw it, a later pass would deliver
        // it for the first time on behalf of a run that is already over. That is not the duplicate-delivery case
        // at-least-once accepts; it is the only delivery.
        // Forged with the guard disabled: no legitimate transition produces an already-expired claim, which is
        // precisely why only a crash gets you here.
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update(
                    """
                    update outbox_messages
                       set relay_claim_id = gen_random_uuid(), relay_claimed_at = now() - interval '10 min',
                           relay_claim_expires_at = now() - interval '5 min'
                     where run_id = ?
                    """,
                    runId);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }

        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        Map<String, Object> message = jdbc.queryForMap("select * from outbox_messages where run_id = ?", runId);
        assertThat(message.get("terminal_disposition")).isEqualTo("SUPPRESSED_CANCELLED");
        // The dead lease is cleared with it, so no stale claim outlives the withdrawal.
        assertThat(message.get("relay_claim_id")).isNull();
        assertThat(message.get("relay_claimed_at")).isNull();
        assertThat(message.get("relay_claim_expires_at")).isNull();
        // And the relay no longer sees anything to reclaim.
        relay.drainOnce();
        assertThat(jdbc.queryForObject(
                        "select publish_attempts from outbox_messages where run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    void aSuppressionMustBelongToTheRunAndTheReasonItClaims() throws Exception {
        Tenant tenant = tenant();
        UUID live = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(lifecycleOf(live)).isEqualTo("QUEUED");

        // Withdrawing the dispatch of a perfectly live run is a one-way door: the requeue transition refuses
        // suppressed rows, so the run would sit QUEUED with nothing to deliver until its deadline, and the
        // delivery record would name a cancellation that never happened.
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_CANCELLED' where run_id = ?",
                live);

        // Nor may a withdrawal name a reason the run did not end for.
        UUID cancelled = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(cancel(tenant.bearer(), cancelled).statusCode()).isEqualTo(200);
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update("update outbox_messages set terminal_disposition = null where run_id = ?", cancelled);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }
        assertRejected(
                "suppression",
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_QUEUE_TIMEOUT' where run_id = ?",
                cancelled);
        // The reason that run actually ended for is accepted.
        jdbc.update(
                "update outbox_messages set terminal_disposition = 'SUPPRESSED_CANCELLED' where run_id = ?",
                cancelled);
    }

    @Test
    void aTerminationCannotBeAttributedToSomeoneWhoDidNotPerformIt() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        String guard = "only scheduling and early terminal transitions are supported";

        // A system expiry pinned to a named tenant, and a tenant cancellation wearing the platform's identity,
        // are the same forgery in opposite directions. The scheduling branch has always pinned its actor; the
        // terminal branch pins both of its own.
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'TIMED_OUT', termination_reason = 'QUEUE_DEADLINE',"
                        + " termination_phase = 'QUEUE', updated_by = 'mallory@evil.example' where run_id = ?",
                runId);
        assertRejected(
                guard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'CANCELLED', termination_reason = 'USER_REQUESTED',"
                        + " termination_phase = 'CANCELLATION', cancellation_status = 'ACKNOWLEDGED',"
                        + " cancellation_requested_at = now(), cancellation_acknowledged_at = now(),"
                        + " updated_by = 'kaas.scheduler' where run_id = ?",
                runId);

        // And the platform's own namespace cannot be entered through the front door either: a token may not
        // claim a reserved subject, so no request can ever carry one into the audit trail.
        assertThat(cancel(token(tenant.organizationId(), "kaas.queue-reaper"), runId).statusCode()).isEqualTo(401);
        assertThat(cancel(token(tenant.organizationId(), "kaas.scheduler"), runId).statusCode()).isEqualTo(401);
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    void aRunCannotEndWithoutSayingSo() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // Past the deadline, so the row guard accepts the shape and the deferred bundle check is what rejects it.
        awaitAllDeadlines();
        // Scheduling's event is mandatory because the bundle check joins it. Terminalization's was enforced only
        // by application code, so a second writer or a repair script could commit a run whose history has a gap
        // exactly where its ending belongs.
        assertThatThrownBy(() -> jdbc.update(
                        "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3,"
                                + " completed_at = clock_timestamp(), updated_at = clock_timestamp(),"
                                + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'TIMED_OUT',"
                                + " termination_reason = 'QUEUE_DEADLINE', termination_phase = 'QUEUE',"
                                + " updated_by = 'kaas.queue-reaper' where run_id = ?",
                        runId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("a terminal run requires its own lifecycle event");
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
    }

    @Test
    void theOnlyReasonATenantMayGiveIsTheOneTheContractDeclares() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);

        // An open reason would let a client write its own cause into an audited record.
        assertThat(cancelWithBody(tenant.bearer(), runId, "{\"reason\":\"BUDGET_EXCEEDED\"}").statusCode())
                .isEqualTo(422);
        assertThat(cancelWithBody(tenant.bearer(), runId, "{}").statusCode()).isEqualTo(422);
        // An unknown property is refused outright rather than ignored.
        assertThat(cancelWithBody(
                                tenant.bearer(), runId, "{\"reason\":\"USER_REQUESTED\",\"force\":true}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(lifecycleOf(runId)).isEqualTo("CREATED");
        assertThat(count("run_lifecycle_events", runId)).isZero();
    }

    @Test
    void theTerminalCheckConstraintsHoldEvenWithTheLifecycleGuardOutOfTheWay() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        // The guard rejects these shapes first, so without disabling it the CHECK constraints behind it would
        // never be reached and a test asserting them would pass for the wrong reason. They are real defence: a
        // future transition that the guard learns to allow must still not be able to write an incoherent row.
        jdbc.update("alter table test_runs disable trigger test_runs_supported_update");
        try {
            assertRejected(
                    "ck_test_runs_terminal_timestamp",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3,"
                            + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'TIMED_OUT'"
                            + " where run_id = ?",
                    runId);
            // A run that ended must say why. This is also what makes the two coalesce-wrapped constraints below
            // unreachable in practice — which is why they are wrapped rather than left to rely on it.
            assertRejected(
                    "ck_test_runs_terminal_reason",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3,"
                            + " completed_at = now(), test_outcome = 'NOT_AVAILABLE',"
                            + " infrastructure_outcome = 'TIMED_OUT' where run_id = ?",
                    runId);
            assertRejected(
                    "ck_test_runs_terminal_reason_outcome",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                            + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'TIMED_OUT',"
                            + " termination_reason = 'USER_REQUESTED', termination_phase = 'CANCELLATION'"
                            + " where run_id = ?",
                    runId);
            assertRejected(
                    "ck_test_runs_termination_vocabulary",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                            + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'TIMED_OUT',"
                            + " termination_reason = 'QUEUE_DEADLINE', termination_phase = 'CANCELLATION'"
                            + " where run_id = ?",
                    runId);
            assertRejected(
                    "ck_test_runs_timeout_not_cancelled",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                            + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'TIMED_OUT',"
                            + " termination_reason = 'QUEUE_DEADLINE', termination_phase = 'QUEUE',"
                            + " cancellation_status = 'ACKNOWLEDGED', cancellation_requested_at = now(),"
                            + " cancellation_acknowledged_at = now() where run_id = ?",
                    runId);
            // An acknowledgement cannot precede the request that caused it, and a status without its timestamp
            // is not a status.
            assertRejected(
                    "ck_test_runs_cancellation_timing",
                    "update test_runs set cancellation_status = 'REQUESTED' where run_id = ?",
                    runId);
            assertRejected(
                    "ck_test_runs_cancellation_timing",
                    "update test_runs set lifecycle_state = 'COMPLETED', run_version = 3, completed_at = now(),"
                            + " test_outcome = 'NOT_AVAILABLE', infrastructure_outcome = 'CANCELLED',"
                            + " termination_reason = 'USER_REQUESTED', termination_phase = 'CANCELLATION',"
                            + " cancellation_status = 'ACKNOWLEDGED',"
                            + " cancellation_requested_at = now() + interval '1 hour',"
                            + " cancellation_acknowledged_at = now() where run_id = ?",
                    runId);
        } finally {
            jdbc.update("alter table test_runs enable trigger test_runs_supported_update");
        }
        // Nothing got through: the run is exactly as the scheduler left it.
        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(versionOf(runId)).isEqualTo(2L);
    }

    @Test
    void aLifecycleEventCannotClaimATransitionThatDidNotHappen() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        assertThat(cancel(tenant.bearer(), runId).statusCode()).isEqualTo(200);

        // The event must match the authoritative row, including who performed it: an audit trail that can be
        // written by anyone about anyone is not evidence.
        assertRejected(
                "run lifecycle event must match the authoritative transition",
                """
                insert into run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                        sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                select gen_random_uuid(), organization_id, project_id, run_id, run_version, 1,
                       'RUN_STATE_CHANGED', 'CREATED', 'COMPLETED', null, 'somebody-else', completed_at
                  from test_runs where run_id = ?
                """,
                runId);
        // And the version and sequence are the same fact written twice, so they cannot disagree.
        assertRejected(
                "ck_run_lifecycle_events_transition",
                """
                insert into run_lifecycle_events (event_id, organization_id, project_id, run_id, run_version,
                        sequence, event_type, previous_state, lifecycle_state, attempt_id, actor, occurred_at)
                select gen_random_uuid(), organization_id, project_id, run_id, 2, 2, 'RUN_STATE_CHANGED',
                       'CREATED', 'COMPLETED', null, updated_by, completed_at
                  from test_runs where run_id = ?
                """,
                runId);
    }

    @Test
    void terminationRefusesEveryPhaseAWorkerWouldOwn() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        // CLAIMED is unreachable through any implemented path, so it has to be forged to test the refusal at all.
        // The point is that the use case does not rely on the database alone.
        jdbc.update("alter table test_runs disable trigger test_runs_supported_update");
        try {
            jdbc.update("update test_runs set lifecycle_state = 'STOPPING' where run_id = ?", runId);
        } finally {
            jdbc.update("alter table test_runs enable trigger test_runs_supported_update");
        }

        HttpResponse<String> response = cancel(tenant.bearer(), runId);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("RUN_NOT_CANCELLABLE");
        assertThat(lifecycleOf(runId)).isEqualTo("STOPPING");
        assertThat(count("run_lifecycle_events", runId)).isZero();
    }

    @Test
    void terminationMetricsCarryNoTenantIdentity() throws Exception {
        Tenant tenant = tenant();
        assertThat(cancel(tenant.bearer(), createRun(tenant)).statusCode()).isEqualTo(200);

        // Reason is the only dimension. Organization, project, run, and principal would all be unbounded, and a
        // metrics backend is the last place a tenant identifier should end up.
        var counter = meterRegistry.find("kaas.run.terminated").tag("reason", "USER_REQUESTED").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).extracting(Tag::getKey).containsExactly("reason");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Waits until every currently QUEUED run has passed its own deadline, read from the database rather than
     * computed here — the deadline is stamped by the database clock, so an application-side calculation could
     * wait for the wrong instant.
     *
     * <p>It waits for all of them, not for one. Each run is queued in its own transaction, so their deadlines
     * differ by however long a scheduling transaction takes; waiting on the first run's deadline and then
     * asserting that several were reaped passed only when the poll interval happened to overshoot the rest.
     */
    private void awaitAllDeadlines() {
        Awaitility.await()
                .atMost(QUEUE_TIMEOUT.plusSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select count(*) = 0 from test_runs"
                                + " where lifecycle_state = 'QUEUED' and queue_deadline_at > now()",
                        Boolean.class)));
    }

    /**
     * Gives a different run a real relay dead letter, so an assertion that suppression leaves the dead-letter
     * count alone has something to exclude rather than comparing zero against zero. Written with the guard
     * disabled because only the relay may legitimately produce one.
     *
     * @return the dead-letter count after seeding
     */
    private long seedDeadLetter(Tenant tenant) throws Exception {
        UUID other = createRun(tenant);
        assertThat(scheduler.scheduleDue()).isEqualTo(1);
        jdbc.update("alter table outbox_messages disable trigger outbox_messages_guard");
        try {
            jdbc.update(
                    "update outbox_messages set terminal_disposition = 'RETRIES_EXHAUSTED',"
                            + " publish_attempts = 12, last_attempt_at = now(),"
                            + " last_failure_code = 'BROKER_UNAVAILABLE' where run_id = ?",
                    other);
        } finally {
            jdbc.update("alter table outbox_messages enable trigger outbox_messages_guard");
        }
        return outbox.countTerminal();
    }

    private void assertRejected(String reason, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(reason);
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

    private long queuedRuns(Tenant tenant) {
        return jdbc.queryForObject(
                "select count(*) from test_runs where organization_id = ? and lifecycle_state = 'QUEUED'",
                Long.class, tenant.organizationId());
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

    private HttpResponse<String> cancel(String bearer, UUID runId) throws Exception {
        return cancelWithBody(bearer, runId, "{\"reason\":\"USER_REQUESTED\"}");
    }

    private HttpResponse<String> cancelWithBody(String bearer, UUID runId, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + "/api/v1/runs/" + runId + "/cancellations"))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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

    /** Builds a fresh organization with the full authorized input chain a run needs. */
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
                                "name", "Terminal feature",
                                "logicalPath", "features/t-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: a\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Terminal environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Terminal profile");
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
        return token(organizationId, "terminal-test");
    }

    private static String token(UUID organizationId, String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
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
