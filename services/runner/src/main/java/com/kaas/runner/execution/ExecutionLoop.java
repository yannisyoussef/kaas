package com.kaas.runner.execution;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import com.kaas.runner.command.CommandRejected;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.command.ValidatedCommand;
import com.kaas.runner.sandbox.EgressExecution;
import com.kaas.runner.sandbox.EgressExecutions;
import com.kaas.runner.sandbox.EgressFailure;
import com.kaas.runner.sandbox.EgressPlan;
import com.kaas.runner.sandbox.EgressProxyStartFailed;
import com.kaas.runner.sandbox.EgressTarget;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.authority.ExecutionAuthority;
import com.kaas.runner.authority.ExecutionAuthorityMonitor;
import com.kaas.runner.authority.HeartbeatRenewal;
import com.kaas.runner.authority.MonotonicClock;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceBundleRejected;
import com.kaas.runner.source.SourceStaging;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * How this runner instantiates egress, or null if it cannot.
     *
     * <p>Null is the safe construction and the ordinary one for a deployment that enforces {@code DENY_ALL}
     * only. It is not the check that keeps such a runner safe — the command validator refuses an
     * {@code ALLOWLIST} command outright unless this host demonstrated it can enforce one — but a second,
     * independent refusal here means a wiring mistake fails closed rather than reaching a null dereference
     * halfway through provisioning.
     */
    private final EgressExecutions egressExecutions;

    /** Where this host stages tenant source, or null when it stages none. Platform-owned in every part. */
    private final java.nio.file.Path sourceStagingRoot;

    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock) {
        this(controlPlane, validator, launcher, mapper, clock, SyntheticProbe.WORKLOAD_PASS, null);
    }

    /** For the one test that needs the FAILED terminal outcome to be reachable. */
    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock,
            SyntheticProbe workload) {
        this(controlPlane, validator, launcher, mapper, clock, workload, null);
    }

    /** The full form: a runner that can also enforce a destination allowlist. */
    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock,
            SyntheticProbe workload,
            EgressExecutions egressExecutions) {
        this(controlPlane, validator, launcher, mapper, clock, workload, egressExecutions, null);
    }

    /**
     * The full form: a runner that can also deliver inert tenant source.
     *
     * @param sourceStagingRoot where this host may stage tenant source, or null when it stages none. Operator
     *     configuration, never tenant-selected and never derived from anything a request carried.
     */
    public ExecutionLoop(
            ControlPlaneClient controlPlane,
            CommandValidator validator,
            SandboxLauncher launcher,
            ObjectMapper mapper,
            Clock clock,
            SyntheticProbe workload,
            EgressExecutions egressExecutions,
            java.nio.file.Path sourceStagingRoot) {
        this.controlPlane = controlPlane;
        this.validator = validator;
        this.launcher = launcher;
        this.mapper = mapper;
        this.clock = clock;
        this.workload = workload;
        this.egressExecutions = egressExecutions;
        this.sourceStagingRoot = sourceStagingRoot;
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
        EgressPlan plan;
        String sourceToken;
        try {
            JsonNode envelope = mapper.readTree(authorization.body());
            JsonNode document = envelope.get("command");
            if (document == null) {
                return ExecutionReport.rejected("The authorization carried no command.");
            }
            command = validator.validate(document.toString(), clock.instant());
            // Read from the envelope rather than the command, and that is deliberate on both counts. The
            // credential rotates on every delivery, so a digest could not cover it and a field the digest
            // cannot cover must not be inside the document. The destinations are aiming material for the
            // platform's own workload rather than authority: the proxy resolves the policy from authoritative
            // state on every request, so a destination altered in transit is refused there. What the command
            // DOES bind, and what the validator already checked, is the policy's revision id and digest.
            plan = egressPlan(envelope);
            // Read from the ENVELOPE, never from the command. Capabilities rotate on every delivery, so a
            // token inside an immutable document would be stale from the second request onward -- and a
            // bearer credential inside a digested document is a credential in every log that records the
            // document. It exists in this variable and nowhere else.
            JsonNode token = envelope.get("sourceCapabilityToken");
            sourceToken = token == null || !token.isString() ? null : token.stringValue();
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

        // 3. CONTINUOUS EXECUTION AUTHORITY, maintained for the whole of what follows.
        //
        // Started before provisioning and closed after the final transition, so every worker-owned phase is
        // inside its lifetime. It renews the lease exactly as the old heartbeat did, and unlike the old
        // heartbeat it acts on the answer: a definitive refusal stops the sandbox where it stands, and an
        // unreachable control plane consumes the remaining lease budget and then stops it anyway.
        //
        // The initial budget is the lease the platform granted at claim time, which is already running. A
        // monitor that began unbounded would leave a window before the first renewal in which nothing bounded
        // execution at all — the exact gap this slice exists to close, reintroduced at its own start.
        try (ExecutionAuthorityMonitor authority = ExecutionAuthorityMonitor.start(
                new HeartbeatRenewal(
                        controlPlane,
                        mapper,
                        runId,
                        attemptId,
                        mapper.createObjectNode().put("assignmentEpoch", assignmentEpoch).toString(),
                        RENEWAL_TIMEOUT),
                MonotonicClock.system(),
                HEARTBEAT_INTERVAL,
                SAFETY_MARGIN,
                INITIAL_AUTHORITY_BUDGET,
                "kaas-authority-" + runId)) {
            return execute(runId, attemptId, assignmentEpoch, command, plan, sourceToken, authority);
        }
    }

    /**
     * Starts one workload under the authority that is watching this execution.
     *
     * <p>One call site for both network policies, deliberately. The allowlist and deny-all paths use different
     * launchers, different probes and different profile versions, and for a while they also each passed the
     * authority separately — which meant a change that dropped it from one of them left the other looking
     * correct. That is not hypothetical: mutating either call in isolation was undetectable, because the
     * loop's own authority check still stopped the run and the abandoned sandbox merely ran to its profile
     * deadline instead of being terminated. Bounded, real, and invisible.
     */
    private SandboxOutcome runWorkload(
            SandboxLauncher sandboxes,
            SyntheticProbe probe,
            String profileVersion,
            UUID runId,
            ExecutionAuthority authority) {
        return sandboxes.run(new SandboxLaunchRequest(probe, profileVersion, runId), authority);
    }

    /**
     * Redeems the authorized source bundle and verifies it, or returns null when this deployment stages none.
     *
     * <h2>Why this happens while the run is still CLAIMED</h2>
     *
     * <p>Not a choice: a source capability is redeemable only while the run is {@code CLAIMED}, which is the
     * lifecycle the control plane has enforced since capabilities existed. Redeeming after PROVISIONING is
     * announced is refused, and the honest response to that is to redeem before it rather than to widen the
     * window on the control-plane side for a worker's convenience.
     *
     * <p>What happens here is a bounded transfer and pure computation. Nothing is written to this host:
     * materialisation waits until PROVISIONING is announced, so no tenant byte reaches a disk before a phase
     * deadline and a reconciler are accountable for it.
     *
     * <p>Authority is re-read before the bundle is fetched and again after it arrives, because obtaining
     * tenant source is execution work: a worker that lost its assignment mid-redemption must not go on to
     * prepare bytes for a run it no longer owns.
     *
     * <p>What is verified here is verified against the COMMAND -- its bundle digest and its exact feature
     * list -- and not against anything the response says about itself. A control-plane defect must not become
     * source substitution.
     */
    /**
     * Writes the verified bundle to this host, under a phase that owns it.
     *
     * <p>Separate from redemption because they happen in different lifecycle states and fail differently:
     * redemption is a bounded network transfer that leaves nothing behind, and this puts tenant bytes on a
     * disk. Authority is re-read immediately before the write, so a worker fenced during PROVISIONING does
     * not leave source on a host it no longer serves.
     */
    private SourceStaging materialiseSource(SourceBundle bundle, ExecutionAuthority authority) {
        if (bundle == null) {
            return null;
        }
        if (authority.lost()) {
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.AUTHORITY_LOST, "Authority ended before source was staged.");
        }
        return SourceStaging.materialise(sourceStagingRoot, bundle);
    }

    private SourceBundle redeemSource(
            ValidatedCommand command, String capabilityToken, ExecutionAuthority authority) {
        if (sourceStagingRoot == null) {
            // This deployment stages no source. The sandbox runs exactly as it did before, which is what
            // every gate and probe suite in this repository still does.
            return null;
        }
        if (capabilityToken == null || capabilityToken.isBlank()) {
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.NOT_REDEEMABLE, "The delivery carried no source capability.");
        }
        if (authority.lost()) {
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.AUTHORITY_LOST, "Authority ended before source was obtained.");
        }
        byte[] archive;
        try {
            archive = controlPlane.redeemSourceBundle(capabilityToken, SourceBundleContract.MAX_TOTAL_BYTES);
        } catch (ControlPlaneUnavailable unavailable) {
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.NOT_REDEEMABLE, "The source bundle could not be redeemed.");
        }
        if (archive == null) {
            // The control plane refused the capability -- expired, fenced, cancelled, superseded, or for a
            // different assignment. Which one is its business; from here it is simply not redeemable.
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.NOT_REDEEMABLE, "The source capability was refused.");
        }
        var expected = command.sourceBundle().features().stream()
                .map(feature -> new SourceBundle.ExpectedEntry(feature.logicalPath(), feature.contentDigest()))
                .toList();
        SourceBundle bundle =
                SourceBundle.verified(archive, expected, command.sourceBundle().contentDigest());
        if (authority.lost()) {
            // Checked again, after the transfer and before anything is written. The window between the two
            // is exactly as long as the transfer, which is bounded, and nothing is on the host yet.
            throw new SourceBundleRejected(
                    SourceBundleRejected.Reason.AUTHORITY_LOST, "Authority ended before source was staged.");
        }
        return bundle;
    }

    /** The policy type whose executions run behind a proxy. Every other type uses the no-network path. */
    private static final String ALLOWLIST = "ALLOWLIST";

    /**
     * The egress material from an authorization envelope, or null when it carried none.
     *
     * <p>Absent rather than empty for a policy that needs none, which is what the control plane emits: a
     * {@code DENY_ALL} sandbox has nothing to present a credential to, and a null-valued field would invite a
     * worker to pass something along anyway. Null here therefore means "this delivery was not for an
     * allowlist", and an allowlist command that arrives with it is refused below rather than run.
     */
    private EgressPlan egressPlan(JsonNode envelope) {
        JsonNode token = envelope.get("egressCapabilityToken");
        JsonNode destinations = envelope.get("egressDestinations");
        if (token == null || token.isNull() || destinations == null || !destinations.isArray()) {
            return null;
        }
        List<EgressTarget> targets = new ArrayList<>();
        destinations.forEach(destination -> targets.add(new EgressTarget(
                destination.get("host").asString(),
                destination.get("port").asInt(),
                destination.get("scheme").asString())));
        return new EgressPlan(token.asString(), targets);
    }

    /** How often the lease is renewed while a run executes. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /**
     * How long one renewal attempt may take.
     *
     * <p>Bounded, and deliberately far below any lease. The ordinary control-plane call retries three times
     * with backoff, which can exceed ninety seconds — longer than the lease it would be renewing — so the
     * monitor uses a single bounded attempt and does its own retrying on the interval above.
     */
    private static final Duration RENEWAL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How much of the lease is given up so the worker stops before its authority can have ended.
     *
     * <p>Derived rather than chosen. The worker must be able to make at least one complete renewal attempt
     * inside the margin — otherwise the deadline could fall in the middle of the very request that would have
     * extended it — so the floor is one interval plus one renewal timeout. That is what this is.
     *
     * <p>Stopping early costs a run that might have continued. Stopping late means code ran with no authority
     * behind it, which is the failure that matters, so the margin errs toward stopping early.
     */
    private static final Duration SAFETY_MARGIN = HEARTBEAT_INTERVAL.plus(RENEWAL_TIMEOUT);

    /**
     * How long execution may continue before the first successful renewal.
     *
     * <p>Conservative on purpose: the runner cannot see the lease the platform granted until a renewal tells
     * it, and assuming a generous one would be assuming authority it has not been given. One margin's worth is
     * enough to reach the first renewal and no more.
     */
    private static final Duration INITIAL_AUTHORITY_BUDGET = SAFETY_MARGIN.plus(HEARTBEAT_INTERVAL);

    private ExecutionReport execute(
            UUID runId,
            UUID attemptId,
            int assignmentEpoch,
            ValidatedCommand command,
            EgressPlan plan,
            String sourceCapabilityToken,
            ExecutionAuthority authority)
            throws ControlPlaneUnavailable {

        // 2b. THE AUTHORIZED SOURCE, redeemed while the run is still CLAIMED.
        //
        // Placed here because the capability's own rule puts it here: redemption requires a CLAIMED run, and
        // announcing PROVISIONING first would make every redemption fail. Nothing is written yet -- the bytes
        // are bounded, in memory, and verified against the command before a phase owns any file.
        SourceBundle redeemedSource;
        try {
            redeemedSource = redeemSource(command, sourceCapabilityToken, authority);
        } catch (SourceBundleRejected rejected) {
            // Refused before any phase advance, so there is no phase to fail and nothing to clean up. The
            // category travels; a logical path or a byte of source does not.
            return infrastructureFailure(
                    runId, attemptId, assignmentEpoch,
                    "The authorized source bundle was refused (" + rejected.reason() + ").");
        }

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
        //    feature source, no secret, and — under DENY_ALL — no network.
        // The SANDBOX profile version, not the engine version. They are different things that happen to be
        // strings, and passing the engine version made the launcher refuse with "Unknown security profile
        // version" — which was the launcher correctly refusing to run under a profile it does not recognise.
        // THE RUNTIME THIS COMMAND WAS AUTHORIZED FOR MUST BE THE ONE THIS WORKER WILL INSTANTIATE.
        //
        // Three things have to name the same boundary: the signed attestation, the command the control plane
        // issued from it, and the runtime this worker actually runs. The first two are tied together at
        // authorization, where the command's runtime is copied out of the signed payload. This is the third
        // link, and it is the one that cannot be checked anywhere else -- only this process knows what it is
        // configured to launch.
        //
        // Compared by NAME. Nothing here resolves the string to a runtime: a command that could select the
        // runtime would be a command that selects which program the daemon executes.
        //
        // The launcher already refuses a profile version it does not hold, and because the profile version is
        // derived from the runtime that check would catch this case too -- as "Unknown security profile
        // version", which sends an operator looking for a profile problem. A worker configured for the
        // baseline runtime receiving work authorized for the mediating one is not a profile problem; it is a
        // deployment that would have run hostile-code work behind a boundary nobody authorized.
        String instantiatedRuntime = launcher.profile().runtime().name();
        if (!instantiatedRuntime.equals(command.sandboxRuntime())) {
            return infrastructureFailure(
                    runId, attemptId, assignmentEpoch,
                    "This worker instantiates a different sandbox runtime than the command authorizes.");
        }

        // 4b. INERT TENANT SOURCE, obtained and staged before any sandbox exists.
        //
        // PROVISIONING work, placed here deliberately: the phase is already announced, so the phase
        // deadline and the authority monitor both cover everything below, and a crash leaves resources a
        // reconciler knows to look for. Preparing tenant bytes before any lifecycle owned them would be the
        // one shape that leaves source on a host nothing is accountable for.
        //
        // try-with-resources, so the bytes live exactly as long as this block. Every exit -- a refused
        // bundle, a lost authority, a failed launch, a timeout, a thrown anything -- removes them, rather
        // than each branch remembering to.
        SandboxOutcome outcome;
        String egressDetail = null;
        try (SourceStaging staging = materialiseSource(redeemedSource, authority)) {
            // The launcher that will actually run the workload. Derived from the configured one, so it
            // differs in exactly one respect: it carries this execution's source. A separate launcher built
            // from scratch could differ in others without anybody noticing.
            SandboxLauncher workloadLauncher = staging == null
                    ? launcher
                    : launcher.withSource(staging.root());
            if (ALLOWLIST.equals(command.networkPolicyType())) {
                // Refused, never degraded. The command validator already refuses an allowlist this host cannot
                // enforce, so arriving here without a mechanism or without egress material is a wiring mistake
                // rather than a state a control plane can produce — and a wiring mistake that silently ran the
                // execution with no network would be an allowlist that permits everything and delivers nothing,
                // reported as a completed run.
                //
                // REPORTED, not merely returned. By this point the run is in RUNNING and the control plane is
                // holding a phase deadline against it. Returning quietly would leave it there until a reconciler
                // reclaimed it and recorded a timeout — a failure the worker observed within milliseconds and
                // then discarded, which is precisely the bug the infrastructure-failure endpoint exists to end.
                if (egressExecutions == null) {
                    return infrastructureFailure(
                            runId, attemptId, assignmentEpoch,
                            "This runner has no egress mechanism for an allowlist.");
                }
                if (plan == null) {
                    return infrastructureFailure(
                            runId, attemptId, assignmentEpoch,
                            "An allowlist authorization carried no egress material.");
                }
                // The profile the sandbox will run under has to be the networked derivative of the profile the
                // command was authorized under. Checked rather than assumed: a launcher configured against some
                // other profile would run the execution anyway, and the evidence would then name a policy that
                // did not produce it. Checked here, after the mechanism exists but before any sandbox does, so a
                // mismatch costs a network and a proxy rather than an untrusted container.
                String expected = SandboxSecurityProfile.networkedVersionOf(command.sandboxProfileVersion());
                try (EgressExecution egress = egressExecutions.start(runId, plan)) {
                    if (!expected.equals(egress.profileVersion())) {
                        return infrastructureFailure(
                                runId, attemptId, assignmentEpoch,
                                "The egress sandbox profile is not the one this command authorized.");
                    }
                    // The egress workload, not the configured one. Which workload an allowlist execution runs is
                    // a property of the policy rather than of this runner's configuration: an allowlist run whose
                    // workload never touched the network would complete successfully having demonstrated nothing.
                    // The egress launcher carrying this execution's source. Derived from the one the egress
                    // mechanism built, so the networked profile keeps every property it already had and gains
                    // exactly one -- rather than being rebuilt and quietly differing somewhere else.
                    outcome = runWorkload(
                            staging == null ? egress.launcher() : egress.launcher().withSource(staging.root()),
                            SyntheticProbe.WORKLOAD_EGRESS,
                            expected,
                            runId,
                            authority);
                    if (!egress.proxyIsRunning()) {
                        // CONSERVATIVE, and deliberately unconditional. The sandbox may have produced a
                        // perfectly well-formed result before the proxy died, and that result is still evidence
                        // gathered while the execution's only egress peer was going away. Nothing has been
                        // submitted yet, so nothing trustworthy is being discarded — and reporting a test outcome
                        // from an execution whose enforcement point vanished mid-run would be the platform
                        // blaming a tenant for its own failure.
                        egressDetail = "The egress proxy did not survive the execution ("
                                + EgressFailure.EGRESS_PROXY_DIED + ").";
                    }
                } catch (EgressProxyStartFailed cannotStart) {
                    // NO SANDBOX WAS CREATED. There is no degraded mode: an allowlist execution without a proxy
                    // is an execution with no enforcement, and the truthful outcome is an infrastructure failure.
                    // The category travels; the cause does not, because a daemon error carries socket paths,
                    // host directories, and image references.
                    return infrastructureFailure(
                            runId, attemptId, assignmentEpoch,
                            "The egress mechanism could not be started (" + cannotStart.failure() + ").");
                }
            } else {
                outcome = runWorkload(
                        workloadLauncher, workload, command.sandboxProfileVersion(), runId, authority);
            }
        } catch (SourceBundleRejected rejected) {
            // Refused BEFORE a sandbox exists. The run fails as INFRASTRUCTURE rather than as a test: no
            // tenant assertion ran, and recording a test failure for a delivery defect would blame a tenant
            // for the platform.
            //
            // The category travels and nothing else does. A logical path or a fragment of source in this
            // message would be tenant content in a control-plane log.
            return infrastructureFailure(
                    runId, attemptId, assignmentEpoch,
                    "The authorized source bundle was refused (" + rejected.reason() + ").");
        }
        // ABSENT OR INCOMPLETE EVIDENCE IS AN INFRASTRUCTURE FAILURE, NOT A TEST RESULT.
        //
        // Checking only failure() was the bug: DockerSandboxLauncher returns an EMPTY failure whenever the
        // container exited and its log stream drained — including a non-zero exit, an OOM kill, and truncated
        // output. The result builder then read a missing workload_outcome as `!"PASSED".equals(null)` and
        // submitted a perfectly well-formed document saying the infrastructure succeeded and the tenant's test
        // failed. Every constraint in the system accepts that document, because it is internally consistent.
        // It is simply false, and it is false in the direction that blames the tenant for the platform.
        // AUTHORITY FIRST, BEFORE ANY SUBMISSION.
        //
        // Checked here, ahead of every other reading of the outcome, because a workload that was stopped may
        // still have produced a perfectly well-formed result a moment earlier — and that result is not
        // evidence about anything. It was produced by a worker that no longer had the right to produce it.
        //
        // Nothing is reported to the control plane. Whatever revoked the authority already knows what the run
        // is; a stale worker adding to that is exactly the writing that fencing exists to refuse, and
        // attempting it would produce a confusing refusal rather than a clean stop.
        if (authority.lost()) {
            return ExecutionReport.authorityLost(authority.lostReason().name());
        }

        // The egress verdict takes precedence. An outcome gathered while the execution's enforcement point
        // was disappearing is not evidence about anything, whatever shape it happens to have.
        String detail = egressDetail != null ? egressDetail : infrastructureFailureDetail(outcome);
        if (detail != null) {
            return infrastructureFailure(runId, attemptId, assignmentEpoch, detail);
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
     * Reports an infrastructure failure to the control plane and returns it.
     *
     * <p>TELL THE CONTROL PLANE. Returning this to our own caller and stopping — which is what this once did —
     * left the run in its phase until a deadline reclaimed it, recorded as a timeout. The platform had
     * observed the failure within seconds and then discarded it.
     *
     * <p>Shared by the sandbox path and the egress path so both reach the control plane the same way. A
     * second copy of this is a second chance for one of them to quietly return without reporting.
     */
    private ExecutionReport infrastructureFailure(
            UUID runId, UUID attemptId, int assignmentEpoch, String detail) throws ControlPlaneUnavailable {
        var body = mapper.createObjectNode();
        body.put("assignmentEpoch", assignmentEpoch);
        // Sanitised to the character set the endpoint accepts. This is our description of our own
        // infrastructure, never workload output and never a credential.
        body.put("detail", detail.replaceAll("[^A-Za-z0-9 .,:;()/_-]", " "));
        ControlPlaneClient.Response reported =
                controlPlane.reportInfrastructureFailure(runId, attemptId, body.toString());
        if (!reported.ok() && reported.status() != 204) {
            // Say so. Silently discarding the answer to "did the control plane accept my failure report" is
            // the same shape as the bug this endpoint exists to fix — a failure the worker observed and then
            // dropped, leaving the run to be reclaimed by a deadline under a false reason.
            return ExecutionReport.infrastructureFailure(
                    detail + " (report refused: " + codeOf(reported.body()) + " " + reported.status() + ")");
        }
        return ExecutionReport.infrastructureFailure(detail);
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

        /**
         * This worker stopped because it no longer had the authority to continue.
         *
         * <p>Its own status because it is neither a refusal nor an infrastructure failure. A refusal is the
         * control plane declining a transition this worker attempted; this worker attempts none. An
         * infrastructure failure says something is wrong with the host, and nothing is — the platform decided
         * this assignment was over, and the worker complied.
         *
         * <p>Nothing is submitted alongside it. Whatever decided the authority was gone — a cancellation, a
         * fence, an expired lease — already knows what the run is, and a stale worker reporting on top of that
         * would be a stale worker writing.
         */
        public static ExecutionReport authorityLost(String reason) {
            return new ExecutionReport("AUTHORITY_LOST", "EXECUTION", reason);
        }
    }
}
