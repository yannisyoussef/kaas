package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.KaasApiApplication;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.domain.EgressDestination;
import com.kaas.api.execution.domain.EgressScheme;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import com.kaas.api.execution.domain.NetworkPolicyType;
import com.kaas.egress.CanonicalDestination;
import java.time.Duration;
import com.kaas.egress.ControlPlaneAuthorizer;
import com.kaas.egress.DenialReason;
import com.kaas.egress.EgressAuthorizer;
import com.kaas.egress.Scheme;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.execution.ExecutionLoop;
import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.ProbeImage;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole thing, end to end, with nothing simulated.
 *
 * <p>A real PostgreSQL running the real migrations, the real control plane over real HTTP with real signed
 * tokens on the real filter chains, the real runner talking to it over the network, and a real container
 * running the real workload under the real hardened profile. No mocks, no in-memory substitutes, no shortcuts
 * into the service layer for the execution itself.
 *
 * <p>This module exists because neither other module can host this. {@code :apps:api}'s build fails if it
 * acquires a container runtime and {@code :services:runner}'s fails if it acquires the control plane — guards
 * that are the reason the launcher is allowed to talk to a Docker daemon at all. The test that needs both
 * therefore lives in a third place rather than either guard being weakened for it.
 *
 * <p>What it establishes that no unit test can: that the two independent implementations of the command digest
 * actually agree on a real command, that the phase transitions the database permits are the ones the runner
 * drives, and that a result produced by a sandbox is accepted as evidence by a control plane that never saw it
 * produced.
 */
@Testcontainers
@Import(SyntheticExecutionPipelineTests.JwtTestConfiguration.class)
@SpringBootTest(
        classes = KaasApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Every background timer off. This test drives the lifecycle explicitly, and a reconciler running
            // underneath it could stop a run mid-phase — which would look like a pipeline defect rather than
            // the test interfering with itself.
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            // Long enough that a real container start does not race the lease it runs under.
            "kaas.claim.lease-duration=PT120S",
            "kaas.claim.recovery-window=PT60S",
            "kaas.execution.authorization-ttl=PT5M",
            "kaas.execution.capability-ttl=PT5M",
            "kaas.scheduling.queue-timeout=PT10M"
        })
class SyntheticExecutionPipelineTests {

    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    /** In the kaas.worker. namespace, which both the service and the database guard require. */
    private static final String WORKER = "kaas.worker.pipeline";

    private static DockerClient DOCKER;
    private static String PROBE_IMAGE;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-pipeline");

    @DynamicPropertySource
    static void attestation(DynamicPropertyRegistry registry) {
        // Carries the egress controls as well as the mandatory ones, so this context can authorize an
        // ALLOWLIST run. It changes nothing for the DENY_ALL tests in this class: the egress controls are
        // consulted only for an allowlist policy, precisely so that a run wanting no network never depends on
        // the egress subsystem. The refusal case — an assessment making no egress claim — is pinned in
        // apps/api, where it can be asserted without a container.
        registry.add(
                "kaas.execution.sandbox-attestation",
                () -> ProducedAttestation.withEgress("kaas.sandbox.v1", Instant.now()));
        // The pinned trust root and the runtime this control plane accepts evidence for. Both are required
        // and both are deployment configuration: no key means nothing verifies, and no accepted subject means
        // a perfectly valid signature still authorizes nothing.
        registry.add("kaas.execution.attestation-trusted-keys", ProducedAttestation::trustedKeys);
        registry.add(
                "kaas.execution.attestation-runtime-subjects", () -> ProducedAttestation.RUNTIME_SUBJECT);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;
    @Autowired private com.kaas.api.execution.application.ExecutionDeadlineReconciler reconciler;

    @Test
    @DisplayName("a claimed run executes through every phase and completes on evidence a sandbox produced")
    void theWholeLifecycleComposes() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        ExecutionLoop.ExecutionReport report = loop().execute(runId, attemptId, 1);

        assertThat(report.status())
                .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                .isEqualTo("COMPLETED");
        assertThat(report.detail()).isEqualTo("PASSED");

        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, test_outcome, infrastructure_outcome, termination_reason,"
                        + " termination_phase, phase_deadline_at, execution_started_at, run_version, updated_by"
                        + " from test_runs where run_id = ?",
                runId);
        assertThat(run.get("lifecycle_state")).isEqualTo("COMPLETED");
        assertThat(run.get("test_outcome")).isEqualTo("PASSED");
        assertThat(run.get("infrastructure_outcome")).isEqualTo("SUCCEEDED");
        assertThat(run.get("termination_reason")).isEqualTo("EXECUTION_COMPLETED");
        assertThat(run.get("termination_phase")).isEqualTo("EXECUTION");
        // A terminal run holds no deadline. The CHECK enforces this, so its violation would have surfaced as a
        // constraint error rather than here — which is the point: this asserts the state, and the database
        // independently refuses the alternative.
        assertThat(run.get("phase_deadline_at")).isNull();
        assertThat(run.get("execution_started_at")).isNotNull();
        // The worker is named honestly, not double-prefixed and not the platform generally.
        assertThat(run.get("updated_by")).isEqualTo(WORKER);

        // Every phase left an event, in order, with no gaps. The sequence is what makes a lost transition
        // visible: a run that skipped COLLECTING_RESULTS would still be COMPLETED and would still have a
        // result, and only the event chain would show it.
        List<String> states = jdbc.queryForList(
                "select lifecycle_state from run_lifecycle_events where run_id = ? order by sequence",
                String.class, runId);
        assertThat(states)
                .containsExactly(
                        "QUEUED", "CLAIMED", "PROVISIONING", "RUNNING", "COLLECTING_RESULTS",
                        "PROCESSING_RESULTS", "COMPLETED");

        // The evidence, bound to the assignment that produced it.
        Map<String, Object> result = jdbc.queryForMap(
                "select r.assignment_epoch, r.test_outcome, r.infrastructure_outcome, r.result_digest,"
                        + " r.run_snapshot_sha256, r.document::text as document, t.snapshot_sha256"
                        + " from execution_results r join test_runs t on t.run_id = r.run_id where r.run_id = ?",
                runId);
        assertThat(result.get("assignment_epoch")).isEqualTo(1);
        assertThat(result.get("test_outcome")).isEqualTo("PASSED");
        assertThat(result.get("infrastructure_outcome")).isEqualTo("SUCCEEDED");
        assertThat(result.get("result_digest").toString()).matches("^sha256:[a-f0-9]{64}$");
        // The result names the snapshot the run actually sealed, not one the worker chose.
        assertThat(result.get("run_snapshot_sha256")).isEqualTo(result.get("snapshot_sha256"));

        JsonNode document = mapper.readTree(result.get("document").toString());
        // It says what it is. A consumer that cannot distinguish a synthetic execution from an engine run will
        // eventually read a green synthetic result as a passing test suite.
        assertThat(document.get("producer").asString()).isEqualTo("kaas-runner-synthetic");
        assertThat(document.get("infrastructureOutcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(document.get("resultCompleteness").asString()).isEqualTo("COMPLETE");
        // Zero tenant features ran, and the document says zero rather than claiming the workload's own
        // assertions were the tenant's scenarios.
        assertThat(document.get("features")).isEmpty();
        assertThat(document.at("/summary/scenarios/total").asInt()).isZero();
        assertThat(document.toString()).doesNotContain("KARATE");

        // The attempt carries its own execution history, and it is chronological.
        Map<String, Object> attempt = jdbc.queryForMap(
                "select provisioned_at, execution_started_at, execution_finished_at, sandbox_reference,"
                        + " infrastructure_disposition from execution_attempts where attempt_id = ?",
                attemptId);
        assertThat(attempt.get("provisioned_at")).isNotNull();
        assertThat(attempt.get("execution_started_at")).isNotNull();
        assertThat(attempt.get("execution_finished_at")).isNotNull();
        assertThat(attempt.get("sandbox_reference").toString()).startsWith("sandbox-");
        assertThat(attempt.get("infrastructure_disposition")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("a failing workload completes the run as FAILED while the infrastructure still SUCCEEDED")
    void aFailingTestIsNotAFailingExecution() throws Exception {
        // The tag selects the failing workload. Both are platform-owned probes in the trusted image; the tenant
        // is choosing between two fixed workloads, not supplying content.
        UUID runId = claimedRun(List.of("@smoke"));

        // The failing workload is chosen by RUNNER CONFIGURATION, not by anything on the command. A tenant has
        // no way to select it — which is the point: the outcome of a run must not be something its owner can
        // dictate out of band.
        ExecutionLoop.ExecutionReport report =
                loop(com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_FAIL).execute(runId, attemptId(runId), 1);
        assertThat(report.status())
                .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                .isEqualTo("COMPLETED");

        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, test_outcome, infrastructure_outcome, termination_reason"
                        + " from test_runs where run_id = ?",
                runId);
        // THE ORTHOGONALITY, end to end. The test failed and the infrastructure did not. If these were one
        // column, every red test would be reported as a broken platform — and the people who would notice are
        // the ones who would stop trusting the platform's own failure reports.
        assertThat(run.get("lifecycle_state")).isEqualTo("COMPLETED");
        assertThat(run.get("test_outcome")).isEqualTo("FAILED");
        assertThat(run.get("infrastructure_outcome")).isEqualTo("SUCCEEDED");
        assertThat(run.get("termination_reason")).isEqualTo("EXECUTION_COMPLETED");

        assertThat(jdbc.queryForObject(
                        "select test_outcome from execution_results where run_id = ?", String.class, runId))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a second submission from the same assignment is refused, and the first result stands")
    void evidenceIsWrittenOnce() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        ExecutionLoop.ExecutionReport first = loop().execute(runId, attemptId, 1);
        assertThat(first.status())
                .as("report was %s at %s: %s", first.status(), first.phase(), first.detail())
                .isEqualTo("COMPLETED");

        String digestBefore = jdbc.queryForObject(
                "select result_digest from execution_results where run_id = ?", String.class, runId);

        // Replaying the whole loop. It stops at authorization, because the run is COMPLETED — which is the
        // correct place to stop, and means the duplicate never reaches the result table at all.
        ExecutionLoop.ExecutionReport replay = loop().execute(runId, attemptId, 1);
        assertThat(replay.status()).isEqualTo("REFUSED");

        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select result_digest from execution_results where run_id = ?", String.class, runId))
                .isEqualTo(digestBefore);
    }

    @Test
    @DisplayName("a worker holding a superseded epoch cannot advance a phase")
    void afencedAssignmentCannotDriveTheRun() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        // Epoch 2 does not exist. Identity alone would let this through — the worker is the right worker — and
        // it is the epoch that says which assignment it is speaking for.
        ExecutionLoop.ExecutionReport report = loop().execute(runId, attemptId, 2);

        assertThat(report.status()).isEqualTo("REFUSED");
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    @DisplayName("the phase endpoint independently refuses a superseded epoch, a foreign worker, and a wrong phase")
    void thePhaseEndpointRevalidatesOnItsOwn() throws Exception {
        // Called directly rather than through the loop. Every one of these refusals is a SECOND line of defence
        // behind authorization, and going through the loop would stop at authorization — leaving the phase
        // service's own checks unexecuted by any test. A check nothing reaches is a check nothing is testing,
        // and deleting it would kill nothing.
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        // Right worker, wrong epoch. Identity alone would admit this.
        assertThat(advance(runId, attemptId, 2, "PROVISIONING", WORKER).statusCode()).isEqualTo(409);
        // Right epoch, wrong worker. The epoch alone would admit this.
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", "kaas.worker.other").statusCode())
                .isEqualTo(409);
        // A platform principal that is not a worker at all.
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", "kaas.scheduler").statusCode()).isEqualTo(409);
        // A phase the run cannot enter from CLAIMED.
        assertThat(advance(runId, attemptId, 1, "COLLECTING_RESULTS", WORKER).statusCode()).isEqualTo(409);

        // The run has not moved, and none of those refusals armed a deadline.
        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, phase_deadline_at, run_version from test_runs where run_id = ?", runId);
        assertThat(run.get("lifecycle_state")).isEqualTo("CLAIMED");
        assertThat(run.get("phase_deadline_at")).isNull();

        // And the legitimate request still works, so the refusals above are specific rather than a blanket
        // failure that would make all four assertions pass for the wrong reason.
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", WORKER).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("PROVISIONING");
    }

    @Test
    @DisplayName("the result endpoint independently refuses a superseded epoch and a command it never issued")
    void theResultEndpointRevalidatesOnItsOwn() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        // Authorize first. The result endpoint checks the submitted command against the one this assignment was
        // actually issued, and without an authorization there is no issued command to check against — the test
        // would then pass because nothing had been issued rather than because the check works.
        assertThat(authorize(runId, attemptId, 1).statusCode()).isEqualTo(200);
        // Drive the run to PROCESSING_RESULTS so the result endpoint's own checks are the ones being tested,
        // rather than the phase check refusing first.
        for (String phase : List.of("PROVISIONING", "RUNNING", "COLLECTING_RESULTS", "PROCESSING_RESULTS")) {
            assertThat(advance(runId, attemptId, 1, phase, WORKER).statusCode()).isEqualTo(200);
        }

        UUID issuedCommand = jdbc.queryForObject(
                "select command_id from execution_commands where attempt_id = ? and assignment_epoch = 1",
                UUID.class, attemptId);

        // A superseded epoch. The CODE matters: every distinct cause on this path is 409, so asserting the
        // status alone passed with the whole identity check deleted — the request simply fell through to the
        // issued-command lookup and was refused there instead.
        HttpResponse<String> wrongEpoch = submitResult(runId, attemptId, 2, issuedCommand, "{}");
        assertThat(wrongEpoch.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(wrongEpoch.body()).get("code").asString()).isEqualTo("ASSIGNMENT_STALE");

        // And a FOREIGN WORKER, which no test submitted a result as at all. Since assignment acquisition, the
        // holder is a real per-process identity rather than a deployment-wide constant, so this is now a
        // meaningful thing to refuse.
        HttpResponse<String> foreignWorker = postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/results",
                "kaas.worker.intruder",
                json(Map.of(
                        "assignmentEpoch", 1,
                        "commandId", issuedCommand.toString(),
                        "document", resultDocument(runId, attemptId, 1, issuedCommand))));
        assertThat(foreignWorker.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(foreignWorker.body()).get("code").asString()).isEqualTo("ASSIGNMENT_STALE");
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .as("a foreign worker must not be able to write another assignment's evidence")
                .isZero();
        // A command this assignment was never issued. Without checking against execution_commands, the only
        // comparison would be the request's own value against the document's own value — two fields the same
        // caller supplies, which agree whatever value was chosen.
        assertThat(submitResult(runId, attemptId, 1, UUID.randomUUID(), "{}").statusCode()).isEqualTo(409);

        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("PROCESSING_RESULTS");
    }

    @Test
    @DisplayName("a cancelled run refuses further phase advances")
    void aCancelledRunStopsAdvancing() throws Exception {
        Tenant tenant = tenant(List.of("@smoke"));
        String tenantBearer = tenant.bearer();
        UUID runId = claimedRunFor(tenant);
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", WORKER).statusCode()).isEqualTo(200);

        // Cancelled through the real tenant API rather than by writing STOPPING directly. A raw UPDATE is
        // refused by the scheduling-bundle guard — a stopping run must have its assignment fenced — and
        // hand-rolling that here would be reproducing the cancellation path badly in order to test around it.
        assertThat(post("/api/v1/runs/" + runId + "/cancellations", tenantBearer,
                        json(Map.of("reason", "USER_REQUESTED"))).statusCode())
                .isBetween(200, 299);

        HttpResponse<String> refusedAfterCancel = advance(runId, attemptId, 1, "RUNNING", WORKER);
        assertThat(refusedAfterCancel.statusCode()).isEqualTo(409);
        // RUN_STOPPING, not ASSIGNMENT_STALE. Cancellation fences the assignment in the same transaction, so
        // "you are fenced" is also true — but it is the wrong thing to tell this worker. Nobody else has taken
        // this run; it was cancelled. A worker that believes it was superseded reports something different to
        // its operator than one that knows the run was stopped, and only the second is what happened.
        assertThat(mapper.readTree(refusedAfterCancel.body()).get("code").asString()).isEqualTo("RUN_STOPPING");
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("STOPPING");
    }

    @Test
    @DisplayName("a lapsed lease stops the worker even though nothing has fenced it yet")
    void aLapsedLeaseRefusesFurtherPhases() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", WORKER).statusCode()).isEqualTo(200);

        // Age the lease past now. Triggers are suspended for exactly this statement because the attempt guard
        // correctly refuses an arbitrary lease rewrite — the state being constructed is one the system reaches
        // on its own, just only after the lease duration elapses, and waiting that out in a test would make it
        // slow without making it stronger.
        //
        // Deliberately NOT fenced. Fencing is what the reconciler does later, on its own schedule, and this is
        // the window in between: checking only the fence flag would let a worker keep driving a run for as long
        // as the reconciler happened to be behind.
        ageLease(runId);

        HttpResponse<String> refused = advance(runId, attemptId, 1, "RUNNING", WORKER);
        assertThat(refused.statusCode()).isEqualTo(409);
        // The CODE, not just the status. The database independently refuses a history write under a lapsed
        // lease, and that also surfaces as 409 — so asserting the status alone would pass with the service's
        // own lease check deleted, and the check would be covered by nothing.
        assertThat(mapper.readTree(refused.body()).get("code").asString()).isEqualTo("LEASE_EXPIRED");
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("PROVISIONING");
    }

    @Test
    @DisplayName("a result whose document names a different epoch than the request is refused")
    void aResultDocumentMustAgreeWithTheAssignmentItWasSubmittedUnder() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        assertThat(authorize(runId, attemptId, 1).statusCode()).isEqualTo(200);
        for (String phase : List.of("PROVISIONING", "RUNNING", "COLLECTING_RESULTS", "PROCESSING_RESULTS")) {
            assertThat(advance(runId, attemptId, 1, phase, WORKER).statusCode()).isEqualTo(200);
        }
        UUID issued = jdbc.queryForObject(
                "select command_id from execution_commands where attempt_id = ? and assignment_epoch = 1",
                UUID.class, attemptId);

        // A WELL-FORMED document, differing only in the epoch it claims. The earlier negative tests submitted
        // "{}", which fails to parse before any provenance field is compared — so every provenance check below
        // the parse was reached by nothing.
        String wrongEpoch = resultDocument(runId, attemptId, 2, issued);
        var response = submitResult(runId, attemptId, 1, issued, wrongEpoch);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(response.body()).get("code").asString())
                .isEqualTo("RESULT_PROVENANCE_MISMATCH");

        // And a well-formed document answering a command this assignment was never issued — with the SAME
        // invented identifier in the request and in the document, so they agree with each other perfectly.
        // That is the whole point: two fields the caller supplies always agree, whatever value was chosen, so
        // the only thing that can refuse this is comparing it against the command the control plane issued.
        // An earlier version of this test invented two different identifiers, which disagreed with each other
        // and were refused by the wrong check — leaving the issued-command comparison covered by nothing.
        UUID neverIssued = UUID.randomUUID();
        String wrongCommand = resultDocument(runId, attemptId, 1, neverIssued);
        var other = submitResult(runId, attemptId, 1, neverIssued, wrongCommand);
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(other.body()).get("code").asString())
                .isEqualTo("RESULT_PROVENANCE_MISMATCH");

        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isZero();

        // The equivalent well-formed document that agrees with everything IS accepted, so the refusals above
        // are about the mismatched field rather than about the document being unacceptable for some other
        // reason this test never noticed.
        assertThat(submitResult(runId, attemptId, 1, issued, resultDocument(runId, attemptId, 1, issued))
                        .statusCode())
                .isEqualTo(200);
    }

    /** A result document that agrees with authoritative state except where a caller changes it. */
    private String resultDocument(UUID runId, UUID attemptId, int epoch, UUID commandId) throws Exception {
        Map<String, Object> run = jdbc.queryForMap(
                "select project_id, organization_id, execution_started_at from test_runs where run_id = ?",
                runId);
        long runVersion = jdbc.queryForObject(
                "select (document->>'runVersion')::bigint from execution_commands where attempt_id = ?",
                Long.class, attemptId);
        Instant startedAt = ((java.sql.Timestamp) run.get("execution_started_at")).toInstant();
        Map<String, Object> zero = Map.of(
                "total", 0, "passed", 0, "failed", 0, "skipped", 0, "aborted", 0);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "1.0");
        document.put("messageId", UUID.randomUUID().toString());
        document.put("messageType", "EXECUTION_RESULT");
        document.put("occurredAt", startedAt.plusSeconds(1).toString());
        document.put("producer", "kaas-runner-synthetic");
        document.put("correlationId", runId.toString());
        document.put("causationId", commandId.toString());
        document.put("resultId", UUID.randomUUID().toString());
        document.put("commandId", commandId.toString());
        document.put("organizationId", run.get("organization_id").toString());
        document.put("projectId", run.get("project_id").toString());
        document.put("runId", runId.toString());
        document.put("runVersion", runVersion);
        document.put("attemptId", attemptId.toString());
        document.put("attemptNumber", 1);
        document.put("assignmentEpoch", epoch);
        document.put("startedAt", startedAt.toString());
        document.put("finishedAt", startedAt.plusSeconds(1).toString());
        document.put("testOutcome", "PASSED");
        document.put("infrastructureOutcome", "SUCCEEDED");
        document.put("resultCompleteness", "COMPLETE");
        document.put("timings", Map.of(
                "provisioning", Map.of("durationMilliseconds", 1),
                "execution", Map.of("durationMilliseconds", 1),
                "reporting", Map.of("durationMilliseconds", 1)));
        document.put("summary", Map.of("features", zero, "scenarios", zero, "steps", zero));
        document.put("features", List.of());
        return json(document);
    }

    @Test
    @DisplayName("a phase deadline stops the run, fences its assignment, and names the phase that elapsed")
    void aDeadlineStoppedRunRefusesFurtherPhases() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", WORKER).statusCode()).isEqualTo(200);

        // Age the deadline rather than waiting two minutes for it.
        expirePhaseDeadline(runId);
        assertThat(reconciler.reconcile()).isEqualTo(1);

        Map<String, Object> stopped = jdbc.queryForMap(
                "select lifecycle_state, stop_reason, updated_by from test_runs where run_id = ?", runId);
        assertThat(stopped.get("lifecycle_state")).isEqualTo("STOPPING");
        // The reason names the phase that actually elapsed, not merely "a deadline".
        assertThat(stopped.get("stop_reason")).isEqualTo("PROVISIONING_DEADLINE");
        assertThat(stopped.get("updated_by")).isEqualTo("kaas.execution-reconciler");

        // The deadline stop also fences the assignment, and it must: the scheduling-bundle constraint refuses a
        // STOPPING run that still holds a live one. An earlier version of this reconciler left the attempt
        // CLAIMED, and every deadline stop failed at commit — silently, because the reconciler catches
        // per-run failures so one bad run cannot abandon the batch. The failure counter was the only evidence.
        Map<String, Object> attempt = jdbc.queryForMap(
                "select attempt_state, fenced_at from execution_attempts where attempt_id = ?", attemptId);
        assertThat(attempt.get("attempt_state")).isEqualTo("FENCED");
        assertThat(attempt.get("fenced_at")).isNotNull();

        HttpResponse<String> refusedAfterDeadline = advance(runId, attemptId, 1, "RUNNING", WORKER);
        assertThat(refusedAfterDeadline.statusCode()).isEqualTo(409);
        // The same reason as a cancellation, and correctly so: the platform stopped this run, it did not hand
        // it to anybody else. The worker learns the run is over rather than that it lost a race it never ran.
        assertThat(mapper.readTree(refusedAfterDeadline.body()).get("code").asString())
                .isEqualTo("RUN_STOPPING");
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("STOPPING");
    }

    @Test
    @DisplayName("each execution phase's deadline names the phase that actually elapsed")
    void everyPhaseDeadlineNamesItsOwnPhase() throws Exception {
        // Only PROVISIONING was covered. Collapsing all four arms of the reconciler's phase-to-reason mapping
        // to one value, or swapping RUNNING with COLLECTING_RESULTS, killed no test — so the reason an operator
        // reads was free to describe a phase the run was never in.
        record Case(List<String> reach, String phase, String reason) {}
        List<Case> cases = List.of(
                new Case(List.of("PROVISIONING"), "PROVISIONING", "PROVISIONING_DEADLINE"),
                new Case(List.of("PROVISIONING", "RUNNING"), "RUNNING", "EXECUTION_DEADLINE"),
                new Case(List.of("PROVISIONING", "RUNNING", "COLLECTING_RESULTS"),
                        "COLLECTING_RESULTS", "RESULT_DEADLINE"),
                new Case(List.of("PROVISIONING", "RUNNING", "COLLECTING_RESULTS", "PROCESSING_RESULTS"),
                        "PROCESSING_RESULTS", "RESULT_DEADLINE"));

        for (Case testCase : cases) {
            UUID runId = claimedRun(List.of("@smoke"));
            UUID attemptId = attemptId(runId);
            for (String phase : testCase.reach()) {
                assertThat(advance(runId, attemptId, 1, phase, WORKER).statusCode()).isEqualTo(200);
            }
            expirePhaseDeadline(runId);
            assertThat(reconciler.reconcile()).isGreaterThanOrEqualTo(1);

            Map<String, Object> stopped = jdbc.queryForMap(
                    "select lifecycle_state, stop_reason from test_runs where run_id = ?", runId);
            assertThat(stopped.get("lifecycle_state")).isEqualTo("STOPPING");
            assertThat(stopped.get("stop_reason"))
                    .as("a deadline that elapsed in %s must be named %s", testCase.phase(), testCase.reason())
                    .isEqualTo(testCase.reason());
        }
    }

    /**
     * Moves a lease into the past.
     *
     * <p>Triggers are suspended around it. That is not a way around an invariant — the invariant is that a
     * lease may only move forward, and it is correct. This constructs the state that ordinary time produces,
     * without spending the ordinary time.
     */
    private void ageLease(UUID runId) {
        // The whole lease window moves, not just its end. ck_execution_attempts_lease_window requires the
        // expiry to follow the start, and it is a CHECK rather than a trigger — so suspending triggers does
        // not suspend it, and it should not: an expired lease is a lease whose window is in the past, not one
        // that ended before it began.
        withTriggersSuspended(
                "update execution_attempts"
                        + " set lease_started_at = clock_timestamp() - interval '5 minutes',"
                        + "     last_heartbeat_at = clock_timestamp() - interval '5 minutes',"
                        + "     lease_expires_at = clock_timestamp() - interval '1 minute'"
                        + " where run_id = ?",
                runId);
    }

    private void expirePhaseDeadline(UUID runId) {
        withTriggersSuspended(
                "update test_runs set phase_deadline_at = clock_timestamp() - interval '1 minute'"
                        + " where run_id = ?",
                runId);
    }

    /**
     * Runs one statement with triggers suspended, on ONE connection.
     *
     * <p>The single connection is the load-bearing part. {@code JdbcTemplate} takes a connection per operation,
     * so setting {@code session_replication_role} in one call and running the UPDATE in the next can land on
     * two different pooled connections — leaving the setting on a connection that does nothing with it and the
     * UPDATE running with triggers fully active. It fails confusingly or, worse, silently does nothing.
     *
     * <p>The role is restored on the same connection before it goes back to the pool. A connection handed back
     * still in replica role would disable every trigger for whatever ran next, which would quietly turn other
     * tests into no-ops.
     */
    private void withTriggersSuspended(String sql, UUID runId) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
            }
            try (var update = connection.prepareStatement(sql)) {
                update.setObject(1, runId);
                assertThat(update.executeUpdate())
                        .as("the state this test depends on was not actually constructed")
                        .isEqualTo(1);
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    @Test
    @DisplayName("a worker that lost the response to a successful submission is told it already succeeded")
    void aRetriedSubmissionReportsSuccessRatherThanStaleness() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);
        assertThat(authorize(runId, attemptId, 1).statusCode()).isEqualTo(200);
        for (String phase : List.of("PROVISIONING", "RUNNING", "COLLECTING_RESULTS", "PROCESSING_RESULTS")) {
            assertThat(advance(runId, attemptId, 1, phase, WORKER).statusCode()).isEqualTo(200);
        }
        UUID issued = jdbc.queryForObject(
                "select command_id from execution_commands where attempt_id = ? and assignment_epoch = 1",
                UUID.class, attemptId);
        String document = resultDocument(runId, attemptId, 1, issued);
        assertThat(submitResult(runId, attemptId, 1, issued, document).statusCode()).isEqualTo(200);

        // The identical submission again, exactly as a worker whose response was lost would send it.
        HttpResponse<String> retry = submitResult(runId, attemptId, 1, issued, document);
        assertThat(retry.statusCode()).isEqualTo(409);
        // RESULT_ALREADY_SUBMITTED, not ASSIGNMENT_STALE.
        //
        // Accepting a result completes the run, and completing it fences the assignment — so by the time this
        // retry arrives the worker's assignment is legitimately fenced, and a liveness check ordered first
        // answers "somebody else has this now". That is the worst available answer: the worker would conclude
        // it lost the run and report a failure that never happened, when in fact its result was accepted.
        assertThat(mapper.readTree(retry.body()).get("code").asString())
                .isEqualTo("RESULT_ALREADY_SUBMITTED");

        // And exactly one result stands, unchanged.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    private HttpResponse<String> authorize(UUID runId, UUID attemptId, int epoch) throws Exception {
        return postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/execution-authorizations",
                WORKER,
                json(Map.of("assignmentEpoch", epoch)));
    }

    private HttpResponse<String> advance(
            UUID runId, UUID attemptId, int epoch, String phase, String worker) throws Exception {
        return postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/phases",
                worker,
                json(Map.of("assignmentEpoch", epoch, "phase", phase, "sandboxReference", "sandbox-test")));
    }

    private HttpResponse<String> submitResult(
            UUID runId, UUID attemptId, int epoch, UUID commandId, String document) throws Exception {
        return postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/results",
                WORKER,
                json(Map.of(
                        "assignmentEpoch", epoch,
                        "commandId", commandId.toString(),
                        "document", document)));
    }

    /** No status assertion: these calls are expected to be refused, and the caller checks the status. */
    private HttpResponse<String> postInternal(String path, String worker, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token(worker, null))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------------------

    /** The runner, pointed at the control plane over real HTTP with a real service token. */
    @Test
    @DisplayName("a worker that instantiates a different runtime than the command authorizes refuses to run")
    void aWorkerConfiguredForAnotherRuntimeRefusesTheCommand() throws Exception {
        // THE THIRD LINK. The signed attestation names a boundary, the command copies it out of that signed
        // payload, and this is the only place the runtime a worker will ACTUALLY instantiate can be compared
        // against either -- no other component knows what this process is configured to launch.
        //
        // The run is authorized normally, under the baseline runtime, and handed to a worker configured for
        // the mediating one. That is the dangerous direction inverted for testability: the check is symmetric,
        // and this side needs no gVisor on the host because the refusal happens before any container exists.
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        ExecutionLoop.ExecutionReport report = loopUnderRuntime(ExecutionRuntimeType.GVISOR)
                .execute(runId, attemptId, 1);

        // Reported to the control plane, not merely returned. By this point the run is RUNNING with a phase
        // deadline against it, and a worker that noticed the mismatch and went quiet would leave the run to
        // be reclaimed later as a timeout -- a failure observed in milliseconds and then discarded.
        assertThat(report.status())
                .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                .isEqualTo("INFRASTRUCTURE_FAILED");
        assertThat(report.detail())
                .as("the refusal must name the runtime mismatch rather than an unrecognised profile string")
                .contains("different sandbox runtime");

        // The DETAIL as well as the status: the status alone is set whether or not the control plane
        // accepted the report, so asserting it by itself would let a refused report pass as success.
        assertThat(report.detail())
                .as("the control plane must have accepted the failure report")
                .doesNotContain("report refused");

        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, stop_reason, test_outcome from test_runs where run_id = ?", runId);
        assertThat(run.get("lifecycle_state")).isEqualTo("STOPPING");
        assertThat(run.get("stop_reason")).isEqualTo("INFRASTRUCTURE_FAILURE");
        // NO test outcome was invented. The tenant's work never started, and recording a verdict for it here
        // would be the platform blaming a tenant for its own misconfiguration.
        assertThat(run.get("test_outcome")).isNull();

        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .as("nothing ran, so there is nothing to have produced a result")
                .isZero();
    }

    @Test
    @DisplayName("cancelling a run stops the workload that is already inside the sandbox")
    void cancellationStopsARunningWorkload() throws Exception {
        try {
            // THE PROPERTY THIS SLICE EXISTS FOR, end to end through the real control plane.
            //
            // Database fencing already refused a stale worker's writes, and there are tests for that. None of them
            // says anything about the workload: before this, a cancelled run's sandbox kept running until it
            // finished on its own or hit the profile deadline. The workload here sleeps for an hour, so it will
            // not finish on its own, and the deadline is far beyond what this test waits for.
            Tenant tenant = tenant(List.of("@smoke"));
            UUID runId = claimedRunFor(tenant);
            UUID attemptId = attemptId(runId);
    
            var report = new java.util.concurrent.atomic.AtomicReference<ExecutionLoop.ExecutionReport>();
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    report.set(loop(com.kaas.runner.sandbox.SyntheticProbe.SLEEP).execute(runId, attemptId, 1));
                } catch (Throwable failure) {
                    // Kept, never discarded. A racer that died is a result, and swallowing it here would turn a
                    // broken worker into a test that simply waits for a report that never arrives.
                    thrown.set(failure);
                }
            });
            worker.start();
    
            // Wait until the workload is genuinely inside a sandbox. Cancelling earlier would exercise the
            // provisioning path, which is a different property.
            waitUntil(() -> "RUNNING".equals(jdbc.queryForObject(
                    "select lifecycle_state from test_runs where run_id = ?", String.class, runId)));
    
            Instant cancelledAt = Instant.now();
            assertThat(post("/api/v1/runs/" + runId + "/cancellations", tenant.bearer(),
                            json(Map.of("reason", "USER_REQUESTED"))).statusCode())
                    .isBetween(200, 299);
    
            worker.join(Duration.ofSeconds(90).toMillis());
            Duration stoppedIn = Duration.between(cancelledAt, Instant.now());
    
            assertThat(thrown.get()).isNull();
            assertThat(worker.isAlive()).as("the worker must return, not hang").isFalse();
            // It stopped because its authority ended, and it says so -- rather than reporting a timeout, which is
            // what a worker that merely ran into the profile deadline would report.
            assertThat(report.get()).isNotNull();
            assertThat(report.get().status())
                    .as("report was %s / %s", report.get().status(), report.get().detail())
                    .isEqualTo("AUTHORITY_LOST");
            assertThat(report.get().detail()).isEqualTo("RUN_NOT_OWNED");
            // Bounded by the platform: one heartbeat interval to notice, one graceful window to stop.
            // 18 seconds separates two different reasons the worker could have returned.
            //
            // A real termination is one heartbeat interval to notice plus one graceful window to stop:
            // measured at 10.2s. A sandbox that was never terminated still disappears when its own 30-second
            // profile deadline fires, and the loop's authority check then reports exactly what is asserted
            // above -- measured at 30.1s under a mutation that dropped the authority from the launch. A looser
            // bound admits both, which proves the report and not the termination. Those are the two axes.
            assertThat(stoppedIn)
                    .as("stopped in %s, which must be sooner than the sandbox's own deadline could explain",
                            stoppedIn)
                    .isLessThan(Duration.ofSeconds(18));
    
            // NO STALE SUCCESS. The run is cancelled, and no result was submitted by a worker that had already
            // lost the right to submit one.
            Map<String, Object> run = jdbc.queryForMap(
                    "select lifecycle_state, test_outcome from test_runs where run_id = ?", runId);
            assertThat(run.get("test_outcome")).isNull();
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                    .isZero();
            assertThat(managedContainers()).as("no sandbox outlives the cancellation").isEmpty();
        } finally {
            // Always, including when an assertion above failed. See removeAnyManagedContainers.
            removeAnyManagedContainers();
        }
    }

    @Test
    @DisplayName("a prolonged control-plane outage stops the workload once the lease budget is gone")
    void aProlongedOutageStopsTheWorkload() throws Exception {
        // CASE E. The control plane is reachable for everything except renewals, so the worker authorizes and
        // starts normally and then cannot prove it still owns the assignment. Nothing tells it to stop; it
        // stops itself, because the lease it is relying on can no longer be assumed valid.
        //
        // This is the case that distinguishes a bounded authority from an unbounded one. A worker that
        // continued on "last known good" would keep the workload running indefinitely, and every database
        // fencing test in this repository would still pass.
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        try (ControlPlaneFaultProxy proxy = new ControlPlaneFaultProxy(port)) {
            proxy.failRenewals();
            var report = new java.util.concurrent.atomic.AtomicReference<ExecutionLoop.ExecutionReport>();
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    report.set(loopThrough(proxy.baseUri(), com.kaas.runner.sandbox.SyntheticProbe.SLEEP)
                            .execute(runId, attemptId, 1));
                } catch (Throwable failure) {
                    thrown.set(failure);
                }
            });
            Instant startedAt = Instant.now();
            worker.start();
            worker.join(Duration.ofSeconds(120).toMillis());
            Duration stoppedIn = Duration.between(startedAt, Instant.now());

            assertThat(thrown.get()).isNull();
            assertThat(worker.isAlive()).as("the worker must stop itself").isFalse();
            assertThat(proxy.renewalsSeen()).as("it did try to renew").isPositive();
            assertThat(report.get().status())
                    .as("report was %s / %s", report.get().status(), report.get().detail())
                    .isEqualTo("AUTHORITY_LOST");
            // Named as the lease expiring rather than as a network fault. What ended is the authority; the
            // outage is only why it could not be renewed.
            assertThat(report.get().detail()).isEqualTo("LEASE_EXPIRED");
            // Bounded by the budget the runner starts with, not by the sandbox's own deadline.
            assertThat(stoppedIn).as("stopped in %s", stoppedIn).isLessThan(Duration.ofSeconds(60));

            // NO SUCCESSFUL RESULT from a worker that could not prove it still had authority.
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                    .isZero();
            assertThat(managedContainers()).isEmpty();
        } finally {
            removeAnyManagedContainers();
        }
    }

    @Test
    @DisplayName("a transient outage inside the lease budget does not stop a healthy run")
    void aTransientOutageIsSurvived() throws Exception {
        // CASE B, and the counterweight to everything above. A mechanism that stops workloads is only useful
        // if it does not stop healthy ones: killing a run on one missed renewal would turn ordinary network
        // latency into lost work, which is precisely why the previous design swallowed failures entirely.
        UUID runId = claimedRun(List.of("@smoke"));
        UUID attemptId = attemptId(runId);

        try (ControlPlaneFaultProxy proxy = new ControlPlaneFaultProxy(port)) {
            var report = new java.util.concurrent.atomic.AtomicReference<ExecutionLoop.ExecutionReport>();
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    report.set(loopThrough(proxy.baseUri(), com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_PASS)
                            .execute(runId, attemptId, 1));
                } catch (Throwable failure) {
                    thrown.set(failure);
                }
            });
            // Renewals fail for one interval and then recover, well inside the budget.
            proxy.failRenewals();
            worker.start();
            Thread.sleep(Duration.ofSeconds(6).toMillis());
            proxy.restoreRenewals();

            worker.join(Duration.ofSeconds(120).toMillis());

            assertThat(thrown.get()).isNull();
            assertThat(report.get()).isNotNull();
            assertThat(report.get().status())
                    .as("report was %s / %s", report.get().status(), report.get().detail())
                    .isEqualTo("COMPLETED");
            assertThat(report.get().detail()).isEqualTo("PASSED");
            assertThat(jdbc.queryForObject(
                            "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                    .isEqualTo("COMPLETED");
        } finally {
            removeAnyManagedContainers();
        }
    }

    /** A loop whose control plane is reached through the given base URI. */
    private ExecutionLoop loopThrough(java.net.URI baseUri, com.kaas.runner.sandbox.SyntheticProbe workload)
            throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                baseUri,
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(probeImage());
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper),
                new DockerSandboxLauncher(docker(), profile, "pipeline"),
                mapper,
                Clock.systemUTC(),
                workload);
    }

    /**
     * Removes anything a revocation test left behind, whatever happened to it.
     *
     * <p>Not politeness. The workload these tests use sleeps for an hour, so a test that fails before its
     * worker thread finishes leaves that sandbox running — and the next test, which asserts that no managed
     * container exists, then fails for a reason that has nothing to do with what it was testing. That happened:
     * one broken SQL statement produced three red tests, two of them innocent.
     */
    private void removeAnyManagedContainers() {
        for (var container : managedContainers()) {
            try {
                docker().removeContainerCmd(container.getId()).withForce(true).exec();
            } catch (RuntimeException alreadyGone) {
                // Removing something that is already gone is exactly the outcome wanted.
            }
        }
    }

    /** Every container this pipeline's launcher generation created and did not remove. */
    private java.util.List<com.github.dockerjava.api.model.Container> managedContainers() {
        return docker().listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("kaas.launcher.generation", "pipeline"))
                .exec();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true");
            }
            Thread.sleep(100);
        }
    }

    @Test
    @DisplayName("source delivery is refused outright on a runtime that cannot enforce the source filesystem")
    void sourceDeliveryIsRefusedOnTheBaselineRuntime() throws Exception {
        // THE FAIL-CLOSED HALF OF KAAS-19, and it is what this job can prove.
        //
        // The hardened source filesystem is a tmpfs the bootstrap remounts read-only from inside the sandbox.
        // Measured: the mediating runtime permits that because its sentry implements mount; the baseline
        // runtime refuses it outright, so a source filesystem built there could never be closed. This
        // pipeline runs on the baseline runtime, so what it can establish is that the runner does not try.
        //
        // Refused BEFORE a container exists, not attempted and abandoned. A worker that framed the bundle
        // first would put tenant bytes inside a sandbox and only then discover it could not close the
        // filesystem around them -- which is the state the whole slice exists to make unreachable.
        //
        // The positive path -- source populated, verified, frozen, and read back with ro and noexec enforced
        // -- is proven in MediatedSourceFilesystemBoundaryTests, in the strong-runtime gate, where the
        // runtime that can do it exists. A green run here is not evidence for that and does not claim to be.
        String sentinel = "KAAS_TENANT_SOURCE_SECRET_SENTINEL_" + UUID.randomUUID();
        String hostile = "#!/bin/sh\ntouch /tmp/kaas-owned\n$(touch /tmp/kaas-owned)\n"
                + "`touch /tmp/kaas-owned`\nRuntime.getRuntime().exec(\"id\")\n"
                + "* def x = read('classpath:evil.js')\n" + sentinel + "\n";

        try {
            Tenant tenant = tenantWithSources(List.of(hostile));
            UUID runId = claimedRunFor(tenant);
            UUID attemptId = attemptId(runId);

            ExecutionLoop.ExecutionReport report = sourceLoop().execute(runId, attemptId, 1);

            assertThat(report.status())
                    .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                    .isEqualTo("INFRASTRUCTURE_FAILED");
            assertThat(report.detail())
                    .as("the refusal names the runtime, because there is nothing wrong with the bundle")
                    .contains("RUNTIME_CANNOT_ENFORCE");

            // NOTHING RAN AND NOTHING LEAKED. The source said to create this file in several syntaxes.
            assertThat(Files.exists(Path.of("/tmp/kaas-owned")))
                    .as("tenant source must not have executed")
                    .isFalse();
            assertThat(report.detail()).doesNotContain(sentinel);
            assertThat(managedContainers()).as("a refusal builds no container").isEmpty();

            // NO RESULT DOCUMENT AT ALL, which is the right shape for this refusal: the run never reached
            // a workload, so there is no test outcome to record and none is invented. The run is terminal
            // through the infrastructure-failure path instead.
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                    .as("a run refused before any sandbox existed has no result to report")
                    .isZero();
            // The run leaves CLAIMED. It settles as STOPPING here rather than COMPLETED because that is what
            // the infrastructure-failure path has always done -- the reconciler finishes it -- and this slice
            // did not change that. What matters for a refused delivery is that the run does not sit CLAIMED
            // with admission capacity held and nothing running behind it.
            assertThat(jdbc.queryForObject(
                            "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                    .as("a refused delivery must not leave the run claimed")
                    .isEqualTo("STOPPING");
        } finally {
            Files.deleteIfExists(Path.of("/tmp/kaas-owned"));
        }
    }

    @Test
    @DisplayName("sealed source cannot be substituted, so the delivered bundle is the authorized one")
    void sealedSourceCannotBeSubstituted() throws Exception {
        // THE OTHER HALF OF BUNDLE INTEGRITY, and it is a database property rather than a runner one.
        //
        // The runner refuses a bundle whose bytes do not match the digests its command authorized -- that is
        // asserted directly, with a hand-built bundle, in SourceBundleTests. What cannot be asserted that way
        // is that the substitution has no route in the first place, and this is it: a sealed FeatureRevision
        // is immutable, enforced by a trigger, so there is no supported operation that changes the bytes a
        // command already authorized.
        //
        // Attempted through the database, not the API, because the API has no such operation at all. If this
        // UPDATE ever succeeds, the runner's digest check becomes the only thing between a rewritten feature
        // and an execution, and this test is where that change gets noticed.
        Tenant tenant = tenantWithSources(List.of("Feature: original\n"));
        UUID runId = claimedRunFor(tenant);
        assertThat(runId).isNotNull();

        assertThatThrownBy(() -> jdbc.update(
                        "update feature_revisions set source = ? where source like ?",
                        "Feature: substituted\n", "%original%"))
                .as("a sealed feature revision must not be rewritable")
                .hasMessageContaining("immutable");

        // And the stored bytes are unchanged.
        assertThat(jdbc.queryForObject(
                        "select count(*) from feature_revisions where source like ?", Integer.class, "%original%"))
                .isPositive();
    }

    /** A loop that stages tenant source under the given root and runs the platform's source verifier. */
    private ExecutionLoop sourceLoop() throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + port),
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(probeImage());
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper),
                new DockerSandboxLauncher(docker(), profile, "pipeline"),
                mapper,
                Clock.systemUTC(),
                com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_SOURCE_VERIFY,
                null,
                true);
    }

    /** Every staged source directory still on disk under the given root. */
    private static List<Path> stagingDirectories(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("kaas-source-")).toList();
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // Best effort in a test's own cleanup.
                }
            });
        }
    }

    private ExecutionLoop loopUnderRuntime(ExecutionRuntimeType runtime) throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + port),
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(probeImage(), runtime);
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper),
                new DockerSandboxLauncher(docker(), profile, "pipeline"),
                mapper,
                Clock.systemUTC(),
                com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_PASS);
    }

    private ExecutionLoop loop() throws Exception {
        return loop(com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_PASS);
    }

    private ExecutionLoop loop(com.kaas.runner.sandbox.SyntheticProbe workload) throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + port),
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(probeImage());
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper),
                new DockerSandboxLauncher(docker(), profile, "pipeline"),
                mapper,
                Clock.systemUTC(),
                workload);
    }

    /**
     * One Docker client and one probe image for the whole class.
     *
     * <p>Built once because building it per test would rebuild the image four times for no benefit, and
     * because the image reference has to be identical across tests — a launcher pointed at a different image
     * than the one the attestation describes is a different security posture, not a faster test.
     */
    private static synchronized DockerClient docker() {
        if (DOCKER == null) {
            // Testcontainers' own client rather than one built here. It already resolved a working Docker
            // endpoint to start the database, and it handles the cases hand-rolled resolution gets wrong —
            // Docker contexts, rootless sockets, Colima, a DOCKER_HOST that is unset rather than absent.
            // Building a second client here produced `unix://localhost:2375` and refused every connection.
            DOCKER = DockerClientFactory.instance().client();
        }
        return DOCKER;
    }

    private static synchronized String probeImage() {
        if (PROBE_IMAGE == null) {
            // Located from the repository root, because this module's working directory is its own and the
            // build context belongs to another module.
            PROBE_IMAGE = ProbeImage.build(
                    docker(),
                    java.nio.file.Path.of("..", "..", "services", "runner", "src", "main", "docker", "probe"));
        }
        return PROBE_IMAGE;
    }

    // ---------------------------------------------------------------------------------------------------
    // Enforceable egress
    // ---------------------------------------------------------------------------------------------------

    /** The destination the allowlist below permits, and the only one it permits. */
    private static final String ALLOWED_DESTINATION = "api.allowed.example";

    @Test
    @DisplayName("an allowlist run issues an egress capability the real proxy code can use against the real "
            + "control plane, and fencing the assignment takes it away")
    void anAllowlistRunAuthorizesTheProxyUntilTheAssignmentIsFenced() throws Exception {
        UUID runId = claimedRunUnder(ALLOWED_DESTINATION, 443, "HTTPS");

        // Re-authorize to obtain a fresh delivery. The token exists only in this response — it was never
        // written to a database, a log, a metric, a label, or the persisted command.
        HttpResponse<String> delivered = authorize(runId, attemptId(runId), 1);
        assertThat(delivered.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(delivered.body());
        assertThat(body.get("command").get("networkPolicy").get("type").asText()).isEqualTo("ALLOWLIST");
        String capability = body.get("egressCapabilityToken").asText();
        assertThat(capability).startsWith("kaas_egr_");

        // THE REAL PROXY-SIDE CLIENT against the REAL control plane. Not a stub of the exchange on either
        // side: the runner's Docker suites prove the proxy honours a decision, and this proves the decision
        // the control plane actually produces is one that client actually understands. Two stubs agreeing
        // would be two implementations of the same misunderstanding.
        // The endpoint answers 200 whatever the verdict, so this asserts it is reachable and working rather
        // than that the answer was yes. It earns its place: the service was briefly annotated readOnly, which
        // PostgreSQL refuses for the row lock it takes, and every request returned 500. The proxy read that as
        // AUTHORIZATION_UNAVAILABLE and refused — fail-closed behaved perfectly while nothing worked at all,
        // and a test that only looked at the decision would have called that a policy denial.
        HttpResponse<String> raw = postInternal(
                "/internal/v1/egress/authorizations",
                "kaas.egress-proxy",
                "{\"capabilityToken\":\"" + capability + "\",\"host\":\"" + ALLOWED_DESTINATION
                        + "\",\"port\":443,\"scheme\":\"HTTPS\"}");
        assertThat(raw.statusCode()).as("%s", raw.body()).isEqualTo(200);
        assertThat(raw.body()).contains("AUTHORIZED");

        EgressAuthorizer authorizer = new ControlPlaneAuthorizer(
                URI.create("http://localhost:" + port),
                "Bearer " + token("kaas.egress-proxy", null),
                Duration.ofSeconds(10));

        var permitted = authorizer.authorize(
                capability, new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTPS));
        // Asserted on the reason as well as the verdict, so a failure says WHY rather than only "false".
        assertThat(permitted.reason()).as("the destination the tenant's policy names").isNull();
        assertThat(permitted.authorized()).isTrue();

        // Everything else is refused, and the refusals are specific.
        assertThat(authorizer.authorize(
                        capability, new CanonicalDestination("other.example.com", 443, Scheme.HTTPS)).reason())
                .isEqualTo(DenialReason.DESTINATION_NOT_ALLOWED);
        assertThat(authorizer.authorize(
                        capability, new CanonicalDestination(ALLOWED_DESTINATION, 8443, Scheme.HTTPS)).reason())
                .as("a port the policy did not name")
                .isEqualTo(DenialReason.DESTINATION_NOT_ALLOWED);
        assertThat(authorizer.authorize(
                        capability, new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTP)).reason())
                .as("the scheme is part of the destination, so plain HTTP to a tunnelled port is not permitted")
                .isEqualTo(DenialReason.DESTINATION_NOT_ALLOWED);
        assertThat(authorizer.authorize(
                        "kaas_egr_" + "z".repeat(43),
                        new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTPS)).reason())
                .as("a well-shaped credential that identifies nothing")
                .isEqualTo(DenialReason.CAPABILITY_INVALID);

        // Now fence the assignment, exactly as a cancellation or a lost lease does. The capability's own TTL
        // has not moved — expiry bounds the damage from a leak, and revalidation is what makes fencing work.
        ageLease(runId);

        assertThat(authorizer.authorize(
                        capability, new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTPS))
                        .authorized())
                .as("an unexpired capability whose assignment is gone must stop working")
                .isFalse();
    }

    @Test
    @DisplayName("a DENY_ALL run is issued no egress capability at all")
    void aDenyAllRunCarriesNoEgressCapability() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));

        JsonNode body = mapper.readTree(authorize(runId, attemptId(runId), 1).body());

        assertThat(body.get("command").get("networkPolicy").get("type").asText()).isEqualTo("DENY_ALL");
        // A sandbox with no network has nothing to present a credential to. Issuing one anyway would put a
        // live bearer token into an environment for no reason, and a capability that exists can leak.
        assertThat(body.has("egressCapabilityToken") && !body.get("egressCapabilityToken").isNull())
                .as("no capability is minted for a policy that needs none")
                .isFalse();
    }

    @Test
    @DisplayName("the egress capability never reaches the command, the database, or a log")
    void theEgressCapabilityLivesOnlyInItsResponse() throws Exception {
        UUID runId = claimedRunUnder(ALLOWED_DESTINATION, 443, "HTTPS");

        JsonNode body = mapper.readTree(authorize(runId, attemptId(runId), 1).body());
        String capability = body.get("egressCapabilityToken").asText();

        // Not in the command document, which is immutable and digested: a field the digest cannot cover must
        // not be emitted, and this rotates on every delivery so no digest could cover it.
        assertThat(body.get("command").toString()).doesNotContain(capability);
        // Not in the persisted command either.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_commands where document::text like ?",
                        Integer.class,
                        "%" + capability + "%"))
                .isZero();
        // Only the hash is stored, so a database backup grants nobody anything.
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(capability.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capabilities"
                                + " where capability_type = 'EGRESS' and token_sha256 = ?",
                        Integer.class,
                        hash))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("rotation leaves exactly one live egress capability, whatever a worker retries")
    void rotationLeavesOneLiveEgressCapability() throws Exception {
        UUID runId = claimedRunUnder(ALLOWED_DESTINATION, 443, "HTTPS");

        String first = mapper.readTree(authorize(runId, attemptId(runId), 1).body())
                .get("egressCapabilityToken").asText();
        String second = mapper.readTree(authorize(runId, attemptId(runId), 1).body())
                .get("egressCapabilityToken").asText();
        assertThat(second).isNotEqualTo(first);

        EgressAuthorizer authorizer = new ControlPlaneAuthorizer(
                URI.create("http://localhost:" + port),
                "Bearer " + token("kaas.egress-proxy", null),
                Duration.ofSeconds(10));
        var destination = new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTPS);

        // Ten retries must leave one working token, not ten. The previous one is revoked in the same
        // transaction that mints the replacement.
        assertThat(authorizer.authorize(second, destination).authorized()).isTrue();
        assertThat(authorizer.authorize(first, destination).authorized())
                .as("a rotated-out capability is revoked, not merely superseded")
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capabilities c"
                                + " join execution_authorizations a on a.authorization_id = c.authorization_id"
                                + " where a.run_id = ? and c.capability_type = 'EGRESS' and c.revoked_at is null",
                        Integer.class,
                        runId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an allowlist run executes end to end through a real proxy and completes on its own evidence")
    void anAllowlistRunCompletesThroughTheProxy() throws Exception {
        // The whole thing, with nothing simulated: this control plane, this database, the repository's proxy
        // image, a real resolver, a real target, and the production DockerEgressExecutions creating the
        // network and starting the proxy. What no other test in this repository can establish is that the
        // decision the control plane actually produces is one the real proxy acts on during a real execution,
        // and that the run then completes through the ordinary lifecycle.
        try (EgressPipelineTopology topology =
                new EgressPipelineTopology(docker(), probeImage())) {
            UUID runId = claimedRunUnder(
                    EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
            UUID attemptId = attemptId(runId);

            ExecutionLoop.ExecutionReport report = allowlistLoop(topology).execute(runId, attemptId, 1);

            assertThat(report.status())
                    .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                    .isEqualTo("COMPLETED");
            assertThat(report.detail()).isEqualTo("PASSED");

            // RESULT SEMANTICS. A successful allowlist execution is an ordinary successful execution: the
            // infrastructure worked and the workload passed. Nothing about enforceable egress makes an
            // allowlist run a special kind of result.
            Map<String, Object> run = jdbc.queryForMap(
                    "select lifecycle_state, test_outcome, infrastructure_outcome, termination_reason"
                            + " from test_runs where run_id = ?",
                    runId);
            assertThat(run.get("lifecycle_state")).isEqualTo("COMPLETED");
            assertThat(run.get("test_outcome")).isEqualTo("PASSED");
            assertThat(run.get("infrastructure_outcome")).isEqualTo("SUCCEEDED");
            assertThat(run.get("termination_reason")).isEqualTo("EXECUTION_COMPLETED");

            // The command the runner acted on bound the allowlist, and the runner accepted it — which it
            // only does when its own host demonstrated it can enforce one.
            assertThat(jdbc.queryForObject(
                            "select c.document ->> 'networkPolicy' is not null from execution_commands c"
                                    + " join execution_authorizations a"
                                    + "   on a.authorization_id = c.authorization_id where a.run_id = ?",
                            Boolean.class,
                            runId))
                    .isTrue();

            // THE PROXY WAS REALLY IN THE PATH. The resolver was queried for the destination, and it was
            // queried by the proxy — the sandbox is on an internal network with no resolver reachable at all,
            // so no other party in this topology could have asked.
            assertThat(topology.dns.queries())
                    .as("the security-relevant resolution happens in the proxy")
                    .anySatisfy(query -> assertThat(query).startsWith(EgressPipelineTopology.ALLOWED_HOST));

            // AND THE EVIDENCE SAYS SO. The document is the ordinary synthetic result — it does not claim a
            // tenant feature ran, because none did.
            JsonNode document = mapper.readTree(jdbc.queryForObject(
                    "select document::text from execution_results where run_id = ?", String.class, runId));
            assertThat(document.get("producer").asString()).isEqualTo("kaas-runner-synthetic");
            assertThat(document.get("infrastructureOutcome").asString()).isEqualTo("SUCCEEDED");
            assertThat(document.get("features")).isEmpty();
            assertThat(document.toString()).doesNotContain("KARATE");

            // NO CREDENTIAL SURVIVED THE EXECUTION. The egress capability was delivered into a container's
            // environment, which is the one place it has to be; it must be nowhere that outlives the run.
            List<String> live = jdbc.queryForList(
                    "select token_sha256 from execution_capabilities c"
                            + " join execution_authorizations a on a.authorization_id = c.authorization_id"
                            + " where a.run_id = ? and c.capability_type = 'EGRESS' and c.revoked_at is null",
                    String.class,
                    runId);
            assertThat(live)
                    .as("one live egress capability, and only its hash — a database backup grants nobody "
                            + "anything")
                    .singleElement()
                    .asString()
                    .matches("^[a-f0-9]{64}$");

            // NOTHING OUTLIVED IT. An orphaned proxy is a running egress gateway with no execution behind it,
            // still holding a service credential and still attached to the target network.
            //
            // Scoped to THIS runner's generation rather than asked globally. A global assertion on a shared
            // daemon fails for whatever else happens to be on the machine, which turns somebody else's
            // leftover into a product defect — it did exactly that once, and the leftover was a network a
            // mutation run had deliberately leaked.
            assertThat(managedByThisRunner("kaas.resource=egress-proxy"))
                    .as("no proxy survives its execution")
                    .isEmpty();
            assertThat(networksOfThisRunner())
                    .as("no execution network survives its execution")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("an https destination is tunnelled, and the proxy never sees inside it")
    void anHttpsAllowlistRunIsTunnelled() throws Exception {
        try (EgressPipelineTopology topology =
                new EgressPipelineTopology(docker(), probeImage())) {
            // The same mechanism with the scheme changed, which is the half the HTTP run cannot reach: a
            // CONNECT rather than a forward-proxied request. It is worth its own run because the workload
            // takes a different code path for it and the proxy takes a different one too — and an untested
            // branch in the trusted probe is a branch nobody has ever seen work.
            UUID runId = claimedRunUnder(
                    EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_HOLD_PORT, "HTTPS");
            UUID attemptId = attemptId(runId);

            ExecutionLoop.ExecutionReport report = allowlistLoop(topology).execute(runId, attemptId, 1);

            assertThat(report.status())
                    .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                    .isEqualTo("COMPLETED");
            assertThat(report.detail()).isEqualTo("PASSED");
            assertThat(jdbc.queryForObject(
                            "select test_outcome from test_runs where run_id = ?", String.class, runId))
                    .isEqualTo("PASSED");

            // The proxy resolved the destination itself, as it does for a forward-proxied request. TLS being
            // end to end does not move name resolution or address classification to the client.
            assertThat(topology.dns.queries())
                    .anySatisfy(query -> assertThat(query).startsWith(EgressPipelineTopology.ALLOWED_HOST));
        }
    }

    @Test
    @DisplayName("an allowlist run whose proxy cannot start fails as infrastructure and starts no sandbox")
    void anAllowlistRunWhoseProxyCannotStartIsAnInfrastructureFailure() throws Exception {
        try (EgressPipelineTopology topology =
                new EgressPipelineTopology(docker(), probeImage())) {
            UUID runId = claimedRunUnder(
                    EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
            UUID attemptId = attemptId(runId);

            // A "proxy" image that is not the proxy: digest-pinned, so every control the profile enforces is
            // satisfied, and it exits within a second — which exercises the readiness path rather than the
            // create path. There is no degraded mode below this: an allowlist execution without a proxy is an
            // execution with no enforcement.
            var deployment = topology.deployment(port, "Bearer " + token("kaas.egress-proxy", null));
            var broken = new com.kaas.runner.sandbox.EgressDeployment(
                    deployment.probeImageReference(),
                    deployment.probeImageReference(),
                    deployment.controlPlaneBaseUri(),
                    deployment.serviceAuthorization(),
                    deployment.dnsServer(),
                    deployment.egressNetworkIds(),
                    deployment.hostAliases(),
                    deployment.dnsTimeout(),
                    deployment.authorizationTimeout(),
                    deployment.revalidationInterval(),
                    deployment.connectTimeout(),
                    deployment.sandboxRuntime());

            ExecutionLoop.ExecutionReport report =
                    allowlistLoop(topology, broken).execute(runId, attemptId, 1);

            assertThat(report.status()).isEqualTo("INFRASTRUCTURE_FAILED");
            // The DETAIL as well as the status. The status alone is set whether or not the control plane
            // accepted the report, so asserting it by itself lets a refused report pass as success.
            assertThat(report.detail())
                    .as("the control plane must have accepted the failure report")
                    .doesNotContain("report refused");
            // The category, which is what an operator acts on. A container that started and then exited is a
            // start failure; one still running but silent past its bound is EGRESS_PROXY_NOT_READY, and the
            // two want different investigations.
            assertThat(report.detail()).contains("EGRESS_PROXY_START_FAILED");

            // TRUTHFULLY CLASSIFIED. The platform failed, so the platform says so — reporting this as a test
            // outcome would send a user looking at their own code for a fault that is entirely ours. The run
            // is STOPPING with the reason named, and the attempt's disposition is FAILED; the terminal
            // settle that turns those into a run-level outcome is the reconciler's job, not the worker's.
            Map<String, Object> run = jdbc.queryForMap(
                    "select lifecycle_state, stop_reason, test_outcome from test_runs where run_id = ?", runId);
            assertThat(run.get("lifecycle_state")).isEqualTo("STOPPING");
            assertThat(run.get("stop_reason")).isEqualTo("INFRASTRUCTURE_FAILURE");
            // NO TEST OUTCOME WAS INVENTED. Nothing ran, so there is nothing to report about a test — which
            // is the difference between "not available" and "failed".
            assertThat(run.get("test_outcome")).isNull();
            assertThat(jdbc.queryForObject(
                            "select infrastructure_disposition from execution_attempts where run_id = ?",
                            String.class, runId))
                    .isEqualTo("FAILED");

            // AND NO SANDBOX RAN. Not "ran and was cleaned up": never created. There is no submitted result
            // because there was never an execution to produce one.
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                    .isZero();
            assertThat(managedByThisRunner("kaas.resource=sandbox")).isEmpty();
            assertThat(networksOfThisRunner())
                    .as("the network goes too, or the correlation is unusable for a retry")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a proxy that dies mid-run is an infrastructure failure, never a failed test")
    void anAllowlistRunWhoseProxyDiesIsAnInfrastructureFailure() throws Exception {
        try (EgressPipelineTopology topology =
                new EgressPipelineTopology(docker(), probeImage())) {
            UUID runId = claimedRunUnder(
                    EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
            UUID attemptId = attemptId(runId);

            // The proxy is stopped the instant it has come up, so the sandbox runs with its only egress peer
            // already gone. Stopped rather than removed: its network endpoints survive, so the only thing
            // that changed is that nothing is listening — removing it would also tear down the endpoints and
            // the sandbox would lose connectivity for a different reason than the one under test.
            ExecutionLoop.ExecutionReport report =
                    allowlistLoop(topology, this::stopProxyImmediately).execute(runId, attemptId, 1);

            // THE POINT. The workload could not reach anything, so it reported FAILED — and a platform that
            // read that as the answer would tell a tenant their test failed when the platform's own gateway
            // had died underneath it. The proxy's health is checked, so the run is classified as ours.
            assertThat(report.status())
                    .as("report was %s at %s: %s", report.status(), report.phase(), report.detail())
                    .isEqualTo("INFRASTRUCTURE_FAILED");
            assertThat(report.detail()).contains("EGRESS_PROXY_DIED");
            assertThat(report.detail()).doesNotContain("report refused");

            Map<String, Object> run = jdbc.queryForMap(
                    "select lifecycle_state, stop_reason, test_outcome from test_runs where run_id = ?", runId);
            assertThat(run.get("lifecycle_state")).isEqualTo("STOPPING");
            assertThat(run.get("stop_reason")).isEqualTo("INFRASTRUCTURE_FAILURE");
            assertThat(run.get("test_outcome"))
                    .as("no test outcome is invented from evidence gathered while the gateway was going away")
                    .isNull();
            assertThat(jdbc.queryForObject(
                            "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                    .isZero();
        }
    }

    @Test
    @DisplayName("a cancelled run's egress stops for being cancelled, not merely for being fenced")
    void aStoppingRunAuthorizesNoEgress() throws Exception {
        Tenant tenant = tenant(List.of("@smoke"));
        UUID runId = claimedRunUnder(tenant, ALLOWED_DESTINATION, 443, "HTTPS");
        String capability = mapper.readTree(authorize(runId, attemptId(runId), 1).body())
                .get("egressCapabilityToken").asString();
        EgressAuthorizer authorizer = new ControlPlaneAuthorizer(
                URI.create("http://localhost:" + port),
                "Bearer " + token("kaas.egress-proxy", null),
                Duration.ofSeconds(10));
        var destination = new CanonicalDestination(ALLOWED_DESTINATION, 443, Scheme.HTTPS);
        // Anti-vacuity: it works first, so what is asserted below is the cancellation taking it away rather
        // than a capability that never worked.
        assertThat(authorizer.authorize(capability, destination).authorized()).isTrue();

        // Cancelled through the real tenant API rather than by writing STOPPING directly: a raw UPDATE is
        // refused by the scheduling-bundle guard, which requires a stopping run's assignment to be fenced.
        assertThat(post("/api/v1/runs/" + runId + "/cancellations", tenant.bearer(),
                        json(Map.of("reason", "USER_REQUESTED"))).statusCode())
                .isBetween(200, 299);

        var decision = authorizer.authorize(capability, destination);
        assertThat(decision.authorized()).isFalse();
        // THE REASON, not merely the refusal. Cancellation both moves the run to STOPPING and fences its
        // assignment, so two independent checks would refuse this — and asserting only `false` would leave
        // the lifecycle check jointly covered with the fencing one and provable by neither. The lifecycle
        // check runs first and answers RUN_NOT_EXECUTING, so this pins that specific check.
        assertThat(decision.reason()).isEqualTo(DenialReason.RUN_NOT_EXECUTING);
    }

    /** The runner, wired to enforce an allowlist against this topology. */
    private ExecutionLoop allowlistLoop(EgressPipelineTopology topology) throws Exception {
        return allowlistLoop(
                topology, topology.deployment(port, "Bearer " + token("kaas.egress-proxy", null)));
    }

    /**
     * The same runner, with something done to the egress the moment it is up.
     *
     * <p>A decorator rather than a substitute: the real {@code DockerEgressExecutions} still creates the
     * network and starts the proxy, and the hook then interferes with the result. Replacing the factory
     * outright would prove the loop handles a stub, which is a different and much smaller claim.
     */
    private ExecutionLoop allowlistLoop(
            EgressPipelineTopology topology, Runnable afterStart) throws Exception {
        var deployment = topology.deployment(port, "Bearer " + token("kaas.egress-proxy", null));
        var real = new com.kaas.runner.sandbox.DockerEgressExecutions(docker(), deployment, "pipeline");
        var decorated = new com.kaas.runner.sandbox.EgressExecutions() {
            @Override
            public com.kaas.runner.sandbox.EgressExecution start(
                    UUID correlationId, com.kaas.runner.sandbox.EgressPlan plan) {
                var execution = real.start(correlationId, plan);
                afterStart.run();
                return execution;
            }

            @Override
            public java.time.Duration maximumRevocationLatency() {
                return real.maximumRevocationLatency();
            }
        };
        return allowlistLoop(deployment, decorated);
    }

    /**
     * Containers this runner generation created, carrying the given label.
     *
     * <p>Scoped by generation, always. These suites share a daemon with whatever else is on the machine, and
     * a global "nothing is left" assertion answers a question this test cannot own. The job-level check in CI
     * is the right place for the global form, after everything has finished.
     */
    private List<com.github.dockerjava.api.model.Container> managedByThisRunner(String label) {
        String[] parts = label.split("=", 2);
        return docker().listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(parts[0], parts[1], "kaas.launcher.generation", "pipeline"))
                .exec();
    }

    private List<com.github.dockerjava.api.model.Network> networksOfThisRunner() {
        return docker().listNetworksCmd()
                .withFilter("label", List.of("kaas.launcher.generation=pipeline"))
                .exec();
    }

    /** Stops the running proxy without removing it, so its endpoints survive and nothing is listening. */
    private void stopProxyImmediately() {
        var proxies = managedByThisRunner("kaas.resource=egress-proxy");
        assertThat(proxies).as("the execution must have started a proxy to stop").isNotEmpty();
        proxies.forEach(proxy -> docker().stopContainerCmd(proxy.getId()).withTimeout(2).exec());
    }

    private ExecutionLoop allowlistLoop(
            EgressPipelineTopology topology, com.kaas.runner.sandbox.EgressDeployment deployment)
            throws Exception {
        return allowlistLoop(
                deployment,
                new com.kaas.runner.sandbox.DockerEgressExecutions(docker(), deployment, "pipeline"));
    }

    private ExecutionLoop allowlistLoop(
            com.kaas.runner.sandbox.EgressDeployment deployment,
            com.kaas.runner.sandbox.EgressExecutions executions)
            throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + port),
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(probeImage());
        return new ExecutionLoop(
                client,
                // ALLOWLIST is accepted here because this host established it can enforce one. The default
                // constructor still refuses it, so a runner that forgot to establish the capability fails
                // closed rather than executing without a proxy.
                new CommandValidator(mapper, java.util.Set.of("DENY_ALL", "ALLOWLIST")),
                new DockerSandboxLauncher(docker(), profile, "pipeline"),
                mapper,
                Clock.systemUTC(),
                com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_PASS,
                executions);
    }

    /**
     * A tenant-owned allowlist with exactly one destination, and the project pointed at it.
     *
     * <p>Written through SQL because no product surface authors a policy yet — policies are platform-owned,
     * and this stands in for the operator action that creates one. What is NOT stood in for is the pinning:
     * the run below goes through the ordinary creation path, so the snapshot copies the project's selection
     * the way a real run does.
     */
    private UUID allowlistFor(Tenant tenant, String host, int port, String scheme) {
        UUID policyId = UUID.randomUUID();
        // Scoped to the tenant that will use it, named explicitly. An earlier version of this helper picked
        // "the most recently created project", which — because it runs BEFORE the run is created — silently
        // selected the PREVIOUS test's project and configured egress for a tenant that was not under test.
        jdbc.update(
                "insert into network_policy_revisions (policy_revision_id, policy_type, policy_version,"
                        + " canonical_digest, created_by, created_at, organization_id, project_id)"
                        + " values (?, 'ALLOWLIST', 1, ?, 'kaas.platform', now(), ?, ?)",
                policyId,
                NetworkPolicyRevision.digestOf(
                        NetworkPolicyType.ALLOWLIST,
                        1,
                        List.of(new EgressDestination(host, port, EgressScheme.valueOf(scheme)))),
                tenant.organizationId(),
                tenant.projectId());
        jdbc.update(
                "insert into network_policy_destinations (policy_revision_id, host, port, scheme)"
                        + " values (?, ?, ?, ?)",
                policyId, host, port, scheme);
        jdbc.update(
                "update projects set network_policy_revision_id = ? where project_id = ?",
                policyId, tenant.projectId());
        return policyId;
    }

    /** A claimed run whose snapshot pinned an allowlist, created through the ordinary path. */
    private UUID claimedRunUnder(String host, int port, String scheme) throws Exception {
        return claimedRunUnder(tenant(List.of("@smoke")), host, port, scheme);
    }

    private UUID claimedRunUnder(Tenant tenant, String host, int port, String scheme) throws Exception {
        // Tenant first, then the policy for THAT tenant, then the run. The order matters: the snapshot copies
        // the project's selection at creation, so a policy configured afterwards would not reach the run.
        UUID policyId = allowlistFor(tenant, host, port, scheme);
        UUID runId = claimedRunFor(tenant);
        // Asserted rather than assumed. If the snapshot did not copy the project's selection, every
        // assertion below would be about a DENY_ALL run and would pass for the wrong reason.
        assertThat(jdbc.queryForObject(
                        "select network_policy_revision_id from run_snapshots where run_id = ?",
                        UUID.class,
                        runId))
                .as("the snapshot pins the policy the project selected, at creation")
                .isEqualTo(policyId);
        return runId;
    }

    private UUID claimedRun(List<String> tags) throws Exception {
        return claimedRunFor(tenant(tags));
    }

    @Test
    @DisplayName("a sandbox that produces no usable evidence is an infrastructure failure, not a failed test")
    void absentEvidenceIsNotATestFailure() throws Exception {
        UUID runId = claimedRun(List.of("@smoke"));

        // INSPECT is a real, trusted probe that runs successfully and emits no workload verdict at all. It
        // stands in for every way a sandbox can exit cleanly while telling us nothing: an OOM kill, a truncated
        // stream, a non-zero exit, a container that ran something other than what we asked for.
        //
        // Before this, the runner read a missing workload_outcome as `!"PASSED".equals(null)` and submitted a
        // well-formed document claiming the infrastructure SUCCEEDED and the tenant's test FAILED. Every
        // constraint in the system accepts that document because it is internally consistent — it is simply
        // false, and false in the direction that blames a tenant for the platform.
        ExecutionLoop.ExecutionReport report =
                loop(com.kaas.runner.sandbox.SyntheticProbe.INSPECT).execute(runId, attemptId(runId), 1);

        assertThat(report.status()).isEqualTo("INFRASTRUCTURE_FAILED");
        // The DETAIL as well as the status. The status alone is set whether or not the control plane accepted
        // the report, so asserting it by itself let a refused report pass as success — which is the very
        // silent-discard this endpoint exists to end.
        assertThat(report.detail())
                .as("the control plane must have accepted the failure report")
                .doesNotContain("report refused");

        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, stop_reason, test_outcome from test_runs where run_id = ?", runId);
        // The run STOPPED and was told why, rather than sitting in its phase until a deadline reclaimed it and
        // recorded a timeout for a failure the platform observed within seconds.
        assertThat(run.get("lifecycle_state")).isEqualTo("STOPPING");
        assertThat(run.get("stop_reason")).isEqualTo("INFRASTRUCTURE_FAILURE");
        // And no test outcome was invented. Nothing ran to completion, so there is nothing to report.
        assertThat(run.get("test_outcome")).isNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select infrastructure_disposition from execution_attempts where run_id = ?",
                        String.class, runId))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a workload that announces itself and reports no verdict is an infrastructure failure")
    void aSilentWorkloadIsAnInfrastructureFailure() throws Exception {
        // Correct identity, no outcome — so ONLY the missing-verdict check can refuse this. Paired with the
        // imposter case below, that makes each check provable on its own; with just one of them, deleting
        // either left the other refusing and neither was actually tested.
        UUID runId = claimedRun(List.of("@smoke"));
        var report = loop(com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_SILENT)
                .execute(runId, attemptId(runId), 1);

        assertThat(report.status()).isEqualTo("INFRASTRUCTURE_FAILED");
        assertThat(report.detail()).contains("reported no outcome").doesNotContain("report refused");
        assertThat(jdbc.queryForObject(
                        "select stop_reason from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("INFRASTRUCTURE_FAILURE");
    }

    @Test
    @DisplayName("a confident verdict under the wrong identity is not believed")
    void anImposterWorkloadIsNotBelieved() throws Exception {
        // A container that exits cleanly and reports PASSED, but is not the workload we asked for. Without the
        // identity check anything that emitted the right key would be accepted as a tenant's passing test.
        UUID runId = claimedRun(List.of("@smoke"));
        var report = loop(com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_IMPOSTER)
                .execute(runId, attemptId(runId), 1);

        assertThat(report.status()).isEqualTo("INFRASTRUCTURE_FAILED");
        assertThat(report.detail()).contains("did not identify itself").doesNotContain("report refused");
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_results where run_id = ?", Integer.class, runId))
                .as("an unidentified workload's verdict must never become evidence")
                .isZero();
    }

    @Test
    @DisplayName("an unacquired assignment is held by nobody, so no worker can drive it")
    void anUnacquiredAssignmentCannotBeDriven() throws Exception {
        // The property assignment acquisition exists for, and the one no other test reaches — every other test
        // acquires as part of its setup, so removing the requirement changed nothing anywhere.
        //
        // Before acquisition, assigned_worker_id held the dispatch consumer's own configured constant: one
        // value for every run in the deployment. Any worker in the fleet satisfied every ownership check on
        // every run, two workers could hold one assignment simultaneously, and the assignment epoch — whose
        // entire job is to distinguish one holder from another — fenced nothing.
        UUID runId = claimedWithoutAcquiring();
        UUID attemptId = attemptId(runId);

        assertThat(jdbc.queryForObject(
                        "select acquired_at from execution_attempts where attempt_id = ?",
                        java.sql.Timestamp.class, attemptId))
                .isNull();

        HttpResponse<String> refused = advance(runId, attemptId, 1, "PROVISIONING", WORKER);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(refused.body()).get("code").asString()).isEqualTo("ASSIGNMENT_STALE");
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("CLAIMED");

        // And once a worker acquires it, the same request succeeds — so the refusal above is about the missing
        // holder rather than anything else about the request.
        assertThat(authorize(runId, attemptId, 1).statusCode()).isEqualTo(200);
        assertThat(advance(runId, attemptId, 1, "PROVISIONING", WORKER).statusCode()).isEqualTo(200);
    }

    /** A run claimed by the dispatch consumer and not yet bound to any worker. */
    private UUID claimedWithoutAcquiring() throws Exception {
        UUID runId = createRun(tenant(List.of("@smoke")));
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);
        return runId;
    }

    private UUID claimedRunFor(Tenant tenant) throws Exception {
        UUID runId = createRun(tenant);
        scheduler.scheduleDue();
        claims.claim(dispatchFor(runId), WORKER);
        // ACQUIRE the assignment, which is what the execution loop's first authorization does. A claim alone
        // leaves the attempt held by nobody: the worker id written at claim time is the dispatch consumer's own
        // configured constant, so until a real worker binds itself the assignment names no process and no phase
        // advance is permitted.
        assertThat(authorize(runId, attemptId(runId), 1).statusCode())
                .as("acquiring the assignment must succeed before any phase can be driven")
                .isEqualTo(200);
        return runId;
    }

    private UUID attemptId(UUID runId) {
        return jdbc.queryForObject(
                "select current_attempt_id from test_runs where run_id = ?", UUID.class, runId);
    }

    private ExecutionDispatch dispatchFor(UUID runId) throws Exception {
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId)
                        .get("payload"));
        return mapper.readValue(payload, ExecutionDispatch.class);
    }

    private UUID createRun(Tenant tenant) throws Exception {
        return UUID.fromString(mapper.readTree(post(
                                "/api/v1/projects/" + tenant.projectId() + "/runs",
                                tenant.bearer(),
                                json(Map.of(
                                        "featureRevisionIds", List.of(tenant.featureRevisionId()),
                                        "runProfileRevisionId", tenant.profileRevisionId())))
                        .body())
                .get("runId")
                .stringValue());
    }

    /**
     * A tenant whose project holds exactly the given feature sources.
     *
     * <p>Multiple features on purpose: a bundle carrying one entry cannot demonstrate that the delivered set
     * is exactly the authorized set, only that something arrived.
     */
    private Tenant tenantWithSources(List<String> sources) throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("pipeline-test", organizationId);
        String projectId = mapper.readTree(
                        post("/api/v1/projects", bearer, json(Map.of("name", "Pipeline " + UUID.randomUUID())))
                                .body())
                .get("projectId")
                .stringValue();
        String lastRevision = null;
        int index = 0;
        for (String source : sources) {
            lastRevision = mapper.readTree(post(
                                    "/api/v1/projects/" + projectId + "/features",
                                    bearer,
                                    json(Map.of(
                                            "name", "Source feature " + index,
                                            "logicalPath", "features/s-" + index + "-" + UUID.randomUUID() + ".feature",
                                            "source", source)))
                            .body())
                    .at("/initialRevision/revisionId")
                    .stringValue();
            index++;
        }
        String environmentRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/environments",
                                bearer,
                                json(Map.of(
                                        "name", "Pipeline environment",
                                        "variables",
                                                List.of(Map.of(
                                                        "key", "baseUrl", "type", "STRING",
                                                        "value", "https://environment.example")),
                                        "secretBindings", List.of())))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Pipeline profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of()));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = mapper.readTree(
                        post("/api/v1/projects/" + projectId + "/run-profiles", bearer, json(profile)).body())
                .at("/initialRevision/revisionId")
                .stringValue();
        return new Tenant(organizationId, UUID.fromString(projectId), bearer, lastRevision, profileRevision);
    }

    private Tenant tenant(List<String> tags) throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("pipeline-test", organizationId);
        String projectId = mapper.readTree(
                        post("/api/v1/projects", bearer, json(Map.of("name", "Pipeline " + UUID.randomUUID())))
                                .body())
                .get("projectId")
                .stringValue();
        String featureRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/features",
                                bearer,
                                json(Map.of(
                                        "name", "Pipeline feature",
                                        "logicalPath", "features/p-" + UUID.randomUUID() + ".feature",
                                        // Never executed. It is sealed into the snapshot so the run has real
                                        // content to be about, and nothing in the execution path reads it.
                                        "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/environments",
                                bearer,
                                json(Map.of(
                                        "name", "Pipeline environment",
                                        "variables",
                                                List.of(Map.of(
                                                        "key", "baseUrl", "type", "STRING",
                                                        "value", "https://environment.example")),
                                        "secretBindings", List.of())))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Pipeline profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", tags));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = mapper.readTree(
                        post("/api/v1/projects/" + projectId + "/run-profiles", bearer, json(profile)).body())
                .at("/initialRevision/revisionId")
                .stringValue();
        return new Tenant(organizationId, UUID.fromString(projectId), bearer, featureRevision, profileRevision);
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s returned %s: %s", path, response.statusCode(), response.body())
                .isBetween(200, 299);
        return response;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private record Tenant(
            UUID organizationId,
            UUID projectId,
            String bearer,
            String featureRevisionId,
            String profileRevisionId) {}

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

    private static String validAttestation(Instant assessedAt) {
        return validAttestation(assessedAt, Map.of());
    }

    /**
     * An attestation document, optionally claiming this deployment can enforce egress.
     *
     * <p>Absent by default, because that is what an assessment produced by a host that has not demonstrated
     * egress enforcement looks like — and it is the state in which ALLOWLIST must keep being refused. A test
     * that wants the allowlist path has to say so.
     */
    private static String validAttestation(Instant assessedAt, Map<String, String> egress) {
        // Produced by the RUNNER's producer and verified by the CONTROL PLANE's verifier. Two independent
        // implementations of one written contract, meeting for the first time in this module.
        return egress.isEmpty()
                ? ProducedAttestation.mandatoryOnly("kaas.sandbox.v1", assessedAt)
                : ProducedAttestation.withEgress("kaas.sandbox.v1", assessedAt);
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
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
