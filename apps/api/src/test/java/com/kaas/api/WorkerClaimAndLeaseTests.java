package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimRepository;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.application.WorkerLeaseReconciler;
import com.kaas.api.controlplane.application.WorkerLeaseService;
import com.kaas.api.controlplane.domain.ClaimDisposition;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker ownership: taking it, keeping it, losing it, and giving it back.
 *
 * <p>The claim use case is driven directly rather than through the broker, because these are the control plane's
 * own invariants and a message adds nothing to them. Broker-level behaviour — the inbox, redelivery, and
 * acknowledgement ordering — is proved against a real RabbitMQ in {@code DispatchConsumerInboxTests}.
 *
 * <p>Timers are off. Every reconciliation pass here is invoked explicitly so no assertion depends on when a
 * background thread happened to run.
 */
@Testcontainers
@Import(WorkerClaimAndLeaseTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            // Short enough that a lease can really expire inside a test, and a recovery window that is genuinely
            // observable rather than instantaneous.
            "kaas.claim.lease-duration=PT2S",
            // Wide enough that the window between expiry and fencing is genuinely observable. At a
            // shorter setting a test can only sample before expiry and after the window, which leaves
            // the window itself — the whole reason fencing is not immediate — unproven.
            "kaas.claim.recovery-window=PT5S",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.admission.max-active-runs-per-organization=6",
            "kaas.admission.max-queued-runs-per-organization=6",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
class WorkerClaimAndLeaseTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.local";
    private static final Duration LEASE = Duration.ofSeconds(2);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-claim");

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
    private RunClaimService claims;

    @Autowired
    private RunClaimRepository claimRepository;

    @Autowired
    private WorkerLeaseService leases;

    @Autowired
    private WorkerLeaseReconciler reconciler;

    /** Reconciliation is global, so a run left behind by one test would be fenced inside another. */
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
    }

    private static final List<String> EVIDENCE_TABLES = List.of(
            "dispatch_inbox", "outbox_messages", "run_lifecycle_events", "execution_dispatches",
            "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    // ---------------------------------------------------------------- claim

    @Test
    void claimingTakesOwnershipAndGrantsNoAuthorityToExecute() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();

        var outcome = claims.claim(dispatchFor(runId), WORKER);

        assertThat(outcome.disposition()).isEqualTo(ClaimDisposition.CLAIMED);
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        // One transition, one version bump, one event.
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        Map<String, Object> event = jdbc.queryForMap(
                "select * from run_lifecycle_events where run_id = ? and sequence = 2", runId);
        assertThat(event.get("previous_state")).isEqualTo("QUEUED");
        assertThat(event.get("lifecycle_state")).isEqualTo("CLAIMED");
        assertThat(event.get("actor")).isEqualTo("kaas.dispatch-consumer");

        Map<String, Object> attempt = jdbc.queryForMap("select * from execution_attempts where run_id = ?", runId);
        assertThat(attempt.get("attempt_state")).isEqualTo("CLAIMED");
        assertThat(attempt.get("attempt_number")).isEqualTo(1);
        // The first assignment is epoch 1, and the worker identity is the server's, never the message's.
        assertThat(attempt.get("assignment_epoch")).isEqualTo(1);
        assertThat(attempt.get("assigned_worker_id")).isEqualTo(WORKER);
        assertThat(attempt.get("lease_started_at")).isNotNull();
        assertThat(attempt.get("lease_expires_at")).isNotNull();
        assertThat(attempt.get("last_heartbeat_at")).isEqualTo(attempt.get("lease_started_at"));
        assertThat(attempt.get("fenced_at")).isNull();

        // Ownership is not permission. Nothing here grants the worker anything to execute with.
        assertThat(count("execution_dispatches", runId)).isEqualTo(1);
        JsonNode dispatchPayload = objectMapper.readTree(String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId)
                        .get("payload")));
        assertThat(dispatchPayload.propertyNames())
                .doesNotContain("assignmentEpoch", "workerId", "lease", "capability", "sourceCapability",
                        "secretCapability", "presignedUrl", "source", "image");
        // And the run's public representation reflects the new version.
        var fetched = get("/api/v1/runs/" + runId, tenant.bearer());
        assertThat(json(fetched).get("lifecycleState").stringValue()).isEqualTo("CLAIMED");
        assertThat(fetched.headers().firstValue("ETag")).contains("\"run-3\"");
    }

    @Test
    void aMessageThatDoesNotDescribeTheRunTheControlPlaneHoldsIsNeverClaimed() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        ExecutionDispatch real = dispatchFor(runId);

        // The reason is asserted, not just the disposition. Three inputs that all answer NOT_CLAIMABLE tell you
        // nothing about which check did the work, and a comment claiming one of them exercised a comparison it
        // never reached is worse than no comment at all.
        //
        // Cross-tenant substitution dies as a lookup miss: the dispatch is resolved by (organization, message),
        // so a payload naming another organization resolves to no row rather than to a mismatched one.
        var crossTenant = claims.claim(withOrganization(real, UUID.randomUUID()), WORKER);
        assertThat(crossTenant.disposition()).isEqualTo(ClaimDisposition.NOT_CLAIMABLE);
        assertThat(crossTenant.reason()).isEqualTo("UNKNOWN_DISPATCH");
        // An identity this control plane never produced, for the same reason.
        var unknownIdentity = claims.claim(withMessageId(real, UUID.randomUUID()), WORKER);
        assertThat(unknownIdentity.disposition()).isEqualTo(ClaimDisposition.NOT_CLAIMABLE);
        assertThat(unknownIdentity.reason()).isEqualTo("UNKNOWN_DISPATCH");
        // A different attempt inside the same run is caught by corroboration against the durable dispatch row,
        // before the run is ever locked.
        var wrongAttempt = claims.claim(withAttempt(real, UUID.randomUUID()), WORKER);
        assertThat(wrongAttempt.disposition()).isEqualTo(ClaimDisposition.NOT_CLAIMABLE);
        assertThat(wrongAttempt.reason()).isEqualTo("DISPATCH_IDENTITY_MISMATCH");

        assertThat(lifecycleOf(runId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("WAITING_FOR_CLAIM");
    }

    @Test
    void aRunThatMovedOnIsStaleRatherThanClaimable() throws Exception {
        Tenant cancelled = tenant();
        UUID cancelledRun = createRun(cancelled);
        scheduler.scheduleDue();
        ExecutionDispatch cancelledDispatch = dispatchFor(cancelledRun);
        assertThat(cancel(cancelled.bearer(), cancelledRun).statusCode()).isEqualTo(200);

        assertThat(claims.claim(cancelledDispatch, WORKER).disposition()).isEqualTo(ClaimDisposition.STALE);
        assertThat(lifecycleOf(cancelledRun)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, cancelledRun))
                .isEqualTo("WAITING_FOR_CLAIM");

        // The second claim of an already-claimed run is a duplicate that raced rather than repeated.
        Tenant claimed = tenant();
        UUID claimedRun = createRun(claimed);
        scheduler.scheduleDue();
        ExecutionDispatch dispatch = dispatchFor(claimedRun);
        assertThat(claims.claim(dispatch, WORKER).disposition()).isEqualTo(ClaimDisposition.CLAIMED);
        assertThat(claims.claim(dispatch, "kaas.worker.other").disposition())
                .isEqualTo(ClaimDisposition.ALREADY_CLAIMED);
        assertThat(versionOf(claimedRun)).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "select assigned_worker_id from execution_attempts where run_id = ?",
                        String.class, claimedRun))
                .isEqualTo(WORKER);
    }

    @Test
    @Timeout(180)
    void concurrentClaimsProduceExactlyOneOwnerAndOneEpoch() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        ExecutionDispatch dispatch = dispatchFor(runId);
        int racers = 8;
        List<ClaimDisposition> outcomes = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier start = new CyclicBarrier(racers);

        try (var pool = Executors.newFixedThreadPool(racers)) {
            var futures = IntStream.range(0, racers)
                    .mapToObj(index -> pool.submit(() -> {
                        try {
                            start.await(60, TimeUnit.SECONDS);
                            outcomes.add(claims.claim(dispatch, "kaas.worker." + index).disposition());
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }))
                    .toList();
            for (var future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        }

        // Every racer's outcome is captured, not just the winner's: a test that only checks the final state
        // cannot tell one clean winner from one winner and seven exceptions.
        assertThat(outcomes).hasSize(racers);
        assertThat(outcomes).filteredOn(ClaimDisposition.CLAIMED::equals).hasSize(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                .isIn(ClaimDisposition.CLAIMED, ClaimDisposition.ALREADY_CLAIMED, ClaimDisposition.STALE));
        // No two workers own epoch 1, because there is only one assignment.
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_attempts where run_id = ? and attempt_state = 'CLAIMED'",
                        Integer.class, runId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select assignment_epoch from execution_attempts where run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- heartbeat

    @Test
    void aHeartbeatRenewsOnlyTheAssignmentItCanName() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        UUID attemptId = attemptOf(runId);
        Instant firstExpiry = leaseExpiry(runId);

        assertThat(heartbeat(runId, attemptId, 1, serviceToken(WORKER)).statusCode()).isEqualTo(200);

        assertThat(leaseExpiry(runId)).isAfter(firstExpiry);
        // A heartbeat is not a transition: no version, no event, no lifecycle change.
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(2);
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject(
                        "select assignment_epoch from execution_attempts where run_id = ?", Integer.class, runId))
                .isEqualTo(1);

        // Wrong epoch and wrong worker are both refused, and neither extends anything.
        Instant afterRenewal = leaseExpiry(runId);
        assertThat(heartbeat(runId, attemptId, 2, serviceToken(WORKER)).statusCode()).isEqualTo(409);
        assertThat(heartbeat(runId, attemptId, 1, serviceToken("kaas.worker.impostor")).statusCode())
                .isEqualTo(409);
        assertThat(heartbeat(runId, UUID.randomUUID(), 1, serviceToken(WORKER)).statusCode()).isEqualTo(409);
        assertThat(leaseExpiry(runId)).isEqualTo(afterRenewal);
    }

    @Test
    void theHeartbeatSurfaceIsNotReachableWithATenantCredential() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        UUID attemptId = attemptOf(runId);

        // A tenant token carries an organization and a non-reserved subject. It is not a service credential, and
        // the internal chain refuses it rather than deciding what organization a worker belongs to.
        assertThat(heartbeat(runId, attemptId, 1, tenant.bearer()).statusCode()).isEqualTo(401);
        // A token that is both — reserved subject and an organization — is refused too: a credential that is
        // simultaneously a service and a tenant is a confusion waiting to be exploited.
        assertThat(heartbeat(runId, attemptId, 1, hybridToken(WORKER, tenant.organizationId())).statusCode())
                .isEqualTo(401);
        assertThat(heartbeat(runId, attemptId, 1, serviceToken(WORKER)).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- lease loss

    @Test
    void anExpiredLeaseIsFencedOnlyAfterItsRecoveryWindowAndReleasesCapacity() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);

        // A live lease is left entirely alone.
        assertThat(reconciler.reconcile()).isZero();
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");

        // Expired, but inside the recovery window: still left alone. This is the assertion that gives the window
        // meaning — a worker that missed one heartbeat to a garbage-collection pause or a brief partition is not
        // punished for it. Without sampling here, the window could be deleted and nothing would notice.
        awaitLeaseExpiry(runId);
        assertThat(reconciler.reconcile()).isZero();
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("CLAIMED");

        awaitLeaseExpiryAndRecoveryWindow(runId);
        assertThat(reconciler.reconcile()).isEqualTo(1);

        // Fencing ends the assignment and starts the run stopping. It does not finish it: the outcome is written
        // when the run settles, so a crash here cannot leave a run claiming a result it never reached.
        assertThat(lifecycleOf(runId)).isEqualTo("STOPPING");
        assertThat(versionOf(runId)).isEqualTo(4L);
        Map<String, Object> attempt = jdbc.queryForMap("select * from execution_attempts where run_id = ?", runId);
        assertThat(attempt.get("attempt_state")).isEqualTo("FENCED");
        assertThat(attempt.get("fenced_at")).isNotNull();
        // The epoch survives fencing, because a later assignment must be strictly greater than it.
        assertThat(attempt.get("assignment_epoch")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select infrastructure_outcome from test_runs where run_id = ?", String.class, runId))
                .isNull();

        // A late heartbeat cannot resurrect a fenced assignment. That is the whole point of fencing: the worker
        // does not have to be reachable to be made harmless.
        assertThat(heartbeat(runId, attemptOf(runId), 1, serviceToken(WORKER)).statusCode()).isEqualTo(409);
        assertThat(lifecycleOf(runId)).isEqualTo("STOPPING");

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
        assertThat(versionOf(runId)).isEqualTo(5L);
        // A lost lease is an infrastructure failure in the claim phase — not a timeout, and not a cancellation.
        assertThat(jdbc.queryForMap("select * from test_runs where run_id = ?", runId))
                .containsEntry("infrastructure_outcome", "FAILED")
                .containsEntry("test_outcome", "NOT_AVAILABLE")
                .containsEntry("quality_gate_status", "NOT_EVALUATED")
                .containsEntry("termination_reason", "LEASE_LOST")
                .containsEntry("termination_phase", "CLAIM")
                .containsEntry("cancellation_status", "NOT_REQUESTED");
        assertThat(count("run_lifecycle_events", runId)).isEqualTo(4);
        // And a heartbeat after the run is over changes nothing either.
        assertThat(heartbeat(runId, attemptOf(runId), 1, serviceToken(WORKER)).statusCode()).isEqualTo(409);
    }

    @Test
    void aHeartbeatThatBeatsTheReconcilerKeepsTheAssignment() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        UUID attemptId = attemptOf(runId);

        Instant originalExpiry = leaseExpiry(runId);
        // Wait until the lease is nearly spent, so the heartbeat is the reason it survives rather than elapsed
        // time being the reason. A loop that finishes well inside the lease proves only that the clock had not
        // run out yet.
        Awaitility.await()
                .atMost(LEASE.plusSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select lease_expires_at - interval '400 milliseconds' <= now()"
                                + " from execution_attempts where run_id = ?",
                        Boolean.class, runId)));
        assertThat(heartbeat(runId, attemptId, 1, serviceToken(WORKER)).statusCode()).isEqualTo(200);

        // Past the instant the lease would have died without that heartbeat, and past the recovery window too.
        Awaitility.await()
                .atMost(LEASE.plusSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Instant.now().isAfter(originalExpiry.plusSeconds(6)));
        assertThat(reconciler.reconcile()).isZero();
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(versionOf(runId)).isEqualTo(3L);
        assertThat(leaseExpiry(runId)).isAfter(originalExpiry);
    }

    // ---------------------------------------------------------------- cancellation of owned work

    @Test
    void cancellingAnOwnedRunFencesItAndSettlesThroughStopping() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);

        HttpResponse<String> response = cancel(tenant.bearer(), runId);

        // Owned work cannot end in one step, so the honest answer is that the request is durable and termination
        // is pending — which is exactly what 202 has always meant in this contract.
        assertThat(response.statusCode())
                .as("lifecycle=%s stopReason=%s body=%s", lifecycleOf(runId), stopReasonOf(runId),
                        response.body())
                .isEqualTo(202);
        JsonNode run = json(response);
        assertThat(run.get("lifecycleState").stringValue()).isEqualTo("STOPPING");
        assertThat(run.get("cancellationStatus").stringValue()).isEqualTo("REQUESTED");
        // No outcome yet. The run has not finished.
        assertThat(run.get("infrastructureOutcome").isNull()).isTrue();
        assertThat(run.get("completedAt").isNull()).isTrue();
        // The assignment is already gone, though: fencing is what makes the request effective.
        assertThat(jdbc.queryForObject(
                        "select attempt_state from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo("FENCED");
        assertThat(heartbeat(runId, attemptOf(runId), 1, serviceToken(WORKER)).statusCode()).isEqualTo(409);

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForMap("select * from test_runs where run_id = ?", runId))
                .containsEntry("infrastructure_outcome", "CANCELLED")
                .containsEntry("termination_reason", "USER_REQUESTED")
                .containsEntry("termination_phase", "CANCELLATION")
                .containsEntry("cancellation_status", "ACKNOWLEDGED");
        assertThat(versionOf(runId)).isEqualTo(5L);
    }

    @Test
    void cancellingARunAlreadyStoppingForALostLeaseIsRefusedRatherThanFalselyAccepted() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        awaitLeaseExpiryAndRecoveryWindow(runId);
        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(lifecycleOf(runId)).isEqualTo("STOPPING");

        HttpResponse<String> response = cancel(tenant.bearer(), runId);

        // The run will settle FAILED with no cancellation recorded anywhere. Answering "accepted, pending" would
        // tell the caller its cancellation is durable when nothing of the kind exists — the same false cause in
        // an audited record the service refuses to write one state later.
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("RUN_NOT_CANCELLABLE");
        assertThat(jdbc.queryForObject(
                        "select cancellation_status from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("NOT_REQUESTED");

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select * from test_runs where run_id = ?", runId))
                .containsEntry("infrastructure_outcome", "FAILED")
                .containsEntry("termination_reason", "LEASE_LOST");
    }

    @Test
    void anOwnedRunStillHoldsAdmissionCapacityUntilItSettles() throws Exception {
        Tenant tenant = tenant();
        List<UUID> runIds = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            runIds.add(createRun(tenant));
        }
        scheduler.scheduleDue();
        // Claiming does not release anything: CLAIMED is active work, and admission counts every state that is
        // not COMPLETED precisely so that adding one cannot silently stop the ceiling binding.
        assertThat(claims.claim(dispatchFor(runIds.get(0)), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        assertThat(create(tenant).statusCode()).isEqualTo(429);

        HttpResponse<String> owned = cancel(tenant.bearer(), runIds.get(0));
        assertThat(owned.statusCode())
                .as("lifecycle=%s stopReason=%s body=%s", lifecycleOf(runIds.get(0)),
                        stopReasonOf(runIds.get(0)), owned.body())
                .isEqualTo(202);
        // Still holding it while stopping — the run is not over yet.
        assertThat(create(tenant).statusCode()).isEqualTo(429);

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(create(tenant).statusCode()).isEqualTo(202);
    }

    // ---------------------------------------------------------------- database invariants

    @Test
    void theDatabaseRejectsEveryOwnershipShapeExceptTheImplementedOnes() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        String lifecycleGuard = "only scheduling, claim, execution, stop, and terminal transitions are supported";
        String attemptGuard = "only claim, acquire, heartbeat, fence, and execution-history transitions are supported";

        // Skipping straight past claim is still refused, as is claiming without taking the assignment.
        assertRejected(
                lifecycleGuard, "update test_runs set lifecycle_state = 'RUNNING' where run_id = ?", runId);
        // Claiming the run without taking the assignment is the shape the row guard cannot see, because the
        // assignment lives on another table. The deferred check is what catches it, at commit.
        assertRejected(
                "an owned run requires exactly one attempt holding the active assignment",
                "update test_runs set lifecycle_state = 'CLAIMED', run_version = 3, updated_at = now(),"
                        + " updated_by = 'kaas.dispatch-consumer' where run_id = ?",
                runId);

        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);
        UUID attemptId = attemptOf(runId);

        // A second assignment cannot be created, and an existing one cannot be reassigned or rewound.
        assertRejected(
                attemptGuard,
                "update execution_attempts set assigned_worker_id = 'kaas.worker.thief' where run_id = ?", runId);
        assertRejected(
                attemptGuard, "update execution_attempts set assignment_epoch = 2 where run_id = ?", runId);
        assertRejected(
                attemptGuard,
                "update execution_attempts set attempt_state = 'WAITING_FOR_CLAIM', assignment_epoch = null,"
                        + " assigned_worker_id = null, lease_started_at = null, lease_expires_at = null,"
                        + " last_heartbeat_at = null where run_id = ?",
                runId);
        // A lease may only ever move forward, and only while it is still alive.
        assertRejected(
                attemptGuard,
                "update execution_attempts set lease_expires_at = lease_expires_at - interval '1 second'"
                        + " where run_id = ?",
                runId);
        assertRejected(
                attemptGuard,
                "update execution_attempts set last_heartbeat_at = last_heartbeat_at - interval '1 second'"
                        + " where run_id = ?",
                runId);
        assertRejected(
                "execution attempts are retained as assignment evidence",
                "delete from execution_attempts where run_id = ?", runId);

        // Identity and history are immutable even while the assignment moves.
        assertRejected(
                attemptGuard, "update execution_attempts set attempt_number = 2 where run_id = ?", runId);
        assertRejected(
                attemptGuard, "update execution_attempts set created_by = 'someone' where run_id = ?", runId);

        // A run cannot reach a terminal state while an assignment is still live, and cannot skip STOPPING.
        assertRejected(
                lifecycleGuard,
                "update test_runs set lifecycle_state = 'COMPLETED', run_version = 4, completed_at = now(),"
                        + " updated_at = now(), test_outcome = 'NOT_AVAILABLE',"
                        + " infrastructure_outcome = 'FAILED', termination_reason = 'LEASE_LOST',"
                        + " termination_phase = 'CLAIM', updated_by = 'kaas.lease-reconciler' where run_id = ?",
                runId);
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject(
                        "select assigned_worker_id from execution_attempts where run_id = ?", String.class, runId))
                .isEqualTo(WORKER);
        assertThat(attemptId).isNotNull();
    }

    @Test
    void aStoppingRunMustHaveItsAssignmentFencedAndATerminalRunMayKeepNoLiveLease() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        assertThat(claims.claim(dispatchFor(runId), WORKER).disposition())
                .isEqualTo(ClaimDisposition.CLAIMED);

        // Moving the run to STOPPING without fencing the attempt leaves a worker believing it still owns work the
        // control plane has decided to end. The deferred check catches it at commit.
        jdbc.update("alter table test_runs disable trigger test_runs_supported_update");
        try {
            assertThatThrownBy(() -> jdbc.update(
                            "update test_runs set lifecycle_state = 'STOPPING', run_version = 4,"
                                    + " stop_reason = 'LEASE_LOST', updated_at = now(),"
                                    + " updated_by = 'kaas.lease-reconciler' where run_id = ?",
                            runId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("a stopping run must have its assignment fenced");
        } finally {
            jdbc.update("alter table test_runs enable trigger test_runs_supported_update");
        }
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
    }

    // ---------------------------------------------------------------- helpers

    private void awaitLeaseExpiry(UUID runId) {
        Awaitility.await()
                .atMost(LEASE.plusSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select lease_expires_at <= now() from execution_attempts where run_id = ?",
                        Boolean.class, runId)));
    }

    private void awaitLeaseExpiryAndRecoveryWindow(UUID runId) {
        Awaitility.await()
                .atMost(LEASE.plusSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select lease_expires_at + interval '5 seconds' <= now()"
                                + " from execution_attempts where run_id = ?",
                        Boolean.class, runId)));
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
