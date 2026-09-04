package com.kaas.runner.execution;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import com.kaas.runner.command.CommandRejected;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.command.ValidatedCommand;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One assignment, driven from authorization to a submitted result.
 *
 * <p>The order is the design. Authority is revalidated and the command independently validated <em>before</em>
 * anything is provisioned, because provisioning is the first step that costs something and the first that can
 * leave a container behind. Every phase is reported to the control plane before the work it names begins, so a
 * runner that dies mid-phase leaves a run whose recorded state is at worst one step ahead of reality — which a
 * deadline reconciler can act on, whereas a run recorded as CLAIMED with a live container behind it is an
 * orphan nobody is looking for.
 *
 * <p><strong>A refusal ends the run.</strong> Not a retry: the control plane refused after looking at live
 * state, and asking again gets the same answer while the deadline runs down. The only thing retried anywhere
 * here is transport, inside the client.
 */
public final class ExecutionLoop {

    private final ControlPlaneClient controlPlane;
    private final CommandValidator validator;
    private final SandboxLauncher launcher;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock) {
        this(controlPlane, validator, launcher, mapper, clock, SyntheticProbe.WORKLOAD_PASS);
    }

    /** For the one test that needs the FAILED terminal outcome to be reachable. */
    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock,
            SyntheticProbe workload) {
        this.controlPlane = controlPlane;
        this.validator = validator;
        this.launcher = launcher;
        this.mapper = mapper;
        this.clock = clock;
        this.workload = workload;
    }

    /**
     * Executes one assignment.
     *
     * @return what happened, which is never an exception for an ordinary refusal — a refused run is a normal
     *     outcome of a system that revalidates, not an error condition.
     */
    public ExecutionReport execute(UUID runId, UUID attemptId, int assignmentEpoch)
            throws ControlPlaneUnavailable {

        // 1. AUTHORITY, revalidated now. The claim that won this assignment may be seconds or minutes old, and
        //    the run may have been cancelled or the lease lost since. Nothing is provisioned before this.
        ControlPlaneClient.Response authorization = controlPlane.authorize(
                runId, attemptId, mapper.createObjectNode().put("assignmentEpoch", assignmentEpoch).toString());
        if (!authorization.ok()) {
            return ExecutionReport.refused("AUTHORIZATION", codeOf(authorization.body()));
        }

        // 2. THE COMMAND, validated independently. A command that fails here is never acted on, and the run is
        //    abandoned rather than executed partially — a document the runner does not fully understand is one
        //    whose security-relevant instructions it may be about to skip.
        ValidatedCommand command;
        try {
            JsonNode envelope = mapper.readTree(authorization.body());
            JsonNode document = envelope.get("command");
            if (document == null) {
                return ExecutionReport.rejected("The authorization carried no command.");
            }
            command = validator.validate(document.toString(), clock.instant());
        } catch (CommandRejected rejected) {
            return ExecutionReport.rejected(rejected.getMessage());
        } catch (RuntimeException unreadable) {
            return ExecutionReport.rejected(
                    "The authorization could not be read: " + unreadable.getClass().getName());
        }
        if (!command.runId().equals(runId)
                || !command.attemptId().equals(attemptId)
                || command.assignmentEpoch() != assignmentEpoch) {
            // The command describes an assignment other than the one asked for. Checked even though the control
            // plane built it, because "the server would not do that" is an assumption rather than a check.
            return ExecutionReport.rejected("The command describes a different assignment.");
        }

        // 3. THE LEASE, kept alive for the whole of what follows.
        //
        // Started before provisioning and closed after the final transition, because every step from here on is
        // refused if the lease has lapsed. The interval is a fraction of the shortest lease the platform is
        // likely to grant; this process cannot see the lease duration, so it renews often rather than cleverly.
        try (LeaseHeartbeat ignored = LeaseHeartbeat.start(
                controlPlane, runId, attemptId, assignmentEpoch,
                mapper.createObjectNode().put("assignmentEpoch", assignmentEpoch).toString(),
                HEARTBEAT_INTERVAL)) {
            return execute(runId, attemptId, assignmentEpoch, command);
        }
    }

    /** How often the lease is renewed while a run executes. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private ExecutionReport execute(
            UUID runId, UUID attemptId, int assignmentEpoch, ValidatedCommand command)
            throws ControlPlaneUnavailable {

        // 3. PROVISIONING, announced before the sandbox exists. Announcing afterwards would leave a window in
        //    which a container is running and no deadline covers it.
        Instant provisioningStartedAt = clock.instant();
        String sandboxReference = "sandbox-" + UUID.randomUUID();
        ControlPlaneClient.Response provisioning =
                advance(runId, attemptId, assignmentEpoch, "PROVISIONING", sandboxReference);
        if (!provisioning.ok() && !alreadyInPhase(provisioning, "PROVISIONING")) {
            return ExecutionReport.refused("PROVISIONING", codeOf(provisioning.body()));
        }

        // 4. RUNNING. The control plane stamps the execution start instant here, and that instant — not one
        //    measured on this host — is what the result must agree with.
        ControlPlaneClient.Response running =
                advance(runId, attemptId, assignmentEpoch, "RUNNING", sandboxReference);
        if (!running.ok() && !alreadyInPhase(running, "RUNNING")) {
            return ExecutionReport.refused("RUNNING", codeOf(running.body()));
        }
        // Taken from the control plane's response, never measured here. The submission is checked against the
        // run's own executionStartedAt, so a locally measured instant would differ by this host's clock drift
        // and every result would be refused as a provenance mismatch.
        Instant executionStartedAt = instantOf(running.body(), "executionStartedAt");
        if (executionStartedAt == null) {
            // Fail closed. Guessing an instant here would produce a submission that is refused later for a
            // reason that looks like tampering, which is a far worse thing to debug than stopping now.
            return ExecutionReport.rejected("The control plane did not report when execution started.");
        }
        Duration provisioningElapsed = Duration.between(provisioningStartedAt, clock.instant());

        // 5. THE WORKLOAD. Platform-owned, through the same hardened launcher the security harness uses. No
        //    feature source, no secret, no network.
        // The SANDBOX profile version, not the engine version. They are different things that happen to be
        // strings, and passing the engine version made the launcher refuse with "Unknown security profile
        // version" — which was the launcher correctly refusing to run under a profile it does not recognise.
        SandboxOutcome outcome = launcher.run(new SandboxLaunchRequest(
                workload, command.sandboxProfileVersion(), runId));
        // ABSENT OR INCOMPLETE EVIDENCE IS AN INFRASTRUCTURE FAILURE, NOT A TEST RESULT.
        //
        // Checking only failure() was the bug: DockerSandboxLauncher returns an EMPTY failure whenever the
        // container exited and its log stream drained — including a non-zero exit, an OOM kill, and truncated
        // output. The result builder then read a missing workload_outcome as `!"PASSED".equals(null)` and
        // submitted a perfectly well-formed document saying the infrastructure succeeded and the tenant's test
        // failed. Every constraint in the system accepts that document, because it is internally consistent.
        // It is simply false, and it is false in the direction that blames the tenant for the platform.
        String detail = infrastructureFailureDetail(outcome);
        if (detail != null) {
            // TELL THE CONTROL PLANE. Returning this to our own caller and stopping — which is what this did —
            // left the run in its phase until a deadline reclaimed it, recorded as a timeout. The platform had
            // observed the failure within seconds and then discarded it.
            var body = mapper.createObjectNode();
            body.put("assignmentEpoch", assignmentEpoch);
            // Sanitised to the character set the endpoint accepts. This is our description of our own sandbox,
            // never workload output.
            body.put("detail", detail.replaceAll("[^A-Za-z0-9 .,:;()/_-]", " "));
            ControlPlaneClient.Response reported =
                    controlPlane.reportInfrastructureFailure(runId, attemptId, body.toString());
            if (!reported.ok() && reported.status() != 204) {
                // Say so. Silently discarding the answer to "did the control plane accept my failure report"
                // is the same shape as the bug this endpoint exists to fix — a failure the worker observed and
                // then dropped, leaving the run to be reclaimed by a deadline under a false reason.
                return ExecutionReport.infrastructureFailure(
                        detail + " (report refused: " + codeOf(reported.body()) + " " + reported.status() + ")");
            }
            return ExecutionReport.infrastructureFailure(detail);
        }

        // 6. COLLECTING, then PROCESSING. Two phases rather than one because they fail differently: collection
        //    happens while the sandbox still exists, processing after it is gone.
        ControlPlaneClient.Response collecting =
                advance(runId, attemptId, assignmentEpoch, "COLLECTING_RESULTS", sandboxReference);
        if (!collecting.ok() && !alreadyInPhase(collecting, "COLLECTING_RESULTS")) {
            return ExecutionReport.refused("COLLECTING_RESULTS", codeOf(collecting.body()));
        }
        ControlPlaneClient.Response processing =
                advance(runId, attemptId, assignmentEpoch, "PROCESSING_RESULTS", sandboxReference);
        if (!processing.ok() && !alreadyInPhase(processing, "PROCESSING_RESULTS")) {
            return ExecutionReport.refused("PROCESSING_RESULTS", codeOf(processing.body()));
        }

        // 7. THE RESULT.
        // Never before the start instant. The two come from different clocks — the control plane stamped the
        // start, this host measures the finish — so on a host running even slightly behind, a fast execution
        // finishes "before" it began. The control plane refuses that, correctly, so it is clamped here where
        // the cause is known rather than surfacing as an unexplained provenance mismatch.
        Instant finishedAt = clock.instant();
        if (finishedAt.isBefore(executionStartedAt)) {
            finishedAt = executionStartedAt;
        }
        // Reporting time is what is left after execution, floored at zero. It is derived from two different
        // clocks — the control plane's start instant and this host's finish instant — so a host running behind
        // can produce a negative interval, and a negative duration would fail the contract's own bound.
        Duration reporting = Duration.between(executionStartedAt.plus(outcome.elapsed()), finishedAt);
        if (reporting.isNegative()) {
            reporting = Duration.ZERO;
        }
        String document = SyntheticResultDocument.build(
                mapper, command, outcome, executionStartedAt, finishedAt, provisioningElapsed, reporting,
                UUID.randomUUID(), UUID.randomUUID());
        var body = mapper.createObjectNode();
        body.put("assignmentEpoch", assignmentEpoch);
        body.put("commandId", command.commandId().toString());
        body.put("document", document);
        ControlPlaneClient.Response submission =
                controlPlane.submitResult(runId, attemptId, body.toString());
        if (!submission.ok()) {
            String code = codeOf(submission.body());
            // A LOST RESPONSE IS NOT A LOST RUN.
            //
            // The client retries a submission whose response never arrived, and the control plane commits
            // before replying — so a retry after a successful commit is answered RESULT_ALREADY_SUBMITTED.
            // Treating every non-200 as a refusal made the worker report a failure for a run that had in fact
            // completed on its own evidence. The control plane went to some trouble to return a distinguishable
            // code for exactly this, and nothing here was reading it.
            if (RESULT_ALREADY_SUBMITTED.equals(code)) {
                return ExecutionReport.completed(outcome.observations().get("workload_outcome"));
            }
            return ExecutionReport.refused("RESULT", code);
        }
        return ExecutionReport.completed(outcome.observations().get("workload_outcome"));
    }

    /**
     * Why this execution produced no trustworthy result, or null if it did.
     *
     * <p>Every condition here is one under which the sandbox did not demonstrably run the workload to
     * completion. Reporting any of them as a test outcome would attribute a platform failure to a tenant's
     * tests, which is the single most damaging thing this component can do.
     */
    private static String infrastructureFailureDetail(SandboxOutcome outcome) {
        // Deliberately NOT calling evidenceIsComplete(): it is `failure.isEmpty() || timedOut()`, and the check
        // below has already returned for every case where a failure is present — so it can never be false here.
        // It was in this chain and mutation testing showed removing it killed nothing, which is what dead code
        // looks like when it is wearing the shape of a control.
        if (outcome.failure().isPresent()) {
            return outcome.failure().orElseThrow().toString();
        }
        if (outcome.outOfMemory()) {
            return "The sandbox was stopped by its memory ceiling.";
        }
        if (outcome.outputTruncated()) {
            // The workload's own verdict may be in the part that was dropped, and a verdict read from a
            // truncated stream is a guess.
            return "The sandbox output was truncated, so its result cannot be read in full.";
        }
        if (outcome.exitCode().isEmpty() || outcome.exitCode().orElseThrow() != 0) {
            return "The sandbox exited with " + outcome.exitCode().map(String::valueOf).orElse("no status") + ".";
        }
        // The workload identifies itself, and the runner checks. Without this, ANY container that exited zero
        // and emitted the right key would be believed — and the probe's own comment claims this identity makes
        // a synthetic result unmistakable downstream, which was only true if somebody looked at it.
        String identity = outcome.observations().get("workload_identity");
        if (!SYNTHETIC_WORKLOAD_IDENTITY.equals(identity)) {
            return "The sandbox did not identify itself as " + SYNTHETIC_WORKLOAD_IDENTITY + ".";
        }
        String workloadOutcome = outcome.observations().get("workload_outcome");
        if (!"PASSED".equals(workloadOutcome) && !"FAILED".equals(workloadOutcome)) {
            return "The workload reported no outcome.";
        }
        return null;
    }

    /** The fixed identity the trusted workload reports. Anything else did not run what we asked for. */
    public static final String SYNTHETIC_WORKLOAD_IDENTITY = "KAAS_SYNTHETIC_V1";

    /**
     * Which workload this runner executes.
     *
     * <p>Runner configuration, NOT anything the command carries. It was selected by a tag, and tags come from
     * the tenant's own run profile — so a tenant could put the platform's control tag in their profile and have
     * their run reported FAILED when nothing had failed. The blast radius was small; the pattern was that the
     * platform's control channel and tenant input shared a namespace, and every control signal added later
     * would have inherited it.
     *
     * <p>Both values are platform-owned probes baked into the trusted image. Production runs the passing one;
     * a test constructs a loop with the failing one so that terminal outcome is reachable without giving a
     * tenant any say in it.
     */
    private final SyntheticProbe workload;

    /** The control plane's answer to "you already did this". */
    private static final String RESULT_ALREADY_SUBMITTED = "RESULT_ALREADY_SUBMITTED";

    private static final String PHASE_NOT_ENTERABLE = "PHASE_NOT_ENTERABLE";

    /**
     * Whether a refused advance is really this worker's own retry landing twice.
     *
     * <p>Only safe because the phase we asked for is the phase the control plane reports the run is already in.
     * Without that check this would swallow a genuine ordering error — a worker asking for a phase the run
     * cannot enter — and carry on as though it had succeeded.
     */
    private boolean alreadyInPhase(ControlPlaneClient.Response response, String phase) {
        if (!PHASE_NOT_ENTERABLE.equals(codeOf(response.body()))) {
            return false;
        }
        try {
            JsonNode state = mapper.readTree(response.body()).get("lifecycleState");
            return state != null && phase.equals(state.asString());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private ControlPlaneClient.Response advance(
            UUID runId, UUID attemptId, int assignmentEpoch, String phase, String sandboxReference)
            throws ControlPlaneUnavailable {
        var body = mapper.createObjectNode();
        body.put("assignmentEpoch", assignmentEpoch);
        body.put("phase", phase);
        body.put("sandboxReference", sandboxReference);
        return controlPlane.advancePhase(runId, attemptId, body.toString());
    }

    private String codeOf(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode code = node.get("code");
            return code == null ? "UNKNOWN" : code.asString();
        } catch (RuntimeException unreadable) {
            return "UNKNOWN";
        }
    }

    private Instant instantOf(String body, String field) {
        try {
            JsonNode node = mapper.readTree(body).get(field);
            return node == null ? null : Instant.parse(node.asString());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** What one assignment came to. */
    public record ExecutionReport(String status, String phase, String detail) {

        public static ExecutionReport completed(String testOutcome) {
            return new ExecutionReport("COMPLETED", "RESULT", testOutcome);
        }

        public static ExecutionReport refused(String phase, String code) {
            return new ExecutionReport("REFUSED", phase, code);
        }

        public static ExecutionReport rejected(String detail) {
            return new ExecutionReport("REJECTED", "VALIDATION", detail);
        }

        public static ExecutionReport infrastructureFailure(String detail) {
            return new ExecutionReport("INFRASTRUCTURE_FAILED", "EXECUTION", detail);
        }
    }
}
