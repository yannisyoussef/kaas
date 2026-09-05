package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.application.WorkerLeaseService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.application.ExecutionAuthorizationService;
import com.kaas.api.execution.application.SourceCapabilityService;
import com.kaas.api.execution.domain.CapabilityToken;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Being allowed to execute, which is a different thing from owning the attempt.
 *
 * <p>Every refusal here is a refusal the platform depends on. A claimed run whose lease has lapsed, whose
 * cancellation arrived a millisecond ago, or whose epoch has been superseded is indistinguishable from a healthy
 * one if you only look at the token you were given — so these tests take authority that was genuinely valid,
 * move the world underneath it, and check that it stops working.
 *
 * <p>A valid sandbox attestation is configured for this class, because the point of these tests is everything
 * <em>other</em> than the gate bridge. The absent and failing gate cases have their own classes, since the
 * attestation is read once at startup and cannot vary per test without turning it into a runtime switch —
 * which is precisely what it must not be.
 */
@Testcontainers
@Import(ExecutionAuthorizationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.claim.lease-duration=PT4S",
            "kaas.claim.recovery-window=PT2S",
            "kaas.execution.authorization-ttl=PT5M",
            "kaas.execution.capability-ttl=PT5M",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.admission.max-active-runs-per-organization=8",
            "kaas.admission.max-queued-runs-per-organization=8",
            "spring.datasource.hikari.maximum-pool-size=24"
        })
class ExecutionAuthorizationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.local";
    private static final String OTHER_WORKER = "kaas.worker.other";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-exec");

    /**
     * A genuinely valid attestation, built the way a real one is.
     *
     * <p>Generated rather than pasted, so its freshness is relative to when the test runs. A fixture with a
     * hardcoded date would start failing the day it aged past the maximum, and the obvious fix — widening the
     * maximum — would quietly disable the freshness check these tests depend on.
     */
    @DynamicPropertySource
    static void attestation(DynamicPropertyRegistry registry) {
        registry.add("kaas.execution.sandbox-attestation", () -> validAttestation(Instant.now()));
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;
    @Autowired private WorkerLeaseService leases;
    @Autowired private ExecutionAuthorizationService authorizations;
    @Autowired private SourceCapabilityService sources;

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
            "execution_capability_secret_references", "execution_commands", "execution_capabilities",
            "execution_authorizations", "dispatch_inbox", "outbox_messages", "run_lifecycle_events",
            "execution_dispatches", "execution_attempts", "run_snapshot_tags", "run_snapshot_artifact_types",
            "run_snapshot_configuration_entries", "run_snapshot_features", "run_snapshots", "test_runs");

    // ------------------------------------------------------------------ the decision

    @Test
    @Timeout(120)
    void aClaimedAttemptUnderALiveLeaseIsAuthorizedAndGetsACommandThatNothingExecutes() throws Exception {
        UUID runId = claimedRun();

        var outcome = authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        assertThat(outcome.denial()).isEmpty();
        var delivery = outcome.delivery().orElseThrow();
        assertThat(delivery.authorization().workerId()).isEqualTo(WORKER);
        assertThat(delivery.authorization().assignmentEpoch()).isEqualTo(1);

        // The run has not moved. Authorization is permission, not a transition; PROVISIONING belongs to a slice
        // that can actually provision something.
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from run_lifecycle_events where run_id = ? and lifecycle_state = 'PROVISIONING'",
                        Integer.class, runId))
                .isZero();
        // Nothing was queued for delivery. The outbox already holds the queue-time dispatch intent from
        // scheduling; what matters is that authorizing added nothing to it, so no broker can carry this command
        // anywhere. Asserting an empty outbox would have been wrong and would have hidden the real property.
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_messages where message_type <> 'EXECUTION_DISPATCH'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Integer.class)).isOne();
    }

    @Test
    @Timeout(120)
    void theAuthorizationNeverOutlivesTheLeaseThatJustifiesIt() throws Exception {
        UUID runId = claimedRun();

        var delivery = authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow();

        Instant leaseExpiry = jdbc.queryForObject(
                        "select lease_expires_at from execution_attempts where run_id = ?",
                        java.sql.Timestamp.class, runId)
                .toInstant();
        // The configured TTL is five minutes and the lease is four seconds, so the lease is what bounds this.
        // Taking the earlier of the two is what makes the invariant true by construction rather than by a check.
        assertThat(delivery.authorization().expiresAt()).isBeforeOrEqualTo(leaseExpiry);
        assertThat(delivery.commandExpiresAt()).isBeforeOrEqualTo(delivery.authorization().expiresAt());
    }

    @Test
    @Timeout(120)
    void aQueuedRunIsNotAuthorized() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();

        assertThat(authorizations.authorize(runId, UUID.randomUUID(), 1, WORKER).denial())
                .contains(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
    }

    @Test
    @Timeout(120)
    void aCancelledRunIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();
        cancel(runId);

        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                .contains(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
    }

    @Test
    @Timeout(120)
    void aCompletedRunIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();
        cancel(runId);
        leases.settleStopping(runId);
        assertThat(lifecycleOf(runId)).isEqualTo("COMPLETED");

        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                .contains(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
    }

    @Test
    @Timeout(120)
    void aDifferentWorkerIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();

        // The first worker to authorize ACQUIRES the assignment. Until that happens the stored worker id is the
        // dispatch consumer's own configured constant, which names no particular process — so "a different
        // worker" is not yet a meaningful idea, and any worker may take an unheld assignment. This test has to
        // establish a holder before it can assert somebody else is refused.
        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial()).isEmpty();

        assertThat(authorizations.authorize(runId, attemptId(runId), 1, OTHER_WORKER).denial())
                .contains(ExecutionDenial.ASSIGNMENT_STALE);
    }

    @Test
    @Timeout(120)
    void anUnacquiredAssignmentIsHeldByNobodyAndTheFirstWorkerTakesIt() throws Exception {
        // The anti-vacuity twin of the test above, and the property acquisition exists for. Before this slice
        // the stored worker id was one constant for the entire deployment, so every worker in the fleet
        // satisfied every ownership check on every run and the assignment epoch fenced nothing.
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);

        assertThat(jdbc.queryForObject(
                        "select acquired_at from execution_attempts where attempt_id = ?",
                        java.sql.Timestamp.class, attemptId))
                .as("a freshly claimed attempt is held by nobody")
                .isNull();

        assertThat(authorizations.authorize(runId, attemptId, 1, OTHER_WORKER).denial()).isEmpty();

        assertThat(jdbc.queryForObject(
                        "select assigned_worker_id from execution_attempts where attempt_id = ?",
                        String.class, attemptId))
                .as("the acquiring worker is recorded, not the consumer's configured constant")
                .isEqualTo(OTHER_WORKER);
        // And the worker the consumer nominally claimed for is now the one refused.
        assertThat(authorizations.authorize(runId, attemptId, 1, WORKER).denial())
                .contains(ExecutionDenial.ASSIGNMENT_STALE);
    }

    @Test
    @Timeout(120)
    void aDifferentEpochIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();

        assertThat(authorizations.authorize(runId, attemptId(runId), 2, WORKER).denial())
                .contains(ExecutionDenial.ASSIGNMENT_STALE);
    }

    @Test
    @Timeout(120)
    void aDifferentAttemptIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();

        assertThat(authorizations.authorize(runId, UUID.randomUUID(), 1, WORKER).denial())
                .contains(ExecutionDenial.ASSIGNMENT_STALE);
    }

    @Test
    @Timeout(120)
    void anExpiredLeaseIsNotAuthorizedEvenBeforeTheReconcilerFencesIt() throws Exception {
        UUID runId = claimedRun();
        awaitLeaseExpiry(runId);

        // The window between expiry and fencing is exactly where a naive check would still say yes.
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                .contains(ExecutionDenial.LEASE_EXPIRED);
    }

    @Test
    @Timeout(120)
    void aFencedAssignmentIsNotAuthorized() throws Exception {
        UUID runId = claimedRun();
        awaitLeaseExpiry(runId);
        assertThat(leases.fenceExpired(runId)).isTrue();

        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                .contains(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
    }

    @Test
    @Timeout(120)
    void anAllowlistIsRefusedWhenTheDeploymentCannotEnforceIt() throws Exception {
        UUID runId = claimedRun();
        // A REALISTIC allowlist: tenant-owned, carrying a destination, with a digest that matches its own
        // content, and pinned by the run's snapshot. Every one of those matters. An allowlist with no
        // destination permits nothing and the domain model refuses to construct one; an unowned one violates
        // the schema; a mismatched digest would be refused by the tamper check instead, and the test would
        // pass for a reason that has nothing to do with enforceability.
        UUID policyId = UUID.randomUUID();
        jdbc.update("alter table network_policy_revisions disable trigger all");
        jdbc.update("alter table network_policy_destinations disable trigger all");
        jdbc.update("alter table run_snapshots disable trigger all");
        try {
            Map<String, Object> scope = jdbc.queryForMap(
                    "select organization_id, project_id from test_runs where run_id = ?", runId);
            jdbc.update(
                    "insert into network_policy_revisions (policy_revision_id, policy_type, policy_version,"
                            + " canonical_digest, created_by, created_at, organization_id, project_id)"
                            + " values (?, 'ALLOWLIST', 1, ?, 'kaas.platform', now(), ?, ?)",
                    policyId,
                    "sha256:27f6c7bbe9d9e3b45c6b46d7f68f44322699effb9c6d1c5a21ca4132e5ab8472",
                    scope.get("organization_id"),
                    scope.get("project_id"));
            jdbc.update(
                    "insert into network_policy_destinations (policy_revision_id, host, port, scheme)"
                            + " values (?, 'api.example.com', 443, 'HTTPS')",
                    policyId);
            jdbc.update(
                    "update run_snapshots set network_policy_revision_id = ? where run_id = ?", policyId, runId);

            // The mechanism exists in this build — ALLOWLIST is an enforceable type now — and this deployment
            // still cannot run it, because its assessment carries no egress controls. That is the fail-closed
            // reading of absent evidence, and it must be a REFUSAL rather than a downgrade: a run that
            // appeared to have egress control nothing was applying would be worse than one that has none.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.NETWORK_POLICY_NOT_ENFORCEABLE);
        } finally {
            jdbc.update("update run_snapshots set network_policy_revision_id = ? where run_id = ?",
                    UUID.fromString("00000000-0000-4000-8000-00000000d001"), runId);
            jdbc.update("delete from network_policy_destinations where policy_revision_id = ?", policyId);
            jdbc.update("delete from network_policy_revisions where policy_revision_id = ?", policyId);
            jdbc.update("alter table run_snapshots enable trigger all");
            jdbc.update("alter table network_policy_destinations enable trigger all");
            jdbc.update("alter table network_policy_revisions enable trigger all");
        }
    }

    @Test
    void aPolicyWhoseDigestDoesNotMatchItsContentIsRefused() throws Exception {
        UUID runId = claimedRun();
        // The other half of the policy check, isolated. Retyping the seeded row alone also breaks its digest,
        // so a single test covering both would pass on whichever check ran first — mutation testing caught
        // exactly that in the previous slice. Here the type is untouched and only the digest is wrong.
        jdbc.update("alter table network_policy_revisions disable trigger all");
        try {
            jdbc.update("update network_policy_revisions set canonical_digest = ? where policy_revision_id = ?",
                    "sha256:" + "e".repeat(64),
                    UUID.fromString("00000000-0000-4000-8000-00000000d001"));

            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.NETWORK_POLICY_NOT_ENFORCEABLE);
        } finally {
            jdbc.update("update network_policy_revisions set canonical_digest = ? where policy_revision_id = ?",
                    "sha256:3944c369d57700eb13ce96b492fbac7ea9443a61faa8985a01e2394ab40e0de6",
                    UUID.fromString("00000000-0000-4000-8000-00000000d001"));
            jdbc.update("alter table network_policy_revisions enable trigger all");
        }
    }

    @Test
    @Timeout(120)
    void aTamperedNetworkPolicyDigestIsRefused() throws Exception {
        UUID runId = claimedRun();
        jdbc.update("alter table network_policy_revisions disable trigger all");
        try {
            jdbc.update("update network_policy_revisions set canonical_digest = ?", "sha256:" + "0".repeat(64));
            // The digest is recomputed from the policy's content, so a row edited underneath the application
            // stops matching what it claims to be.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.NETWORK_POLICY_NOT_ENFORCEABLE);
        } finally {
            jdbc.update(
                    "update network_policy_revisions set canonical_digest = ?",
                    "sha256:3944c369d57700eb13ce96b492fbac7ea9443a61faa8985a01e2394ab40e0de6");
            jdbc.update("alter table network_policy_revisions enable trigger all");
        }
    }

    @Test
    @Timeout(120)
    void aSecretBearingRunIsRefusedBecauseNoProviderExists() throws Exception {
        Tenant tenant = tenant(true);
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);

        // Honest failure at authorization rather than a command promising secrets nothing can deliver. The
        // failure happens before any sandbox exists, with a reason that names the actual problem.
        assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                .contains(ExecutionDenial.SECRET_PROVIDER_UNAVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from execution_commands", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ idempotency and rotation

    @Test
    @Timeout(120)
    void repeatingTheRequestReturnsOneAuthorizationAndOneCommand() throws Exception {
        UUID runId = claimedRun();

        var first = authorizations.authorize(runId, attemptId(runId), 1, WORKER).delivery().orElseThrow();
        var second = authorizations.authorize(runId, attemptId(runId), 1, WORKER).delivery().orElseThrow();

        assertThat(second.authorization().authorizationId())
                .isEqualTo(first.authorization().authorizationId());
        assertThat(second.commandId()).isEqualTo(first.commandId());
        assertThat(second.commandDigest()).isEqualTo(first.commandDigest());
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from execution_commands", Integer.class)).isOne();
    }

    @Test
    @Timeout(120)
    void aRetryRotatesTheCapabilitySoOnlyTheNewestTokenWorks() throws Exception {
        UUID runId = claimedRun();

        String firstToken = authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow()
                .sourceCapabilityToken();
        String secondToken = authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow()
                .sourceCapabilityToken();

        assertThat(secondToken).isNotEqualTo(firstToken);
        // Ten retries must not leave ten working tokens. Exactly one live capability per type per authorization.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capabilities where revoked_at is null", Integer.class))
                .isOne();
        // Asserted directly rather than inferred from the refusal. The refusal alone was satisfied by the
        // `revoked_at is null` predicate in the redemption update, so the revocation itself — the thing this
        // test is named for — was jointly covered rather than proven.
        assertThat(jdbc.queryForObject(
                        "select revoked_at is not null from execution_capabilities where token_sha256 = ?",
                        Boolean.class, CapabilityToken.hash(firstToken)))
                .isTrue();
        assertThat(sources.redeem(firstToken, WORKER).denial()).contains(ExecutionDenial.CAPABILITY_EXPIRED);
        assertThat(sources.redeem(secondToken, WORKER).bundle()).isPresent();
    }

    @Test
    @Timeout(180)
    void concurrentRequestsProduceExactlyOneAuthorization() throws Exception {
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);
        int racers = 6;
        CyclicBarrier start = new CyclicBarrier(racers);
        List<UUID> issued = java.util.Collections.synchronizedList(new ArrayList<>());

        try (var pool = Executors.newFixedThreadPool(racers)) {
            List<? extends java.util.concurrent.Future<?>> futures = IntStream.range(0, racers)
                    .mapToObj(index -> pool.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        var outcome = authorizations.authorize(runId, attemptId, 1, WORKER);
                        outcome.delivery()
                                .ifPresent(delivery -> issued.add(delivery.authorization().authorizationId()));
                        return null;
                    }))
                    .toList();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        // Every racer that succeeded must have been describing the same authorization, and the table must hold
        // exactly one. Asserting only "no exception" would pass with six competing authorizations.
        //
        // What this proves is the ROW LOCK, not the unique constraint. Every racer takes `for update` on the run
        // before it looks for an existing authorization, so the six serialize: one issues and five reissue, and
        // the constraint is never reached. The test below reaches that branch directly, because a branch nothing
        // enters is a branch that can return anything.
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from execution_commands", Integer.class)).isOne();
        assertThat(issued).isNotEmpty();
        assertThat(java.util.Set.copyOf(issued)).hasSize(1);
    }

    @Test
    @Timeout(120)
    void losingTheUniqueConstraintRefusesRatherThanDeliveringAnUnwrittenAuthorization() throws Exception {
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);
        // A conflicting authorization for this exact assignment, written outside the service so the insert
        // genuinely collides. Under the run lock this cannot happen through the service itself, which is
        // precisely why the branch needs reaching another way.
        jdbc.update(
                """
                insert into execution_authorizations
                    (authorization_id, organization_id, project_id, run_id, run_version, attempt_id,
                     attempt_number, assignment_epoch, worker_id, run_snapshot_sha256,
                     security_profile_version, security_assessment_digest, probe_image_digest,
                     network_policy_revision_id, issued_at, expires_at)
                select gen_random_uuid(), t.organization_id, t.project_id, t.run_id, t.run_version, ?, 1, 1, ?,
                       t.snapshot_sha256, 'kaas.sandbox.v1', ?, ?,
                       '00000000-0000-4000-8000-00000000d001', clock_timestamp(),
                       clock_timestamp() + interval '5 minutes'
                  from test_runs t where t.run_id = ?
                """,
                attemptId, WORKER, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), runId);

        // The loser must refuse. Returning success would hand a worker an authorization id, a command, and a
        // live capability token for a row that was never written.
        var outcome = authorizations.authorize(runId, attemptId, 1, WORKER);

        assertThat(outcome.delivery()).isEmpty();
        assertThat(outcome.denial()).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from execution_commands", Integer.class)).isZero();
    }

    @Test
    @Timeout(120)
    void aRevokedAuthorizationCannotBeBroughtBackToLife() throws Exception {
        UUID runId = claimedRun();
        var first = authorizations.authorize(runId, attemptId(runId), 1, WORKER).delivery().orElseThrow();
        String token = first.sourceCapabilityToken();
        int liveBefore = jdbc.queryForObject(
                "select count(*) from execution_capabilities where revoked_at is null", Integer.class);
        jdbc.update(
                "update execution_authorizations set revoked_at = clock_timestamp(), revoked_reason = 'OPERATOR'"
                        + " where authorization_id = ?",
                first.authorization().authorizationId());

        // Revocation is terminal, and the re-anchoring path actively moves expires_at forward — so a missing
        // check here would not merely return a stale row, it would grant fresh authority and mint a live token
        // against an authorization somebody deliberately withdrew.
        var outcome = authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        assertThat(outcome.denial()).contains(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        // No NEW capability was minted. The one issued before the revocation is still on the row — revoking an
        // authorization does not rewrite its children — and that is fine precisely because possession is not
        // authority: the token below is refused on the authorization's own window.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capabilities where revoked_at is null", Integer.class))
                .isEqualTo(liveBefore);
        assertThat(sources.redeem(token, WORKER).denial()).contains(ExecutionDenial.CAPABILITY_EXPIRED);
    }

    @Test
    @Timeout(180)
    void aSnapshotLargerThanABundleMayCarryIsRefusedBeforeAnyTokenExists() throws Exception {
        UUID runId = claimedRun();
        jdbc.update("alter table feature_revisions disable trigger all");
        // The per-revision size CHECK has to come off as well as the triggers: a single revision cannot legally
        // hold enough source to exceed the bundle ceiling, which is why the two limits do not compose in the
        // first place. A real oversized snapshot is many legal revisions; this reaches the same total with one.
        jdbc.update("alter table feature_revisions drop constraint ck_feature_revisions_source_size");
        try {
            // Grown server-side with repeat(), so sixty-four megabytes never crosses into the JVM. The point is
            // the refusal, not the transfer.
            jdbc.update(
                    "update feature_revisions set source = repeat('x', ?) where revision_id in"
                            + " (select feature_revision_id from run_snapshot_features where run_id = ?)",
                    64 * 1024 * 1024 + 1, runId);

            // The ceilings did not compose: a run may pin far more source than a bundle may carry, and issuance
            // could not see the size because it digests paths rather than content. So authorization always
            // succeeded and redemption always threw — rolling the redemption counter back, which made the
            // amplification ceiling unreachable on exactly the input that cost the most.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.RUN_SNAPSHOT_INVALID);
            assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from execution_capabilities", Integer.class))
                    .isZero();
        } finally {
            // Shrunk again before the trigger goes back on. Leaving sixty-four megabytes behind would bloat
            // every later test in the class and make the next one's timing depend on this one having run.
            jdbc.update(
                    "update feature_revisions set source = 'Feature: a\n' where revision_id in"
                            + " (select feature_revision_id from run_snapshot_features where run_id = ?)",
                    runId);
            jdbc.update(
                    "alter table feature_revisions add constraint ck_feature_revisions_source_size"
                            + " CHECK (octet_length(source) BETWEEN 1 AND 524288)");
            jdbc.update("alter table feature_revisions enable trigger all");
        }
    }

    @Test
    @Timeout(120)
    void anUnsealedOrEmptySnapshotIsRefused() throws Exception {
        UUID runId = claimedRun();
        jdbc.update("alter table run_snapshots disable trigger all");
        try {
            jdbc.update("update run_snapshots set sealed = false where run_id = ?", runId);

            // RUN_SNAPSHOT_INVALID had no test at all: both halves of the check could be deleted with the whole
            // suite still green, which is the same shape as a control only ever exercised where it does nothing.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.RUN_SNAPSHOT_INVALID);
            assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class))
                    .isZero();
        } finally {
            jdbc.update("update run_snapshots set sealed = true where run_id = ?", runId);
            jdbc.update("alter table run_snapshots enable trigger all");
        }
    }

    // ------------------------------------------------------------------ capability security

    @Test
    @Timeout(120)
    void theTokenIsHighEntropyAndItsPlaintextIsNeverStored() throws Exception {
        UUID runId = claimedRun();

        String token = authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow()
                .sourceCapabilityToken();

        assertThat(token).startsWith("kaas_src_");
        assertThat(CapabilityToken.hasShapeOf(token, CapabilityType.SOURCE)).isTrue();
        assertThat(CapabilityToken.hasShapeOf(token, CapabilityType.SECRET)).isFalse();

        // Not in the capability row, not in the command document, not anywhere in the database. The stored hash
        // is what the token becomes; the token itself the server cannot produce again.
        assertThat(jdbc.queryForObject("select token_sha256 from execution_capabilities", String.class))
                .isEqualTo(CapabilityToken.hash(token))
                .isNotEqualTo(token);
        String document = jdbc.queryForObject("select document::text from execution_commands", String.class);
        assertThat(document).doesNotContain(token).doesNotContain("kaas_src_");
    }

    @Test
    @Timeout(120)
    void aSourceCapabilityReturnsExactlyTheSnapshotPinnedSources() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);
        String token = authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow()
                .sourceCapabilityToken();

        var bundle = sources.redeem(token, WORKER).bundle().orElseThrow();

        Map<String, String> extracted = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle.archive()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                extracted.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        String expectedPath = jdbc.queryForObject(
                "select logical_path from run_snapshot_features where run_id = ?", String.class, runId);
        assertThat(extracted).containsOnlyKeys(expectedPath);
        assertThat(extracted.get(expectedPath)).isEqualTo("Feature: a\nScenario: one\n* match 1 == 1\n");
        assertThat(bundle.contentDigest()).startsWith("sha256:");
    }

    @Test
    @Timeout(120)
    void aCapabilityIssuedToOneWorkerCannotBeRedeemedByAnother() throws Exception {
        UUID runId = claimedRun();
        String token = tokenFor(runId);

        // A stolen token is not enough: redemption revalidates who currently holds the assignment.
        assertThat(sources.redeem(token, OTHER_WORKER).denial()).contains(ExecutionDenial.CAPABILITY_FENCED);
    }

    @Test
    @Timeout(120)
    void aCapabilityStopsWorkingTheMomentTheRunIsCancelled() throws Exception {
        UUID runId = claimedRun();
        String token = tokenFor(runId);
        assertThat(sources.redeem(token, WORKER).bundle()).isPresent();

        cancel(runId);

        // The TTL has not moved. What changed is the world, which is the whole point of revalidating.
        assertThat(sources.redeem(token, WORKER).denial()).contains(ExecutionDenial.CAPABILITY_FENCED);
    }

    @Test
    @Timeout(120)
    void aCapabilityStopsWorkingWhenTheLeaseIsLost() throws Exception {
        UUID runId = claimedRun();
        String token = tokenFor(runId);
        assertThat(sources.redeem(token, WORKER).bundle()).isPresent();

        awaitLeaseExpiry(runId);

        // Expired but not yet fenced: the reconciler has not run, and the run still reads CLAIMED. The
        // capability is refused anyway.
        //
        // The reason is CAPABILITY_EXPIRED rather than CAPABILITY_FENCED, and that is the invariant working
        // rather than a weaker check: a capability's window is bounded by the authorization's, which is bounded
        // by the lease's, so a lapsed lease necessarily means a lapsed capability. There is no state in which a
        // capability outlives the lease and has to be caught by the fencing check instead — which is exactly
        // what "authorization.expiresAt <= lease.expiresAt" is for.
        assertThat(lifecycleOf(runId)).isEqualTo("CLAIMED");
        assertThat(sources.redeem(token, WORKER).denial()).contains(ExecutionDenial.CAPABILITY_EXPIRED);
    }

    @Test
    @Timeout(120)
    void aSecretTokenIsNotAcceptedAsASourceToken() throws Exception {
        UUID runId = claimedRun();
        tokenFor(runId);

        // Type confusion is refused on the token's own shape, before any lookup happens.
        assertThat(sources.redeem(CapabilityToken.issue(CapabilityType.SECRET), WORKER).denial())
                .contains(ExecutionDenial.CAPABILITY_INVALID);
        assertThat(sources.redeem("kaas_src_not-a-real-token", WORKER).denial())
                .contains(ExecutionDenial.CAPABILITY_INVALID);
        // Right length, wrong alphabet. The previous case failed on length alone, so the charset half of the
        // shape check was never the reason anything was refused.
        assertThat(sources.redeem("kaas_src_" + "!".repeat(43), WORKER).denial())
                .contains(ExecutionDenial.CAPABILITY_INVALID);
        assertThat(sources.redeem(null, WORKER).denial()).contains(ExecutionDenial.CAPABILITY_INVALID);
    }

    @Test
    @Timeout(120)
    void aCapabilityForOneRunCannotFetchAnother() throws Exception {
        UUID first = claimedRun();
        UUID second = claimedRun();
        String firstToken = tokenFor(first);
        tokenFor(second);

        var bundle = sources.redeem(firstToken, WORKER).bundle().orElseThrow();

        // The capability resolves its own authorization's run. There is no request parameter naming a run at
        // all, which is what makes cross-run access structurally impossible rather than merely checked.
        String firstPath = jdbc.queryForObject(
                "select logical_path from run_snapshot_features where run_id = ?", String.class, first);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle.archive()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo(firstPath);
        }
    }

    // ------------------------------------------------------------------ the command

    @Test
    @Timeout(120)
    void theCommandBindsTheSnapshotPolicyAndSandboxProfileItWasAuthorizedUnder() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        JsonNode document = objectMapper.readTree(
                jdbc.queryForObject("select document::text from execution_commands", String.class));

        assertThat(document.get("schemaVersion").stringValue()).isEqualTo("1.0");
        assertThat(document.at("/networkPolicy/type").stringValue()).isEqualTo("DENY_ALL");
        assertThat(document.at("/sandboxSecurityProfile/profileVersion").stringValue())
                .isEqualTo("kaas.sandbox.v1");
        assertThat(document.at("/sandboxSecurityProfile/assessmentDigest").stringValue()).startsWith("sha256:");
        // The command names the engine that will run it. Reporting KARATE while the platform executes its own
        // synthetic workload is the single most misleading thing this slice could do, so it is asserted here
        // rather than left to the runner to refuse.
        assertThat(document.at("/engine/type").stringValue()).isEqualTo("SYNTHETIC");
        assertThat(document.toString()).doesNotContain("KARATE");
        assertThat(document.get("secretCapabilities")).isEmpty();
        assertThat(document.get("assignmentEpoch").intValue()).isEqualTo(1);
        String snapshot = jdbc.queryForObject(
                "select snapshot_sha256 from test_runs where run_id = ?", String.class, runId);
        assertThat(document.get("runSnapshotDigest").stringValue()).isEqualTo("sha256:" + snapshot);
    }

    @Test
    @Timeout(120)
    void aCommandCannotBeUpdatedOrDeleted() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        assertThatThrownBy(() -> jdbc.update("update execution_commands set command_digest = ?", "sha256:" + "0".repeat(64)))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from execution_commands")).hasMessageContaining("immutable");
    }

    @Test
    @Timeout(120)
    void anAuthorizationCannotBeEditedAndCannotBeDeleted() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        // Only revocation may be written. Widening the window after issuance would make "short-lived" a claim
        // rather than a property.
        assertThatThrownBy(() -> jdbc.update(
                        "update execution_authorizations set expires_at = expires_at + interval '1 hour'"))
                .hasMessageContaining("only be revoked");
        assertThatThrownBy(() -> jdbc.update("update execution_authorizations set worker_id = 'kaas.someone-else'"))
                .hasMessageContaining("only be revoked");
        assertThatThrownBy(() -> jdbc.update("delete from execution_authorizations"))
                .hasMessageContaining("never deleted");
    }

    @Test
    @Timeout(120)
    void aCapabilityWindowCannotBeExtendedAfterIssuance() throws Exception {
        UUID runId = claimedRun();
        tokenFor(runId);

        assertThatThrownBy(() -> jdbc.update(
                        "update execution_capabilities set expires_at = expires_at + interval '1 hour'"))
                .hasMessageContaining("redemption or revocation");
    }

    // ------------------------------------------------------------------ what the schema makes impossible

    @Test
    @Timeout(120)
    void aCapabilityCannotBeScopedToAnotherTenantsSecretReference() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);
        UUID capabilityId = jdbc.queryForObject(
                "select capability_id from execution_capabilities", UUID.class);
        // A secret reference belonging to a different organization and project entirely.
        Tenant stranger = tenant(true);
        UUID foreignSecret = jdbc.queryForObject(
                "select secret_reference_id from secret_references where project_id = ?",
                UUID.class, stranger.projectId());

        // The foreign keys are composite on (organization_id, project_id, secret_reference_id), so a row that
        // names another tenant's secret is not merely rejected by application code — it cannot be written. A
        // single-column key constrains existence and never ownership, and the previous version of this schema
        // accepted exactly this row while its own comment claimed the key prevented it.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into execution_capability_secret_references
                            (capability_id, organization_id, project_id, secret_reference_id, binding_key)
                        values (?, (select organization_id from execution_capabilities where capability_id = ?),
                                (select project_id from execution_capabilities where capability_id = ?), ?, 'STOLEN')
                        """,
                        capabilityId, capabilityId, capabilityId, foreignSecret))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Timeout(120)
    void aCommandCannotNameAnAssignmentItsAuthorizationDoesNot() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);
        UUID authorizationId = jdbc.queryForObject(
                "select authorization_id from execution_authorizations", UUID.class);

        // A command's attempt, epoch, tenant, and run are exactly the fields a consumer would fence on, and they
        // were previously free: a command carrying a valid authorization id was accepted naming a different
        // attempt, a different epoch, a different tenant, and a run that did not exist.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into execution_commands
                            (command_id, authorization_id, organization_id, run_id, attempt_id, assignment_epoch,
                             command_digest, document, issued_at, expires_at)
                        select gen_random_uuid(), ?, organization_id, run_id, gen_random_uuid(), 7,
                               ?, '{}'::jsonb, clock_timestamp(), clock_timestamp() + interval '5 minutes'
                          from execution_authorizations where authorization_id = ?
                        """,
                        authorizationId, "sha256:" + "0".repeat(64), authorizationId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Timeout(120)
    void aCommandCannotCarryAWindowLongerThanTheCeiling() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        // Expiry is the field a consumer fences on, and it previously had no ceiling at all — a hundred-year
        // window was accepted.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into execution_commands
                            (command_id, authorization_id, organization_id, run_id, attempt_id, assignment_epoch,
                             command_digest, document, issued_at, expires_at)
                        select gen_random_uuid(), authorization_id, organization_id, run_id, attempt_id,
                               assignment_epoch, ?, '{}'::jsonb, clock_timestamp(),
                               clock_timestamp() + interval '100 years'
                          from execution_authorizations
                        """,
                        "sha256:" + "0".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Timeout(120)
    void onlyOneAuthorizationMayBeLiveForOneAttemptWhateverTheEpoch() throws Exception {
        UUID runId = claimedRun();
        authorizations.authorize(runId, attemptId(runId), 1, WORKER);

        // The uniqueness on (attempt_id, assignment_epoch) is an idempotency key, not a fencing constraint: it
        // makes epoch 1 and epoch 2 two distinct rows, both of which could be unrevoked at once. The partial
        // unique index is what actually forbids authority alongside the current holder.
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into execution_authorizations
                            (authorization_id, organization_id, project_id, run_id, run_version, attempt_id,
                             attempt_number, assignment_epoch, worker_id, run_snapshot_sha256,
                             security_profile_version, security_assessment_digest, probe_image_digest,
                             network_policy_revision_id, issued_at, expires_at)
                        select gen_random_uuid(), organization_id, project_id, run_id, run_version, attempt_id,
                               attempt_number, 2, worker_id, run_snapshot_sha256, security_profile_version,
                               security_assessment_digest, probe_image_digest, network_policy_revision_id,
                               clock_timestamp(), clock_timestamp() + interval '5 minutes'
                          from execution_authorizations
                        """))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Timeout(120)
    void aCapabilityRowIsAuditEvidenceAndCannotBeDeletedOrTruncated() throws Exception {
        UUID runId = claimedRun();
        tokenFor(runId);

        // A capability row is the only record that a token was ever issued and how often it was redeemed.
        assertThatThrownBy(() -> jdbc.update("delete from execution_capabilities"))
                .hasMessageContaining("never deleted");
        // TRUNCATE fires no row-level trigger, so every guard above is silent about it unless a statement-level
        // trigger exists. Immutability that one statement bypasses is not immutability.
        // CASCADE, so the statement reaches the immutability trigger rather than stopping at the foreign key.
        //
        // execution_results now references execution_commands, and PostgreSQL refuses a bare TRUNCATE on a
        // referenced table before any trigger runs. That is a second, independent protection — but asserting on
        // it would leave the trigger itself covered by nothing, which is precisely the "passes for a different
        // reason" trap. CASCADE gets past the foreign key and the trigger is then what refuses.
        assertThatThrownBy(() -> jdbc.update("truncate execution_commands cascade"))
                .hasMessageContaining("immutable");
        // And the foreign key refuses the bare form, so both protections are asserted rather than one hiding
        // the other.
        assertThatThrownBy(() -> jdbc.update("truncate execution_commands"))
                .hasMessageContaining("referenced in a foreign key constraint");
        assertThatThrownBy(() -> jdbc.update("truncate execution_capabilities cascade"))
                .hasMessageContaining("never deleted");
    }

    @Test
    @Timeout(180)
    void anAuthorizationFollowsALeaseThatIsRenewedPastItsOriginalWindow() throws Exception {
        UUID runId = claimedRun();
        var first = authorizations.authorize(runId, attemptId(runId), 1, WORKER).delivery().orElseThrow();
        Instant originalExpiry = first.authorization().expiresAt();

        // Heartbeat past the original window, exactly as a healthy worker does every few seconds.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> {
            leases.heartbeat(runId, attemptId(runId), 1, WORKER);
            return repositoryTime().isAfter(originalExpiry);
        });

        // Freezing the window at issuance was a liveness dead end: the lease always wins the min(), a lease is
        // renewed indefinitely, and the unique constraint makes a replacement impossible — so a healthy,
        // heartbeating worker became permanently unauthorizable one lease-period after its first request, and
        // the run's only exit was FAILED. Re-anchoring is what lets the authorization follow its lease.
        var again = authorizations.authorize(runId, attemptId(runId), 1, WORKER);
        assertThat(again.denial()).isEmpty();
        assertThat(again.delivery().orElseThrow().authorization().authorizationId())
                .isEqualTo(first.authorization().authorizationId());
        assertThat(again.delivery().orElseThrow().authorization().expiresAt()).isAfter(originalExpiry);
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isOne();
    }

    private Instant repositoryTime() {
        return jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    // ------------------------------------------------------------------ the internal surface

    @Test
    @Timeout(120)
    void aTenantTokenCannotReachTheInternalAuthorizationSurface() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);

        HttpResponse<String> response = postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId(runId) + "/execution-authorizations",
                tenant.bearer(),
                "{\"assignmentEpoch\":1}");

        // A tenant credential carries an organization and a non-reserved subject, so it fails the internal
        // chain's converter outright rather than being resolved in the caller's favour.
        assertThat(response.statusCode()).isIn(401, 403);
    }

    @Test
    @Timeout(120)
    void theWorkerCannotWidenItsOwnAuthorityThroughTheRequestBody() throws Exception {
        UUID runId = claimedRun();

        HttpResponse<String> response = postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId(runId) + "/execution-authorizations",
                serviceToken(WORKER),
                // Everything a caller might hope to dictate. Strict parsing refuses the request outright rather
                // than accepting it and ignoring the extra fields, which would look identical from outside.
                "{\"assignmentEpoch\":1,\"workerId\":\"kaas.someone-else\",\"securityGatePassed\":true,"
                        + "\"networkPolicy\":\"ALLOWLIST\",\"image\":\"evil:latest\"}");

        assertThat(response.statusCode()).isIn(400, 422);
        assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class)).isZero();
    }

    @Test
    @Timeout(120)
    void theInternalSurfaceIssuesTheTokenOnceAndForbidsCaching() throws Exception {
        UUID runId = claimedRun();

        HttpResponse<String> response = postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId(runId) + "/execution-authorizations",
                serviceToken(WORKER),
                "{\"assignmentEpoch\":1}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("sourceCapabilityToken").stringValue()).startsWith("kaas_src_");
        assertThat(body.get("secretCapabilityTokens")).isEmpty();
    }

    @Test
    @Timeout(120)
    void aRefusalIsNotAnOracleForWhichRunsAreCurrentlyClaimed() throws Exception {
        UUID claimed = claimedRun();
        Tenant other = tenant();
        UUID queued = createRun(other);
        scheduler.scheduleDue();

        // A run that IS claimed, addressed with the wrong attempt, and a run that is merely queued. The service
        // distinguishes these — ASSIGNMENT_STALE is reachable only when a run is currently claimed — so a body
        // that reported the distinction would confirm which run and attempt identifiers are live. The internal
        // chain grants one authority to every platform service and carries no tenancy, so any service credential
        // could walk identifiers and learn other tenants' run states from the difference.
        HttpResponse<String> wrongAttemptOnClaimedRun = postInternal(
                "/internal/v1/runs/" + claimed + "/attempts/" + UUID.randomUUID() + "/execution-authorizations",
                serviceToken(WORKER),
                "{\"assignmentEpoch\":1}");
        HttpResponse<String> queuedRun = postInternal(
                "/internal/v1/runs/" + queued + "/attempts/" + UUID.randomUUID() + "/execution-authorizations",
                serviceToken(WORKER),
                "{\"assignmentEpoch\":1}");

        assertThat(wrongAttemptOnClaimedRun.statusCode()).isEqualTo(409);
        assertThat(queuedRun.statusCode()).isEqualTo(409);
        // Byte-identical. The distinction is kept in the log and the metric, where an operator can see it and a
        // caller cannot.
        assertThat(objectMapper.readTree(wrongAttemptOnClaimedRun.body()).get("code").stringValue())
                .isEqualTo("EXECUTION_NOT_AUTHORIZED")
                .isEqualTo(objectMapper.readTree(queuedRun.body()).get("code").stringValue());
    }

    // ------------------------------------------------------------------ helpers

    private UUID claimedRun() throws Exception {
        Tenant tenant = tenant();
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);
        return runId;
    }

    private String tokenFor(UUID runId) {
        return authorizations
                .authorize(runId, attemptId(runId), 1, WORKER)
                .delivery()
                .orElseThrow()
                .sourceCapabilityToken();
    }

    private UUID attemptId(UUID runId) {
        return jdbc.queryForObject(
                "select attempt_id from execution_attempts where run_id = ?", UUID.class, runId);
    }

    private String lifecycleOf(UUID runId) {
        return jdbc.queryForObject("select lifecycle_state from test_runs where run_id = ?", String.class, runId);
    }

    /**
     * Cancels the way the real path does: the run stops and the assignment is fenced in one step.
     *
     * <p>Both halves, because doing only the first leaves a terminal run still holding a live assignment, which
     * the schema refuses outright — and because a test that fenced nothing would be proving capability refusal
     * against a state the platform can never actually be in.
     */
    private void cancel(UUID runId) {
        jdbc.update("alter table test_runs disable trigger all");
        jdbc.update("alter table execution_attempts disable trigger all");
        try {
            jdbc.update(
                    "update execution_attempts set attempt_state = 'FENCED', fenced_at = clock_timestamp()"
                            + " where run_id = ?",
                    runId);
            jdbc.update(
                    "update test_runs set lifecycle_state = 'STOPPING', stop_reason = 'USER_REQUESTED',"
                            + " cancellation_status = 'REQUESTED', cancellation_requested_at = clock_timestamp(),"
                            + " run_version = run_version + 1, updated_at = clock_timestamp() where run_id = ?",
                    runId);
        } finally {
            jdbc.update("alter table execution_attempts enable trigger all");
            jdbc.update("alter table test_runs enable trigger all");
        }
    }

    private void awaitLeaseExpiry(UUID runId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select lease_expires_at <= clock_timestamp() from execution_attempts where run_id = ?",
                        Boolean.class, runId)));
    }

    private ExecutionDispatch dispatchFor(UUID runId) throws Exception {
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId).get("payload"));
        return objectMapper.readValue(payload, ExecutionDispatch.class);
    }

    private static String validAttestation(Instant assessedAt) {
        return validAttestation(assessedAt, Map.of());
    }

    /**
     * An attestation document, optionally carrying egress controls.
     *
     * <p>Egress controls are absent by default, which is what an assessment produced by a deployment that
     * cannot enforce an allowlist looks like — and what every test that is not about egress should be using,
     * because it is the state that must keep refusing ALLOWLIST.
     */
    private static String validAttestation(Instant assessedAt, Map<String, String> egress) {
        Map<String, String> controls = new java.util.TreeMap<>();
        SandboxSecurityAttestation.REQUIRED_MANDATORY_CONTROLS.forEach(control -> controls.put(control, "PASS"));
        String probe = "sha256:" + "a".repeat(64);
        Instant truncated = assessedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var draft = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION,
                "kaas.sandbox.v1", probe, "docker", truncated, controls, egress, "");
        StringBuilder json = new StringBuilder("{\"schemaVersion\":\"")
                .append(SandboxSecurityAttestation.SCHEMA_VERSION)
                .append("\",\"securityProfileVersion\":\"kaas.sandbox.v1\",\"probeImageDigest\":\"")
                .append(probe)
                .append("\",\"runtime\":\"docker\",\"assessedAt\":\"")
                .append(truncated)
                .append("\",\"mandatoryControls\":{");
        json.append(asJsonBody(controls)).append("}");
        if (!egress.isEmpty()) {
            json.append(",\"egressControls\":{").append(asJsonBody(new java.util.TreeMap<>(egress))).append("}");
        }
        return json.append(",\"digest\":\"").append(draft.expectedDigest()).append("\"}").toString();
    }

    private static String asJsonBody(Map<String, String> entries) {
        return entries.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private HttpResponse<String> postInternal(String path, String bearer, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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
        return UUID.fromString(objectMapper.readTree(response.body()).get("runId").stringValue());
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
            UUID organizationId, UUID projectId, String bearer, String featureRevisionId, String profileRevisionId) {}

    private Tenant tenant() throws Exception {
        return tenant(false);
    }

    private Tenant tenant(boolean withSecretBinding) throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("execution-test", organizationId);
        String projectId = objectMapper
                .readTree(post("/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID())))
                        .body())
                .get("projectId")
                .stringValue();
        String featureRevision = objectMapper
                .readTree(post(
                                "/api/v1/projects/" + projectId + "/features",
                                bearer,
                                key(),
                                json(Map.of(
                                        "name", "Execution feature",
                                        "logicalPath", "features/e-" + UUID.randomUUID() + ".feature",
                                        "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();

        List<Map<String, Object>> secretBindings = new ArrayList<>();
        if (withSecretBinding) {
            String secretReferenceId = objectMapper
                    .readTree(post(
                                    "/api/v1/projects/" + projectId + "/secret-references",
                                    bearer,
                                    key(),
                                    json(Map.of("name", "api-token-" + UUID.randomUUID())))
                            .body())
                    .get("secretReferenceId")
                    .stringValue();
            secretBindings.add(Map.of("key", "API_TOKEN", "secretReferenceId", secretReferenceId));
        }
        String environmentRevision = objectMapper
                .readTree(post(
                                "/api/v1/projects/" + projectId + "/environments",
                                bearer,
                                key(),
                                json(Map.of(
                                        "name", "Execution environment",
                                        "variables",
                                                List.of(Map.of(
                                                        "key", "baseUrl", "type", "STRING",
                                                        "value", "https://environment.example")),
                                        "secretBindings", secretBindings)))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Execution profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = objectMapper
                .readTree(post("/api/v1/projects/" + projectId + "/run-profiles", bearer, key(), json(profile))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        return new Tenant(organizationId, UUID.fromString(projectId), bearer, featureRevision, profileRevision);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String serviceToken(String subject) throws Exception {
        return token(subject, null);
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
