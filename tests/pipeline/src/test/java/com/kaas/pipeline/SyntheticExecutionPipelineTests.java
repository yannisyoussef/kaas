package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.KaasApiApplication;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.execution.ExecutionLoop;
import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.ProbeImage;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
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
        registry.add("kaas.execution.sandbox-attestation", () -> validAttestation(Instant.now()));
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
        Map<String, String> controls = new TreeMap<>();
        SandboxSecurityAttestation.REQUIRED_MANDATORY_CONTROLS.forEach(control -> controls.put(control, "PASS"));
        String probe = "sha256:" + "a".repeat(64);
        Instant truncated = assessedAt.truncatedTo(ChronoUnit.SECONDS);
        // The attestation carries its own digest, and the control plane recomputes it. Omitting it made every
        // authorization refuse with SECURITY_GATE_UNAVAILABLE — which is the gate working exactly as intended:
        // an attestation it cannot verify is absent evidence, and absent evidence fails closed.
        var draft = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION,
                "kaas.sandbox.v1", probe, "docker", truncated, controls, "");
        StringBuilder json = new StringBuilder("{\"schemaVersion\":\"")
                .append(SandboxSecurityAttestation.SCHEMA_VERSION)
                .append("\",\"securityProfileVersion\":\"kaas.sandbox.v1\",\"probeImageDigest\":\"")
                .append(probe)
                .append("\",\"runtime\":\"docker\",\"assessedAt\":\"")
                .append(truncated)
                .append("\",\"mandatoryControls\":{");
        String body = controls.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return json.append(body).append("},\"digest\":\"").append(draft.expectedDigest()).append("\"}")
                .toString();
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
