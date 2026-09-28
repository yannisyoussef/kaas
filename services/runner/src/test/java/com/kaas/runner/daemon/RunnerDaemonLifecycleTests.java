package com.kaas.runner.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.execution.ExecutionLoop;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production runner's lifecycle, with the host and the control plane replaced and nothing else.
 *
 * <p>The daemon under test is the real {@link RunnerDaemon}: the same startup sequence, intake loop, readiness,
 * shutdown and backoff a deployment runs. What is replaced is only what it drives -- Docker, the gates, the
 * execution loop and the control plane -- so each property here is observable in seconds and deterministically.
 * The composition that wires the real ones is exercised by {@code ProductionRunnerCompositionTests}.
 */
@Timeout(60)
class RunnerDaemonLifecycleTests {
    private static final String WORKER = "kaas.worker.lifecycle";
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @TempDir
    Path directory;

    private Harness harness;

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    // ---------------------------------------------------------------- startup

    @Test
    void readyOnlyAfterEveryStartupStepAndInTheContractedOrder() throws Exception {
        harness = new Harness();
        CountDownLatch reconciling = new CountDownLatch(1);
        CountDownLatch releaseReconcile = new CountDownLatch(1);
        harness.reconcile = () -> {
            harness.order.add("reconcile");
            reconciling.countDown();
            try {
                releaseReconcile.await();
            } catch (InterruptedException stopped) {
                throw new IllegalStateException(stopped);
            }
            return 0;
        };
        harness.start();

        assertThat(reconciling.await(10, TimeUnit.SECONDS)).isTrue();
        // Mid-reconciliation: host checked, evidence produced -- and still not ready, and nobody asked for work.
        Thread.sleep(300);
        assertThat(harness.daemon.readiness().ready()).isFalse();
        assertThat(harness.daemon.readiness().report().get("STARTUP_RECONCILIATION")).isEqualTo("NOT_YET_ESTABLISHED");
        assertThat(harness.api.requestsTo("/internal/v1/assignments")).isEmpty();
        assertThat(harness.api.requestsTo("/internal/v1/sandbox-attestations")).isEmpty();

        releaseReconcile.countDown();
        awaitTrue(() -> harness.daemon.readiness().ready());
        assertThat(harness.order).containsSubsequence("preflight", "assess", "reconcile", "token", "submit");
        awaitTrue(() -> !harness.api.requestsTo("/internal/v1/assignments").isEmpty());
    }

    @Test
    void aHostWithoutTheConfiguredRuntimeNeverBecomesReadyAndNeverAsksForWork() throws Exception {
        harness = new Harness();
        harness.preflight = () -> {
            harness.readiness.holds(Readiness.Condition.DOCKER);
            harness.readiness.set(Readiness.Condition.RUNTIME, false, "RUNTIME_NOT_REGISTERED");
            harness.readiness.holds(Readiness.Condition.IMAGES);
            return Optional.empty();
        };
        harness.start();

        Thread.sleep(2_000);
        assertThat(harness.daemon.readiness().ready()).isFalse();
        assertThat(harness.daemon.readiness().report().get("RUNTIME")).isEqualTo("RUNTIME_NOT_REGISTERED");
        assertThat(harness.daemon.live()).as("a missing runtime is not a reason to be restarted").isTrue();
        assertThat(harness.api.requests).isEmpty();
        assertThat(harness.executed).isEmpty();
    }

    // ---------------------------------------------------------------- intake

    @Test
    void aClaimedAssignmentIsExecutedInTheRunnersOwnName() throws Exception {
        harness = new Harness();
        UUID runId = UUID.randomUUID();
        harness.api.assign(runId);
        harness.start();

        awaitTrue(() -> harness.executed.contains(runId));
        FakeControlPlane.Request claim = harness.api.requestsTo("/internal/v1/assignments").get(0);
        // Nothing in the body chooses a worker; the identity is the credential.
        assertThat(claim.body()).isEqualTo("{}");
        assertThat(claim.authorization()).startsWith("Bearer ");
        assertThat(harness.metrics.count("kaas_runner_claim_success_total")).isEqualTo(1);
        awaitTrue(() -> harness.metrics.count("kaas_runner_execution_completed_total{status=\"COMPLETED\"}") == 1);
    }

    @Test
    void anIdleRunnerLongPollsRatherThanSpinning() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_CLAIM_WAIT", "PT2S");
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());

        Thread.sleep(5_000);
        int claims = harness.api.requestsTo("/internal/v1/assignments").size();
        int waits = harness.api.requestsTo("/internal/v1/assignments/waits").size();
        // Five idle seconds against a two-second wait is a handful of each -- not hundreds.
        assertThat(claims).isBetween(1, 5);
        assertThat(waits).isBetween(1, 5);
    }

    @Test
    void neverMoreAssignmentsThanSlots() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_MAX_CONCURRENCY", "2");
        CountDownLatch release = new CountDownLatch(1);
        harness.execution = runId -> await(release);
        for (int index = 0; index < 5; index++) {
            harness.api.assign(UUID.randomUUID());
        }
        harness.start();

        awaitTrue(() -> harness.executed.size() == 2);
        Thread.sleep(1_000);
        assertThat(harness.executed).hasSize(2);
        assertThat(harness.maxConcurrent.get()).isEqualTo(2);
        // A runner with no free slot does not ask; the claim is the backpressure.
        assertThat(harness.api.requestsTo("/internal/v1/assignments")).hasSize(2);

        release.countDown();
        awaitTrue(() -> harness.executed.size() == 5);
        assertThat(harness.maxConcurrent.get()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- outage and identity

    @Test
    void anApiOutageMakesTheRunnerNotReadyAndBacksOffWithoutDyingAndItRecovers() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_CLAIM_WAIT", "PT1S");
        harness.environment.put("KAAS_RUNNER_CLAIM_BACKOFF_INITIAL", "PT0.2S");
        harness.environment.put("KAAS_RUNNER_CLAIM_BACKOFF_MAX", "PT1S");
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());

        harness.api.down.set(true);
        awaitTrue(() -> !harness.daemon.readiness().ready());
        assertThat(harness.daemon.readiness().report().get("CONTROL_PLANE")).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
        int before = harness.api.requests.size();
        Thread.sleep(4_000);
        int during = harness.api.requests.size() - before;
        assertThat(harness.daemon.live()).isTrue();
        // Backing off to one-second waits: a few requests in four seconds, never a hot loop.
        assertThat(during).isBetween(1, 12);
        assertThat(harness.metrics.count("kaas_runner_claim_error_total")).isPositive();

        harness.api.down.set(false);
        UUID runId = UUID.randomUUID();
        harness.api.assign(runId);
        awaitTrue(() -> harness.executed.contains(runId));
        assertThat(harness.daemon.readiness().ready()).isTrue();
    }

    @Test
    void withoutAValidCredentialTheRunnerIsNotReadyClaimsNothingAndSendsNothingUnauthenticated() throws Exception {
        harness = new Harness();
        AtomicBoolean issuerUp = new AtomicBoolean(false);
        harness.tokens = () -> {
            if (!issuerUp.get()) {
                throw new ServiceIdentity.ServiceIdentityUnavailable("TOKEN_ENDPOINT_UNREACHABLE");
            }
            return harness.freshToken();
        };
        harness.api.assign(UUID.randomUUID());
        harness.start();

        Thread.sleep(2_000);
        assertThat(harness.daemon.readiness().ready()).isFalse();
        assertThat(harness.daemon.readiness().report().get("SERVICE_IDENTITY")).isEqualTo("TOKEN_ENDPOINT_UNREACHABLE");
        assertThat(harness.daemon.live()).isTrue();
        // No request of any kind without a credential: there is no unauthenticated fallback.
        assertThat(harness.api.requests).isEmpty();

        issuerUp.set(true);
        awaitTrue(() -> harness.executed.size() == 1);
        assertThat(harness.api.requests).allSatisfy(request -> assertThat(request.authorization()).startsWith("Bearer "));
    }

    @Test
    void anExpiredCredentialIsNeverPresentedAndAnExpiringOneIsReplacedBeforeItLapses() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        AtomicInteger issued = new AtomicInteger();
        ServiceIdentity identity = new ServiceIdentity(WORKER, () -> {
            issued.incrementAndGet();
            return new ServiceIdentity.Token(
                    FakeControlPlane.token(WORKER, clock.instant().plusSeconds(600)), WORKER,
                    clock.instant().plusSeconds(600));
        }, clock, new RunnerMetrics(), "runner");

        String first = identity.header();
        clock.advance(Duration.ofSeconds(300));
        assertThat(identity.header()).isEqualTo(first);
        // Past 70% of its life the token is replaced, before any request could carry it into expiry.
        clock.advance(Duration.ofSeconds(150));
        assertThat(identity.header()).isNotEqualTo(first);
        assertThat(issued.get()).isEqualTo(2);

        ServiceIdentity stale = new ServiceIdentity(WORKER, () -> new ServiceIdentity.Token(
                FakeControlPlane.token(WORKER, clock.instant().minusSeconds(1)), WORKER, clock.instant().minusSeconds(1)),
                clock, new RunnerMetrics(), "runner");
        assertThat(stale.available()).isFalse();
        assertThat(stale.lastFailure()).isEqualTo("ISSUED_EXPIRED");

        ServiceIdentity someoneElse = new ServiceIdentity(WORKER, () -> new ServiceIdentity.Token(
                FakeControlPlane.token("kaas.worker.other", clock.instant().plusSeconds(600)), "kaas.worker.other",
                clock.instant().plusSeconds(600)), clock, new RunnerMetrics(), "runner");
        assertThat(someoneElse.available()).isFalse();
        assertThat(someoneElse.lastFailure()).isEqualTo("SUBJECT_MISMATCH");
    }

    @Test
    void aShortLivedCredentialIsReplacedBeforeItStopsBeingPresentableLeavingNoGap() throws Exception {
        // Sixty seconds, as the deployment gate issues. Every second of its life must yield a presentable token.
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        AtomicInteger issued = new AtomicInteger();
        ServiceIdentity identity = new ServiceIdentity(WORKER, () -> {
            issued.incrementAndGet();
            Instant expiry = clock.instant().plusSeconds(60);
            return new ServiceIdentity.Token(FakeControlPlane.token(WORKER, expiry), WORKER, expiry);
        }, clock, new RunnerMetrics(), "runner");

        for (int second = 0; second <= 180; second++) {
            assertThat(identity.available()).as("presentable at t=%ds", second).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }
        // Replaced repeatedly, and never more than once a second.
        assertThat(issued.get()).isBetween(4, 181);
    }

    // ---------------------------------------------------------------- evidence

    @Test
    void evidenceTheControlPlaneRefusesLeavesTheRunnerNotReady() throws Exception {
        harness = new Harness();
        harness.api.attestationAccepted.set(false);
        harness.start();

        awaitTrue(() -> harness.api.requestsTo("/internal/v1/sandbox-attestations").size() >= 2);
        assertThat(harness.daemon.readiness().ready()).isFalse();
        assertThat(harness.daemon.readiness().report().get("ATTESTATION"))
                .isEqualTo("REFUSED_RUNTIME_IMPLEMENTATION_MISMATCH");
        assertThat(harness.api.requestsTo("/internal/v1/assignments")).isEmpty();
    }

    @Test
    void aRuntimeReplacedUnderAcceptedEvidenceIsNoticedAtTheNextTickAndReMeasured() throws Exception {
        harness = new Harness();
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());
        int assessmentsBefore = harness.assessments.get();

        // runsc replaced on the host. The next tick measures it, sees evidence describing a different binary,
        // stops taking work at once and asks for a fresh measurement -- which the control plane refuses.
        harness.api.attestationAccepted.set(false);
        harness.measuredDigest.set("sha256:" + "b".repeat(64));

        awaitTrue(() -> !harness.daemon.readiness().ready());
        assertThat(harness.daemon.readiness().report().get("RUNTIME_DIGEST_MATCH")).isIn("RUNTIME_CHANGED",
                "RUNTIME_NOT_ACCEPTED");
        awaitTrue(() -> harness.assessments.get() > assessmentsBefore);
        assertThat(harness.metrics.render()).contains("kaas_runner_runtime_digest_match 0");
    }

    @Test
    void evidenceThatLapsesMakesTheRunnerNotReadyWithoutKillingIt() throws Exception {
        harness = new Harness();
        harness.api.usableUntil = Instant.now().plus(AttestationRefresher.EXPIRY_MARGIN).plusSeconds(3);
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());

        awaitTrue(() -> !harness.daemon.readiness().ready());
        assertThat(harness.daemon.readiness().report().get("ATTESTATION")).isEqualTo("EXPIRED");
        assertThat(harness.daemon.live()).isTrue();
    }

    @Test
    void reconciliationKeepsRunningOnItsIntervalAfterStartup() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_RECONCILE_INTERVAL", "PT0.3S");
        AtomicInteger passes = new AtomicInteger();
        harness.reconcile = () -> {
            passes.incrementAndGet();
            return 0;
        };
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());
        int atReady = passes.get();

        awaitTrue(() -> passes.get() >= atReady + 3);
        assertThat(harness.metrics.count("kaas_runner_reconcile_total")).isGreaterThanOrEqualTo(atReady + 3);
    }

    @Test
    void evidenceIsReMeasuredAndResubmittedOnItsIntervalWithoutARestart() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL", "PT0.5S");
        harness.environment.put("KAAS_RUNNER_ATTESTATION_RETRY_INTERVAL", "PT0.5S");
        harness.start();
        awaitTrue(() -> harness.daemon.readiness().ready());
        int submissions = harness.api.requestsTo("/internal/v1/sandbox-attestations").size();
        int assessments = harness.assessments.get();

        // Each refresh is a new assessment AND a new submission -- not a resend of the one from startup.
        awaitTrue(() -> harness.api.requestsTo("/internal/v1/sandbox-attestations").size() >= submissions + 3);
        assertThat(harness.assessments.get()).isGreaterThanOrEqualTo(assessments + 3);
        assertThat(harness.metrics.count("kaas_runner_attestation_refresh_total{result=\"ACCEPTED\"}"))
                .isGreaterThanOrEqualTo(4);
        assertThat(harness.daemon.readiness().ready()).isTrue();
    }

    // ---------------------------------------------------------------- shutdown

    @Test
    void shutdownAbandonsALongPollAtOnceAndClaimsNothingAfterItBegins() throws Exception {
        harness = new Harness();
        harness.environment.put("KAAS_RUNNER_CLAIM_WAIT", "PT20S");
        harness.start();
        awaitTrue(() -> harness.api.inFlightWaits.get() == 1);

        long began = System.nanoTime();
        harness.daemon.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - began);

        assertThat(took).as("a twenty-second wait must not hold shutdown").isLessThan(Duration.ofSeconds(5));
        assertThat(harness.daemon.readiness().ready()).isFalse();
        assertThat(harness.api.requestsTo("/internal/v1/assignments"))
                .allSatisfy(claim -> assertThat(claim.atNanos()).isLessThan(began));
        assertThat(harness.cleanedUp.get()).isTrue();
        assertThat(harness.closed.get()).isTrue();
    }

    @Test
    void aClaimInFlightWhenShutdownBeginsIsCompletedAndItsAssignmentDrainedNotDropped() throws Exception {
        harness = new Harness();
        harness.api.claimDelayMillis.set(1_500);
        UUID runId = UUID.randomUUID();
        harness.api.assign(runId);
        harness.start();
        awaitTrue(() -> harness.api.requestsTo("/internal/v1/assignments").size() == 1);

        harness.daemon.stop();

        // The claim finished, the assignment it returned was executed to completion, and nothing further was
        // claimed. Dropping it would have left a run leased to a runner that had forgotten it.
        assertThat(harness.executed).containsExactly(runId);
        assertThat(harness.completed).containsExactly(runId);
        assertThat(harness.api.requestsTo("/internal/v1/assignments")).hasSize(1);
    }

    @Test
    void nothingTheRunnerStartedOutlivesItsShutdown() throws Exception {
        harness = new Harness();
        CountDownLatch release = new CountDownLatch(1);
        harness.environment.put("KAAS_RUNNER_SHUTDOWN_TIMEOUT", "PT1S");
        harness.execution = runId -> await(release);
        harness.api.assign(UUID.randomUUID());
        harness.start();
        awaitTrue(() -> harness.executed.size() == 1);

        harness.daemon.stop();

        // The execution that would not finish was interrupted after the drain deadline; every thread the daemon
        // started has ended.
        assertThat(harness.interrupted.get()).isTrue();
        awaitTrue(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().startsWith("kaas-runner-") && thread.isAlive()
                        && !thread.getName().equals("kaas-runner-health")));
        assertThat(harness.daemon.awaitStopped(Duration.ZERO)).isTrue();
        assertThat(harness.daemon.live()).isFalse();
    }

    @Test
    void metricsCarryNoRunTenantOrWorkerIdentity() throws Exception {
        harness = new Harness();
        List<UUID> runs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID runId = UUID.randomUUID();
            runs.add(runId);
            harness.api.assign(runId);
        }
        harness.start();
        awaitTrue(() -> harness.completed.size() == 3);

        String exposition = harness.metrics.render();
        assertThat(exposition).contains("kaas_runner_claim_success_total 3");
        assertThat(exposition).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(exposition).doesNotContain(WORKER).doesNotContain("127.0.0.1");
        runs.forEach(run -> assertThat(exposition).doesNotContain(run.toString()));
        // And nothing claims a broker exists on this side.
        assertThat(exposition.toLowerCase()).doesNotContain("rabbit").doesNotContain("amqp").doesNotContain("consumer");
    }

    // ---------------------------------------------------------------- harness

    @FunctionalInterface
    interface Execution {
        void run(UUID runId) throws Exception;
    }

    @FunctionalInterface
    interface Tokens {
        ServiceIdentity.Token fetch() throws ServiceIdentity.ServiceIdentityUnavailable;
    }

    final class Harness implements AutoCloseable {
        final FakeControlPlane api;
        final Map<String, String> environment = new HashMap<>();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<UUID> executed = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<UUID> completed = new ConcurrentLinkedQueue<>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final AtomicInteger assessments = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean cleanedUp = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<String> measuredDigest = new AtomicReference<>(DIGEST);
        final Readiness readiness = new Readiness();
        final RunnerMetrics metrics = new RunnerMetrics();
        RunnerDaemon.Preflight preflight;
        RunnerDaemon.Reconciler reconcile = () -> {
            order.add("reconcile");
            return 0;
        };
        Execution execution = runId -> { };
        Tokens tokens = this::freshToken;
        RunnerDaemon daemon;

        Harness() throws Exception {
            api = new FakeControlPlane();
            Path tokenFile = Files.writeString(directory.resolve("token"), "unused");
            environment.putAll(Map.of(
                    "KAAS_RUNNER_WORKER_ID", WORKER,
                    "KAAS_RUNNER_API_URL", api.uri().toString(),
                    "KAAS_RUNNER_TOKEN_FILE", tokenFile.toString(),
                    "KAAS_RUNNER_ATTESTATION_KEY_ID", "kaas-test-key-1",
                    "KAAS_RUNNER_RUNTIME_SUBJECT", "kaas.runtime.test",
                    "KAAS_RUNNER_PROBE_IMAGE", "sha256:" + "1".repeat(64),
                    "KAAS_RUNNER_ENGINE_IMAGE", "sha256:" + "2".repeat(64),
                    "KAAS_RUNNER_CLAIM_WAIT", "PT1S",
                    "KAAS_RUNNER_CLAIM_BACKOFF_INITIAL", "PT0.2S",
                    "KAAS_RUNNER_CLAIM_BACKOFF_MAX", "PT1S"));
            preflight = () -> {
                order.add("preflight");
                readiness.holds(Readiness.Condition.DOCKER);
                readiness.holds(Readiness.Condition.RUNTIME);
                readiness.holds(Readiness.Condition.IMAGES);
                return Optional.of(measuredDigest.get());
            };
        }

        ServiceIdentity.Token freshToken() {
            Instant expiry = Instant.now().plusSeconds(900);
            return new ServiceIdentity.Token(FakeControlPlane.token(WORKER, expiry), WORKER, expiry);
        }

        void start() {
            RunnerConfiguration configuration = RunnerConfiguration.fromEnvironment(environment);
            Clock clock = Clock.systemUTC();
            HttpClient http = HttpClient.newHttpClient();
            ServiceIdentity identity = new ServiceIdentity(WORKER, () -> {
                order.add("token");
                return tokens.fetch();
            }, clock, metrics, "runner");
            ControlPlaneClient client = new ControlPlaneClient(http, configuration.apiBaseUri(), identity,
                    Duration.ofSeconds(5), ControlPlaneClient.Sleeper.real());
            AttestationRefresher attestation = new AttestationRefresher(() -> {
                order.add("assess");
                assessments.incrementAndGet();
                return new AttestationRefresher.Assessment("{}", measuredDigest.get());
            }, client, readiness, metrics, clock, JsonMapper.builder().build());
            api.onRequest = path -> {
                if (path.equals("/internal/v1/sandbox-attestations")) {
                    order.add("submit");
                }
            };
            daemon = new RunnerDaemon(new RunnerDaemon.Parts(
                    configuration, readiness, metrics, client, identity,
                    () -> preflight.check(),
                    attestation,
                    () -> reconcile.reconcile(),
                    () -> cleanedUp.set(true),
                    (runId, attemptId, epoch) -> {
                        executed.add(runId);
                        int now = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(now, Math::max);
                        try {
                            execution.run(runId);
                            completed.add(runId);
                            return ExecutionLoop.ExecutionReport.completed("PASSED");
                        } catch (InterruptedException stopped) {
                            interrupted.set(true);
                            return ExecutionLoop.ExecutionReport.authorityLost("SHUTDOWN");
                        } catch (Exception failed) {
                            throw new IllegalStateException(failed);
                        } finally {
                            concurrent.decrementAndGet();
                        }
                    },
                    () -> closed.set(true),
                    clock,
                    Duration.ofMillis(300)));
            daemon.start();
        }

        @Override
        public void close() {
            if (daemon != null) {
                daemon.stop();
            }
            api.close();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        latch.await();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 30s");
            }
            Thread.sleep(25);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
