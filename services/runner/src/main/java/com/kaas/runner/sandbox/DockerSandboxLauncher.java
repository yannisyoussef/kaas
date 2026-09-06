package com.kaas.runner.sandbox;

import com.kaas.runner.authority.ExecutionAuthority;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one synthetic probe under the fixed security profile, on an ordinary Docker daemon.
 *
 * <p>Standard Docker is chosen as the MVP boundary in full knowledge that it is not, by itself, a sufficient
 * boundary for hostile code: a container shares the host kernel, and a kernel bug is a container escape. What
 * it does provide — on every host KaaS runs on, without special daemon configuration — is a set of controls
 * that can each be turned on and then <em>demonstrated from inside the sandbox</em>. That combination of
 * portability and provability is why it is the baseline, and the ADR names gVisor and microVMs as the
 * stronger boundaries that replace it before user content is ever admitted.
 *
 * <p>Every container setting is derived here from the profile. None is reachable from a caller.
 */
public final class DockerSandboxLauncher implements SandboxLauncher {
    /** How long to keep draining a finished sandbox's output before giving up on the rest of it. */
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final DockerClient docker;
    private final SandboxSecurityProfile profile;
    private final String generation;

    public DockerSandboxLauncher(DockerClient docker, SandboxSecurityProfile profile, String generation) {
        this.docker = docker;
        this.profile = profile;
        this.generation = generation;
    }

    @Override
    public SandboxSecurityProfile profile() {
        return profile;
    }

    @Override
    public SandboxLauncher withSource(java.nio.file.Path sourceMount) {
        return new DockerSandboxLauncher(
                docker, SandboxSecurityProfile.withSource(profile, sourceMount), generation);
    }

    @Override
    public SandboxOutcome run(SandboxLaunchRequest request, ExecutionAuthority authority) {
        if (!profile.version().equals(request.profileVersion())) {
            // A request naming a profile this launcher does not hold is refused rather than silently run under
            // whatever profile happens to be configured. Evidence has to say which policy produced it.
            throw new IllegalArgumentException("Unknown security profile version.");
        }
        Instant startedAt = Instant.now();
        String containerId = null;
        SandboxOutcome outcome;
        try {
            containerId = create(request);
            requireRuntimeEnforced(containerId);
            // CHECKED AFTER CREATION AND BEFORE START. Authority can be lost while a sandbox is being built,
            // and a container that is created but never started leaves nothing running to terminate. The
            // creation is undone by the ordinary cleanup below, so this costs a container and no execution.
            if (authority.lost()) {
                throw new AuthorityLostException(authority.lostReason());
            }
            docker.startContainerCmd(containerId).exec();
            outcome = observe(containerId, startedAt, authority);
        } catch (RuntimeException failure) {
            outcome = new SandboxOutcome(
                    Optional.empty(),
                    Map.of(),
                    false,
                    0,
                    Duration.between(startedAt, Instant.now()),
                    false,
                    Optional.of(classify(failure)));
        }
        // Cleanup runs on success, failure, timeout, and launcher exception alike. A sandbox that outlives its
        // launcher is a resource leak at best and a running copy of untrusted code at worst.
        //
        // It deliberately does not run in a finally block. A throw from finally replaces the pending return, so
        // a run that succeeded and merely failed to be removed lost every observation it had just produced, and
        // the caller — the security gate — aborted by exception instead of returning verdicts. A cleanup
        // failure is now folded into the outcome, which is what SANDBOX_CLEANUP_FAILED existed for and never
        // reached.
        try {
            remove(containerId);
        } catch (SandboxCleanupException cleanupFailed) {
            return outcome.withFailure(cleanupFailed.failure());
        }
        return outcome;
    }

    /**
     * Reads back which runtime the daemon actually assigned, before the workload starts.
     *
     * <p>REQUESTED IS NOT ENFORCED. A daemon with no {@code runsc} runtime registered does not fail the create
     * call in every configuration, and a misconfigured one can fall through to its default — which is
     * {@code runc}. That would produce a sandbox that looks identical in every respect except the only one
     * that mattered, running a workload authorized for a stronger boundary under a weaker one, silently.
     *
     * <p>Checked before start rather than after, so a mismatch never runs the workload at all. And it throws
     * rather than returning a failed outcome: this is not the sandbox misbehaving, it is the platform being
     * unable to provide the boundary it promised, and there is deliberately no branch here that continues
     * under the runtime it got.
     *
     * <p>This is the launcher's half of the evidence. The other half is observed from <em>inside</em> the
     * sandbox, because a daemon reporting a runtime name is still the daemon answering a question about
     * itself.
     */
    private void requireRuntimeEnforced(String containerId) {
        String assigned;
        try {
            assigned = docker.inspectContainerCmd(containerId).exec().getHostConfig().getRuntime();
        } catch (RuntimeException unreadable) {
            throw new SandboxRuntimeUnavailableException(
                    "The sandbox runtime could not be read back from the daemon.");
        }
        String expected = profile.runtime().daemonRuntimeName();
        // A null means the daemon reported no explicit runtime, which is the default one. Treated as a
        // mismatch for anything but the default rather than as "probably fine".
        if (!expected.equals(assigned)) {
            throw new SandboxRuntimeMismatchException(
                    "The sandbox was assigned runtime " + assigned + " but the profile requires " + expected
                            + "; refusing rather than running under a boundary that was not authorized.");
        }
    }

    private String create(SandboxLaunchRequest request) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                // No network at all. Not a restrictive network — none. This is the strongest simple default
                // and the only one whose enforcement is trivially provable. Taken from the profile rather than
                // written here, so the record's constructor — which refuses any other value — is what enforces
                // it. A policy field the launcher ignores is a claim with nothing behind it.
                .withNetworkMode(profile.networkMode())
                .withReadonlyRootfs(profile.readOnlyRootFilesystem())
                // Writable, in memory, bounded, and mounted without executable permission or setuid: a writable
                // path that can also be executed is a place to stage a payload.
                //
                // /dev/shm is sized here too. The runtime supplies one whether or not it is asked to, and its
                // 64MB default is four times the ceiling this profile declares — measured: 65,859,584 bytes
                // written into it while the profile claimed a 16MB bound. It is charged to the memory cgroup,
                // so it is not a host-memory escape, but a limit the profile does not set is not a limit the
                // profile can claim.
                .withTmpFs(Map.of(
                        "/tmp", "rw,noexec,nosuid,nodev,size=" + profile.temporaryFilesystemBytes(),
                        "/dev/shm", "rw,noexec,nosuid,nodev,size=" + profile.temporaryFilesystemBytes()))
                .withCapDrop(Capability.values())
                .withSecurityOpts(List.of("no-new-privileges:true"))
                // The daemon writes every byte the sandbox prints to a host file, and that write happens
                // outside the container's cgroup: it is charged to nothing, throttled by nothing, and invisible
                // to the collector's own ceiling. Measured at 883 MB/s and 35.11 GB from one sandbox before
                // this bound existed. The collector's limit bounds what the launcher keeps; this bounds what
                // the host is made to store.
                .withLogConfig(new LogConfig(
                        LogConfig.LoggingType.JSON_FILE,
                        Map.of("max-size", profile.maximumLogBytes() + "b", "max-file", "1")))
                .withMemory(profile.memoryLimitBytes())
                // Equal to memory, which is the runtime's way of saying "no swap". Without it a workload
                // simply swaps past the ceiling and the limit is decorative.
                .withMemorySwap(profile.memorySwapLimitBytes())
                .withCpuQuota(profile.cpuQuotaMicroseconds())
                .withCpuPeriod(profile.cpuPeriodMicroseconds())
                .withPidsLimit(profile.pidsLimit())
                // Explicitly empty. Not "no dangerous mounts" — no mounts. There is no host path a sandbox has
                // any reason to see, and an empty list cannot be got wrong the way an exclusion list can.
                .withBinds(List.of())
                .withPrivileged(false)
                // WHICH RUNTIME CONFINES THIS. Taken from the profile, never from a caller: the runtime name
                // is the name of a program the daemon will execute, so a caller who could choose it would be
                // choosing what runs rather than merely how. Requesting it is not the same as getting it,
                // which is why requireRuntimeEnforced() reads it back below.
                .withRuntime(profile.runtime().daemonRuntimeName())
                .withAutoRemove(false);

        if (profile.sourceMount() != null) {
            // INERT TENANT SOURCE, READ-ONLY.
            //
            // Both sides of this bind are platform-owned: the host side is an opaque directory under the
            // operator's staging root, and the container side is a fixed constant. No tenant byte contributes
            // to either, which is what keeps "source is data" true at the mount as well as in the format.
            //
            // Read-only is measured rather than assumed -- see the mount evidence in
            // docs/security/tenant-source-delivery.md, which also records, honestly, that noexec does NOT
            // survive onto a gofer-backed mount under the mediating runtime. That gap is why the materialiser
            // writes every file without an executable bit and why the format cannot express one, and it is
            // reported rather than papered over.
            hostConfig.withMounts(java.util.List.of(new com.github.dockerjava.api.model.Mount()
                    .withType(com.github.dockerjava.api.model.MountType.BIND)
                    .withSource(profile.sourceMount().toAbsolutePath().toString())
                    .withTarget(com.kaas.runner.source.SourceBundleContract.CONTAINER_PATH)
                    .withReadOnly(true)));
        }

        CreateContainerResponse created = createOrRefuse(hostConfig, request);
        return created.getId();
    }

    /**
     * Creates the container, distinguishing "this host has no such runtime" from every other create failure.
     *
     * <p>Measured, not assumed: a daemon with no {@code runsc} registered refuses the create outright with
     * {@code unknown or invalid runtime name}. So an unavailable runtime cannot silently become the default
     * one — the container is never created at all. This method exists to make that outcome <em>legible</em>
     * rather than to make it safe; it was already safe.
     */
    private CreateContainerResponse createOrRefuse(HostConfig hostConfig, SandboxLaunchRequest request) {
        try {
            return createContainer(hostConfig, request);
        } catch (RuntimeException refused) {
            if (!runtimeIsRegistered(profile.runtime().daemonRuntimeName())) {
                throw new SandboxRuntimeUnavailableException(
                        "This host does not provide the runtime this sandbox was authorized for: "
                                + profile.runtime().daemonRuntimeName());
            }
            throw refused;
        }
    }

    /** Whether the daemon knows this runtime at all. Asked only to explain a refusal, never to permit one. */
    private boolean runtimeIsRegistered(String runtimeName) {
        try {
            var registered = docker.infoCmd().exec().getRuntimes();
            return registered != null && registered.containsKey(runtimeName);
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    private CreateContainerResponse createContainer(
            HostConfig hostConfig, SandboxLaunchRequest request) {
        return docker.createContainerCmd(profile.imageReference())
                .withHostConfig(hostConfig)
                .withUser(profile.runAsUser())
                .withCmd(request.probe().arguments())
                .withEnv(environment())
                .withLabels(SandboxLabels.of(generation, request.correlationId(), profile.version()))
                .withAttachStdout(true)
                .withAttachStderr(true)
                // Disabled only when the profile says no network. Leaving this unconditionally true would
                // silently override the network mode above, so a sandbox that was meant to reach its proxy
                // would instead have nothing — an allowlist that permits everything and delivers nothing.
                .withNetworkDisabled("none".equals(profile.networkMode()))
                .exec();
    }

    /**
     * The sandbox's entire environment, built from nothing.
     *
     * <p>Never the host's environment with known-sensitive names removed: subtraction requires knowing every
     * name worth removing, and the one nobody thought of is the one that leaks. Starting empty means a new
     * credential in the launcher's environment cannot become a new leak in the sandbox.
     */
    private List<String> environment() {
        List<String> variables = new ArrayList<>();
        profile.environment().forEach((name, value) -> variables.add(name + "=" + value));
        return List.copyOf(variables);
    }

    /**
     * Waits for the probe, collecting bounded output, and terminates it at the deadline.
     *
     * <p>The deadline is enforced by the launcher, not by the workload: a sandbox that has to cooperate in its
     * own termination is not bounded at all.
     */
    /**
     * How long a sandbox is given to stop on its own before it is killed.
     *
     * <p>Short, and bounded by construction. The workload being asked to stop is the workload whose authority
     * has just been revoked; waiting politely for it to agree would make the stopping time a property of the
     * code being contained rather than of the platform containing it. Hostile code that ignores the signal
     * simply reaches the forced kill.
     */
    private static final Duration GRACEFUL_STOP = Duration.ofSeconds(5);

    /**
     * How often the authority is re-read while a workload runs.
     *
     * <p>This is the resolution of the whole mechanism: a revocation cannot be acted on sooner than the next
     * check. Short enough that termination is prompt, long enough that it is not a spin loop — and it costs
     * nothing per tick, because the wait it interrupts is already blocked on a latch.
     */
    private static final Duration AUTHORITY_POLL = Duration.ofMillis(250);

    /**
     * Waits for the workload to exit, while a sentinel terminates it if its authority ends.
     *
     * <h2>Why a second thread rather than a sliced wait</h2>
     *
     * <p>The obvious implementation is to await the exit in short slices and re-read the authority between
     * them. It does not work, and the failure is silent: docker-java's
     * {@code awaitCompletion(timeout, unit)} <strong>closes the underlying stream when it times out</strong>,
     * so the first slice that expires destroys the wait and every later call returns "completed" immediately.
     * Measured against a container sleeping for sixty seconds: slice one returned false at 257ms, and slice
     * two returned <em>true</em> at 261ms with the container still running. A sliced wait would therefore have
     * reported every long workload as finished a quarter of a second after it started.
     *
     * <p>So the wait stays exactly as it was — one blocking call with the profile deadline — and a small
     * sentinel watches the authority alongside it. When authority ends the sentinel terminates the container,
     * and the blocking wait returns on its own because the thing it was waiting for died. The interruption is
     * real rather than cooperative: nothing depends on the workload noticing anything.
     */
    private Integer awaitExit(String containerId, ExecutionAuthority authority) throws InterruptedException {
        WaitCallback exit = docker.waitContainerCmd(containerId).exec(new WaitCallback());
        Thread sentinel = startAuthoritySentinel(containerId, authority);
        try {
            return exit.awaitStatusCode(profile.wallClockTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            // Always, including when the workload finished on its own. A sentinel left running would keep
            // polling an authority for a sandbox that no longer exists, and on a busy runner that is a thread
            // per completed execution.
            sentinel.interrupt();
        }
    }

    /**
     * Watches the authority for as long as one sandbox runs.
     *
     * <p>Deliberately does nothing but observe and terminate. It records no outcome, touches no lifecycle
     * state and reports nothing: the execution thread owns all of that, and a second thread writing outcomes
     * is how two threads come to disagree about what happened.
     */
    private Thread startAuthoritySentinel(String containerId, ExecutionAuthority authority) {
        Thread sentinel = new Thread(
                () -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            if (authority.lost()) {
                                terminate(containerId);
                                return;
                            }
                            Thread.sleep(AUTHORITY_POLL.toMillis());
                        }
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException failure) {
                        // Never propagated. This runs on its own thread, so a throw here would be swallowed by
                        // the JVM's default handler and the only visible effect would be a sandbox that failed
                        // to stop -- which is the one outcome this code exists to prevent. The execution
                        // thread's own deadline remains as the backstop.
                    }
                },
                "kaas-authority-sentinel-" + containerId.substring(0, Math.min(12, containerId.length())));
        sentinel.setDaemon(true);
        sentinel.start();
        return sentinel;
    }

    /**
     * Stops a sandbox, politely and then not.
     *
     * <p>Docker's own stop is used for the graceful half because it already implements the bound: it signals
     * the workload, waits the timeout, and kills it itself. The explicit kill afterwards covers the cases
     * where that call fails or returns while something is still alive, because "the stop command returned" is
     * not the same claim as "nothing is running".
     */
    private void terminate(String containerId) {
        try {
            docker.stopContainerCmd(containerId)
                    .withTimeout((int) GRACEFUL_STOP.toSeconds())
                    .exec();
        } catch (RuntimeException alreadyStopping) {
            // A container that has already exited, or is already being stopped, is not a failure here.
        }
        kill(containerId);
    }

    private SandboxOutcome observe(String containerId, Instant startedAt, ExecutionAuthority authority) {
        BoundedOutput output = new BoundedOutput(profile.maximumOutputBytes());
        try {
            docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(output);
            Integer exitCode = awaitExit(containerId, authority);
            // The container exiting does not mean its output has arrived. Reading the observations at this
            // point without waiting for the stream to drain makes every conclusion drawn from them racy — and
            // a security check that intermittently sees nothing is a security check that intermittently
            // reports a control it never observed.
            boolean drained = output.awaitCompletion(
                    OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Duration elapsed = Duration.between(startedAt, Instant.now());
            // WHY THE SANDBOX ENDED, AND NOT MERELY THAT IT DID.
            //
            // A terminated container exits with a status like any other, so without this a sandbox the
            // platform killed is indistinguishable from a workload that finished on its own -- and a killed
            // one would be read as a completed one. The observations it managed to produce are kept, because
            // what a workload said before it was stopped is still evidence; what changes is that the outcome
            // names the authority loss as the reason.
            //
            // Checked ahead of the drain, because an authority loss explains an incomplete drain rather than
            // being explained by it.
            Optional<SandboxFailure> failure = authority.lost()
                    ? Optional.of(SandboxFailure.SANDBOX_AUTHORITY_LOST)
                    : drained
                            // An incomplete drain means the observations are a partial view, and a partial
                            // view must not be read as evidence. Saying so here is what stops a check
                            // concluding a control was enforced from the absence of a line that simply had
                            // not arrived yet.
                            ? Optional.empty()
                            : Optional.of(SandboxFailure.SANDBOX_OBSERVE_FAILED);
            return new SandboxOutcome(
                    Optional.ofNullable(exitCode),
                    output.observations(),
                    output.truncated(),
                    output.retainedBytes(),
                    elapsed,
                    outOfMemory(containerId),
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            kill(containerId);
            return new SandboxOutcome(
                    Optional.empty(),
                    output.observations(),
                    output.truncated(),
                    output.retainedBytes(),
                    Duration.between(startedAt, Instant.now()),
                    false,
                    Optional.of(SandboxFailure.SANDBOX_OBSERVE_FAILED));
        } catch (DockerClientException deadline) {
            // docker-java signals a wait timeout with this type. Every other RuntimeException is a daemon or
            // transport fault, and mapping those to SANDBOX_TIMEOUT let an unreachable daemon at t=2s satisfy
            // a check that was supposed to demonstrate a 30-second deadline. The two are now distinct, because
            // "we stopped it" and "we lost contact with it" are not the same claim.
            kill(containerId);
            return new SandboxOutcome(
                    Optional.empty(),
                    output.observations(),
                    output.truncated(),
                    output.retainedBytes(),
                    Duration.between(startedAt, Instant.now()),
                    false,
                    Optional.of(SandboxFailure.SANDBOX_TIMEOUT));
        } catch (RuntimeException daemonFailure) {
            kill(containerId);
            return new SandboxOutcome(
                    Optional.empty(),
                    output.observations(),
                    output.truncated(),
                    output.retainedBytes(),
                    Duration.between(startedAt, Instant.now()),
                    false,
                    Optional.of(SandboxFailure.SANDBOX_OBSERVE_FAILED));
        }
    }

    /**
     * Whether the kernel killed this sandbox for exceeding its memory ceiling, asked of the daemon.
     *
     * <p>The probe cannot report this: under the real profile it is killed mid-allocation and never reaches the
     * line that would have said so. Reading an empty result as success is how the memory ceiling came to be
     * reported as demonstrated by runs that produced no evidence at all. The daemon knows, so ask it.
     */
    private boolean outOfMemory(String containerId) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(containerId).exec().getState().getOOMKilled());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void kill(String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
        } catch (RuntimeException alreadyGone) {
            // Killing something that already stopped is not a failure worth propagating.
        }
    }

    private void remove(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException failure) {
            // Reported, never swallowed: an accumulating pile of stopped sandboxes is how a host runs out of
            // disk, and the orphan reconciler needs to know this happens.
            throw new SandboxCleanupException(SandboxFailure.SANDBOX_CLEANUP_FAILED);
        }
    }

    private static SandboxFailure classify(RuntimeException failure) {
        if (failure instanceof SandboxCleanupException cleanup) {
            return cleanup.failure();
        }
        if (failure instanceof AuthorityLostException) {
            return SandboxFailure.SANDBOX_AUTHORITY_LOST;
        }
        if (failure instanceof SandboxRuntimeMismatchException) {
            return SandboxFailure.SANDBOX_RUNTIME_MISMATCH;
        }
        if (failure instanceof SandboxRuntimeUnavailableException) {
            // Reported as its own category. "This host has no such runtime" and "the image was wrong" send an
            // operator to entirely different places, and only one of them is a statement about the boundary.
            return SandboxFailure.SANDBOX_RUNTIME_UNAVAILABLE;
        }
        // Deliberately coarse. The daemon's own message can carry a socket path, a host directory, or an image
        // reference, none of which belongs in a result a caller or a metric can see.
        return SandboxFailure.SANDBOX_CREATE_FAILED;
    }

    /**
     * Signals that the authorized runtime could not be provided, without carrying the daemon's message.
     *
     * <p>A distinct type rather than a string match on the daemon's error. Matching on
     * {@code "unknown or invalid runtime name"} would be matching on another project's user-facing text, which
     * changes without notice — and the consequence of a missed match here is a security condition reported as
     * an ordinary launch failure.
     */
    public static final class SandboxRuntimeUnavailableException extends RuntimeException {
        SandboxRuntimeUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Signals that the daemon assigned a runtime other than the one the profile requires.
     *
     * <p>Separate from {@link SandboxRuntimeUnavailableException} so the two reach an operator as the
     * different findings they are — see {@link SandboxFailure#SANDBOX_RUNTIME_MISMATCH}.
     */
    public static final class SandboxRuntimeMismatchException extends RuntimeException {
        SandboxRuntimeMismatchException(String message) {
            super(message);
        }
    }

    /**
     * Signals that authority ended while the sandbox was being created.
     *
     * <p>Carries the decision so the outcome can name it. A generic launch failure here would report a
     * cancelled run as a broken host.
     */
    public static final class AuthorityLostException extends RuntimeException {
        private final com.kaas.runner.authority.AuthorityDecision decision;

        AuthorityLostException(com.kaas.runner.authority.AuthorityDecision decision) {
            super("Execution authority ended before the sandbox started.");
            this.decision = decision;
        }

        public com.kaas.runner.authority.AuthorityDecision decision() {
            return decision;
        }
    }

    /** Signals a cleanup failure without carrying the daemon's message with it. */
    public static final class SandboxCleanupException extends RuntimeException {
        private final SandboxFailure failure;

        SandboxCleanupException(SandboxFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public SandboxFailure failure() {
            return failure;
        }
    }

    /**
     * Collects the probe's key=value observations, stopping at the configured ceiling.
     *
     * <p>Untrusted output is attacker-controlled data, so it is bounded before it is anything else. Beyond the
     * limit the collector stops keeping bytes entirely rather than buffering and trimming, because the cost of
     * a flood has to be paid at the moment it arrives, not afterwards. This bounds what the launcher keeps;
     * the daemon's own copy on host disk is bounded separately, by the log configuration in {@code create}.
     *
     * <p>Every field is guarded, because the timeout path reads the observations after killing the container
     * without waiting for the stream to finish: the callback thread may still be delivering frames while the
     * launcher builds its outcome.
     */
    private static final class BoundedOutput extends ResultCallback.Adapter<Frame> {
        private final int maximumBytes;
        private final Map<String, String> observations = new LinkedHashMap<>();
        private final StringBuilder pending = new StringBuilder();
        private final AtomicBoolean truncated = new AtomicBoolean();
        private int bytes;

        private BoundedOutput(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public synchronized void onNext(Frame frame) {
            if (truncated.get()) {
                return;
            }
            byte[] payload = frame.getPayload();
            if (bytes + payload.length > maximumBytes) {
                truncated.set(true);
                return;
            }
            bytes += payload.length;
            pending.append(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            drain();
        }

        private void drain() {
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                record(pending.substring(0, newline));
                pending.delete(0, newline + 1);
            }
        }

        private void record(String line) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                return;
            }
            // Control characters are stripped here, at the boundary, rather than wherever this is eventually
            // rendered. Terminal escape sequences in untrusted output are an attack on whoever reads the logs.
            observations.put(sanitize(line.substring(0, equals)), sanitize(line.substring(equals + 1)));
        }

        private static String sanitize(String value) {
            StringBuilder safe = new StringBuilder(value.length());
            value.codePoints()
                    // Control characters go, and so do the format characters that survive them: a
                    // right-to-left override or a zero-width joiner in untrusted output can reorder how a
                    // whole evidence line reads to a human without changing a byte of its meaning. Stripping
                    // happens here, at the collector, before any other code sees the value.
                    .filter(codePoint -> !Character.isISOControl(codePoint))
                    .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                    .filter(codePoint -> Character.getType(codePoint) != Character.LINE_SEPARATOR)
                    .filter(codePoint -> Character.getType(codePoint) != Character.PARAGRAPH_SEPARATOR)
                    .forEach(safe::appendCodePoint);
            return safe.toString().trim();
        }

        private synchronized Map<String, String> observations() {
            drain();
            return Map.copyOf(observations);
        }

        private boolean truncated() {
            return truncated.get();
        }

        /**
         * How many bytes the collector actually kept.
         *
         * <p>Reported so the ceiling can be checked against what was retained rather than against the
         * collector's own claim that it truncated. A flag set while every byte was still being kept is a
         * flag, not a bound.
         */
        private synchronized int retainedBytes() {
            return bytes;
        }
    }

    private static final class WaitCallback
            extends com.github.dockerjava.api.command.WaitContainerResultCallback {}
}
