package com.kaas.runner.gate;

import com.kaas.runner.gate.SecurityCheck.Enforcement;
import com.kaas.runner.gate.SecurityCheck.Verdict;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the trusted probes and turns what they observed into a verdict per control.
 *
 * <p>Every check here is behavioural. None of them inspects the launcher's own configuration and calls that
 * evidence, because configuration is a statement of intent and the question this gate answers is whether the
 * runtime actually did it. A control that can only be evidenced by reading back the setting that requested it
 * is reported as unsupported.
 *
 * <p>Two rules govern every check below, and both exist because this gate previously broke them.
 *
 * <p><strong>Absent evidence is never a pass.</strong> Five mandatory controls once reported success when the
 * probe produced nothing at all — an image that would not start, a daemon that went away, a drain that never
 * finished. A check that reads a missing line as confirmation certifies runs that never happened, which is
 * worse than having no check, because it looks like one.
 *
 * <p><strong>Surfaces are enumerated, never named.</strong> Asking whether {@code /host} exists finds a mount
 * at {@code /host} and nothing else. A bind at {@code /mnt/hostdata}, a socket at {@code /run/docker.sock},
 * and the host's root disk attached as {@code /dev/loop0} all passed this gate while the sandbox held the
 * host. Each check now compares the whole observed set against what the profile permits.
 */
public final class HostileExecutionSecurityGate {
    /** A capability mask of all zeroes: the kernel's own report that nothing is permitted. */
    private static final String NO_CAPABILITIES = "0000000000000000";

    /**
     * What may legitimately appear in the sandbox's environment.
     *
     * <p>Two of these come from the profile's allowlist. The rest — {@code HOME}, {@code HOSTNAME},
     * {@code PWD}, {@code SHLVL} — are manufactured by the container runtime and the shell itself, not
     * inherited from the launcher's process. They are named explicitly rather than tolerated by a wildcard, so
     * that anything genuinely inherited still fails this check.
     */
    private static final Set<String> ALLOWED_ENVIRONMENT_NAMES =
            Set.of("KAAS_SANDBOX", "PATH", "HOME", "HOSTNAME", "PWD", "SHLVL");

    /**
     * Where the runtime is allowed to have mounted anything.
     *
     * <p>Matched by prefix rather than as an exact set, because the pseudo-filesystem layout differs across
     * hosts and cgroup versions and a gate that fails on an irrelevant difference gets switched off. What it
     * does catch is the thing that matters: every host path a bind mount would land on — {@code /mnt},
     * {@code /run}, {@code /secrets}, {@code /opt}, {@code /host}, {@code /workspace} — is outside it.
     */
    private static final List<String> ALLOWED_MOUNT_PREFIXES =
            List.of("/proc", "/sys", "/dev", "/tmp", "/etc/hosts", "/etc/hostname", "/etc/resolv.conf");

    /**
     * Paths the runtime overmounts to keep the kernel's own internals out of the sandbox.
     *
     * <p>Their presence is positive evidence that masking was applied. A daemon started with
     * {@code systempaths=unconfined} exposes all of them and produces an otherwise identical assessment, so
     * without this the sandbox's most sensitive readable surface is the one thing nothing checks.
     */
    private static final List<String> REQUIRED_MASKED_PATHS =
            List.of("/proc/kcore", "/proc/keys", "/proc/timer_list", "/proc/scsi", "/sys/firmware");

    /** Character devices a minimal sandbox has a reason to see. Anything else is a surface nobody asked for. */
    private static final Set<String> ALLOWED_CHARACTER_DEVICES = Set.of(
            "/dev/full", "/dev/null", "/dev/random", "/dev/tty", "/dev/urandom", "/dev/zero",
            "/dev/console", "/dev/ptmx", "/dev/pts/ptmx");

    private final SandboxLauncher launcher;
    private final String runtime;

    public HostileExecutionSecurityGate(SandboxLauncher launcher, String runtime) {
        this.launcher = launcher;
        this.runtime = runtime;
    }

    public HostileExecutionAssessment assess() {
        List<SecurityCheck> checks = new ArrayList<>();
        SandboxOutcome inspect = run(SyntheticProbe.INSPECT);
        checks.addAll(identityChecks(inspect));
        checks.addAll(filesystemChecks(inspect));
        checks.addAll(privilegeChecks(inspect));
        checks.addAll(environmentChecks(inspect));
        checks.add(networkCheck(run(SyntheticProbe.NETWORK)));
        checks.add(processCheck(run(SyntheticProbe.PROCESSES)));
        checks.add(memoryCheck(run(SyntheticProbe.MEMORY)));
        checks.add(timeoutCheck(run(SyntheticProbe.SLEEP)));
        checks.add(outputCheck(run(SyntheticProbe.OUTPUT)));
        checks.add(runtimeCheck(inspect));
        return new HostileExecutionAssessment(
                launcher.profile().version(), runtime, Instant.now(), checks);
    }

    private SandboxOutcome run(SyntheticProbe probe) {
        return launcher.run(
                new SandboxLaunchRequest(probe, launcher.profile().version(), UUID.randomUUID()));
    }

    /**
     * Whether this outcome may be reasoned about at all.
     *
     * <p>Applied before every mandatory verdict. It covers both the launcher losing its view of the sandbox
     * and the probe failing to report that the applets its evidence depends on exist — a missing {@code nc} or
     * {@code ip} is indistinguishable from a denied operation at the exit code, so a base-image change could
     * otherwise turn a whole family of checks into unconditional passes.
     */
    private static boolean usable(SandboxOutcome outcome) {
        return outcome.evidenceIsComplete()
                && outcome.observation("probe_tooling").map("present"::equals).orElse(false);
    }

    private SecurityCheck mandatory(SandboxOutcome outcome, String control, boolean satisfied, String evidence) {
        if (!usable(outcome)) {
            return check(control, Enforcement.MANDATORY, Verdict.FAIL,
                    "evidence unusable: failure=" + outcome.failure().map(Enum::name).orElse("none")
                            + " tooling=" + outcome.observation("probe_tooling").orElse("unreported"));
        }
        return check(control, Enforcement.MANDATORY, satisfied ? Verdict.PASS : Verdict.FAIL, evidence);
    }

    private List<SecurityCheck> identityChecks(SandboxOutcome outcome) {
        // Read from the kernel by the process itself, not from the image's USER directive, which a launcher
        // can override, and not from the launcher's own setting, which is the thing under test.
        String uid = outcome.observation("uid").orElse(null);
        String gid = outcome.observation("gid").orElse(null);
        String expected = launcher.profile().runAsUser();
        // Compared against the profile rather than merely tested for "not zero". A uid of "" or "nobody" is
        // not root either, and both used to pass.
        String expectedUid = expected.substring(0, expected.indexOf(':'));
        String expectedGid = expected.substring(expected.indexOf(':') + 1);
        return List.of(
                mandatory(outcome, "NON_ROOT_UID", expectedUid.equals(uid) && !"0".equals(uid), "uid=" + uid),
                mandatory(outcome, "NON_ROOT_GID", expectedGid.equals(gid) && !"0".equals(gid), "gid=" + gid),
                // Without a user namespace the container's uid *is* the host's uid. Reported rather than
                // asserted, so a deployment can see what its non-root claim is actually worth.
                check(
                        "USER_NAMESPACE",
                        Enforcement.DEPLOYMENT_SPECIFIC,
                        outcome.observation("uid_map")
                                .map(map -> map.startsWith("0:0:") ? Verdict.FAIL : Verdict.PASS)
                                .orElse(Verdict.UNSUPPORTED),
                        "uid_map=" + outcome.observation("uid_map").orElse("unreported")));
    }

    private List<SecurityCheck> filesystemChecks(SandboxOutcome outcome) {
        Set<String> mounts = outcome.observedSet("mount_points");
        Set<String> writable = outcome.observedSet("mount_writable");
        Set<String> sockets = outcome.observedSet("unix_sockets");
        Set<String> blockDevices = outcome.observedSet("block_device_nodes");
        Set<String> charDevices = outcome.observedSet("char_device_nodes");

        List<String> unexpectedMounts = mounts.stream().filter(path -> !allowedMount(path)).sorted().toList();
        List<String> unexpectedWritable =
                writable.stream().filter(path -> !allowedMount(path)).sorted().toList();
        // Masked or absent, and PROVABLY one of the two.
        //
        // runc mounts over these paths, so they show up in the mount table. gVisor never implemented them, so
        // they do not exist at all -- which is at least as strong, and produces none of the same evidence.
        // Requiring the mount would fail a sandbox that is stricter than the one that passes.
        //
        // The trap is that "not in the mount table and not in the present list" is also what a probe that
        // never looked produces. So the probe reports the list it examined, and a required path missing from
        // THAT list fails: absent evidence is not a pass.
        Set<String> kernelPathsExamined = outcome.observedSet("kernel_paths_checked");
        Set<String> kernelPathsPresent = outcome.observedSet("kernel_paths_present");
        List<String> unexaminedPaths = REQUIRED_MASKED_PATHS.stream()
                .filter(path -> !kernelPathsExamined.contains(path))
                .toList();
        // Present, not overmounted, AND carrying something. The third condition is what separates a runtime
        // that never implemented the path (an empty synthetic directory, exposing nothing) from a daemon
        // started with systempaths=unconfined (the real thing, readable) -- which is the case this check
        // exists to catch and still fails.
        Set<String> kernelPathsNonEmpty = outcome.observedSet("kernel_paths_nonempty");
        List<String> exposedKernelPaths = REQUIRED_MASKED_PATHS.stream()
                .filter(kernelPathsPresent::contains)
                .filter(path -> !mounts.contains(path))
                .filter(kernelPathsNonEmpty::contains)
                .toList();
        // The baseline list plus whatever THIS runtime emulates. Scoped to the runtime on purpose: see
        // ExecutionRuntimeType#emulatedCharacterDevices.
        Set<String> allowedCharDevices = new java.util.HashSet<>(ALLOWED_CHARACTER_DEVICES);
        allowedCharDevices.addAll(launcher.profile().runtime().emulatedCharacterDevices());
        List<String> unexpectedCharDevices =
                charDevices.stream().filter(path -> !allowedCharDevices.contains(path)).sorted().toList();

        // The read-only claim rests on writing to a directory this uid owns: writing to "/" would be refused by
        // ordinary permissions even on a writable filesystem. That ownership lives in one Dockerfile line, so
        // the gate refuses to trust the result unless the probe confirms the precondition still holds.
        String owner = outcome.observation("probe_owned_owner").orElse("unreported");
        boolean ownershipHolds = owner.equals(outcome.observation("uid").orElse(null));

        return List.of(
                mandatory(
                        outcome,
                        "READ_ONLY_ROOT",
                        ownershipHolds && "false".equals(outcome.observation("rootfs_writable").orElse(null)),
                        "rootfs_writable=" + outcome.observation("rootfs_writable").orElse("unreported")
                                + " probe_owned_owner=" + owner),
                // The approved location has to work, or the boundary is merely broken rather than secure.
                mandatory(
                        outcome,
                        "WRITABLE_TMPFS",
                        "true".equals(outcome.observation("tmp_writable").orElse(null)) && mounts.contains("/tmp"),
                        "tmp_writable=" + outcome.observation("tmp_writable").orElse("unreported")),
                // Any socket anywhere, not one hardcoded path. The daemon socket was previously looked for at
                // /var/run/docker.sock only — a directory that does not exist in this image — so mounting it at
                // /run/docker.sock left the check reporting "absent" while the sandbox held the host.
                mandatory(outcome, "NO_DOCKER_SOCKET", sockets.isEmpty(), "unix_sockets=" + sockets),
                mandatory(
                        outcome,
                        "NO_HOST_MOUNTS",
                        unexpectedMounts.isEmpty() && unexpectedWritable.isEmpty(),
                        "unexpected_mounts=" + unexpectedMounts + " unexpected_writable=" + unexpectedWritable),
                mandatory(
                        outcome,
                        "NO_HOST_DEVICES",
                        blockDevices.isEmpty() && unexpectedCharDevices.isEmpty(),
                        "block_devices=" + blockDevices + " unexpected_char_devices=" + unexpectedCharDevices),
                mandatory(
                        outcome,
                        "KERNEL_PATHS_MASKED",
                        unexaminedPaths.isEmpty() && exposedKernelPaths.isEmpty(),
                        "exposed=" + exposedKernelPaths + " unexamined=" + unexaminedPaths
                                + " present=" + kernelPathsPresent.stream().sorted().toList()));
    }

    private static boolean allowedMount(String path) {
        return "/".equals(path)
                || ALLOWED_MOUNT_PREFIXES.stream()
                        .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private List<SecurityCheck> privilegeChecks(SandboxOutcome outcome) {
        // All five sets, not the two that used to be checked. A bounding set of zero does not by itself imply
        // a permitted set of zero: post-exec permitted includes P(inheritable) & F(inheritable), which the
        // bounding set does not mask.
        List<String> capabilitySets = List.of("cap_eff", "cap_prm", "cap_bnd", "cap_inh", "cap_amb");
        boolean allZero = capabilitySets.stream()
                .allMatch(key -> outcome.observation(key).map(NO_CAPABILITIES::equals).orElse(false));
        String capabilityEvidence = capabilitySets.stream()
                .map(key -> key + "=" + outcome.observation(key).orElse("unreported"))
                .reduce((left, right) -> left + " " + right)
                .orElse("none");
        String noNewPrivs = outcome.observation("no_new_privs").orElse(null);
        Set<String> setuidBinaries = outcome.observedSet("setuid_binaries");
        String seccomp = outcome.observation("seccomp").orElse(null);
        return List.of(
                mandatory(outcome, "CAPABILITIES_DROPPED", allZero, capabilityEvidence),
                noNewPrivilegesCheck(outcome, noNewPrivs),
                // The escalation path itself, observed rather than inferred.
                //
                // no-new-privileges stops the kernel performing a privilege transition; this establishes there
                // is nothing in the sandbox to transition through. It is the one of the two that reads the
                // same under every runtime, which is why it is mandatory everywhere while the flag check is
                // not — see ExecutionRuntimeType#exposesNoNewPrivilegesFlag.
                mandatory(
                        outcome,
                        "NO_SETUID_BINARIES",
                        outcome.observation("setuid_binaries").isPresent() && setuidBinaries.isEmpty(),
                        "setuid_binaries=" + outcome.observation("setuid_binaries").orElse("unreported")),
                // Seccomp filtering is on by default on most daemons and absent on some. Reported honestly as
                // deployment-specific rather than claimed: a control we cannot demonstrate everywhere is not
                // part of the mandatory baseline, and pretending otherwise would make the baseline a fiction.
                //
                // Mode 2 says a filter is loaded. It does not say which filter, and the launcher pins none, so
                // a permissive host profile is indistinguishable from the runtime's default.
                check(
                        "SECCOMP_FILTER",
                        Enforcement.DEPLOYMENT_SPECIFIC,
                        seccomp == null ? Verdict.UNSUPPORTED : ("0".equals(seccomp) ? Verdict.FAIL : Verdict.PASS),
                        "seccomp_mode=" + seccomp + " (a filter is loaded; its contents are not verified)"));
    }

    private List<SecurityCheck> environmentChecks(SandboxOutcome outcome) {
        // The sandbox's environment is built from nothing, so the assertion is about what is *present*, not
        // about which known-sensitive names were successfully removed.
        Set<String> names = outcome.observedSet("env_names");
        boolean onlyAllowlisted = !names.isEmpty() && ALLOWED_ENVIRONMENT_NAMES.containsAll(names);
        return List.of(mandatory(
                outcome,
                "MINIMAL_ENVIRONMENT",
                onlyAllowlisted,
                "env_names=" + names.stream().sorted().toList()));
    }

    private SecurityCheck networkCheck(SandboxOutcome outcome) {
        // Positive evidence that no interface exists, which is the only thing that distinguishes "no network"
        // from "a network with nothing routable on it". Reachability alone cannot: on an egress-filtered host
        // a fully attached container reports every destination unreachable, and a sandbox with a routable
        // address can still reach its neighbours. Measured 0/0/0 isolated against 1/2/1 bridged.
        List<String> interfaceKeys =
                List.of("net_global_addresses", "net_default_routes", "net_interfaces_up");
        boolean noInterfaces = interfaceKeys.stream()
                .allMatch(key -> outcome.observation(key).map("0"::equals).orElse(false));

        // Corroboration, not proof. Loopback is deliberately not among them: --network none leaves a fully
        // working lo, so a loopback attempt can only ever report that nothing is listening.
        List<String> destinations = List.of(
                "net_public", "net_private", "net_metadata", "net_link_local",
                "net_metadata_v6", "net_docker_host", "net_gateway");
        boolean allDenied = destinations.stream()
                .allMatch(key -> outcome.observation(key).map("unreachable"::equals).orElse(false));
        boolean dnsDenied = outcome.observation("net_dns").map("unresolvable"::equals).orElse(false);

        String evidence = interfaceKeys.stream()
                .map(key -> key + "=" + outcome.observation(key).orElse("unreported"))
                .reduce((left, right) -> left + " " + right)
                .orElse("none")
                + " destinations_denied=" + allDenied
                + " net_dns=" + outcome.observation("net_dns").orElse("unreported");
        return mandatory(outcome, "NETWORK_DENIED", noInterfaces && allDenied && dnsDenied, evidence);
    }

    private SecurityCheck processCheck(SandboxOutcome outcome) {
        // The probe asks for more processes than the ceiling allows and reports its progress as it goes. Three
        // things together are the evidence: it got fewer than it asked for, it never reached the marker it
        // prints after the loop, and it stopped at roughly the configured ceiling rather than somewhere else.
        // The marker matters because the shell is killed outright when it cannot fork, so "stopped early" and
        // "finished quietly" would otherwise look identical. The ceiling comparison matters because a run
        // stopped by the *memory* cgroup at 42 processes used to satisfy this check just as well.
        int started = intObservation(outcome, "processes_started");
        int requested = intObservation(outcome, "processes_requested");
        long ceiling = launcher.profile().pidsLimit();
        boolean completed = outcome.observation("processes_loop_completed").isPresent();
        boolean stoppedAtCeiling = started > 0 && started <= ceiling && started >= ceiling - 4;
        // Under a runtime whose own threads draw on the same budget, stopping short of the ceiling is the
        // correct behaviour rather than a symptom, so the ceiling comparison is "at or below" instead of
        // "at". See ExecutionRuntimeType#sharesProcessBudgetWithTheSandbox for what is given up and what
        // proves it instead.
        boolean boundedBelowCeiling = started > 0 && started <= ceiling;
        boolean ceilingHeld = launcher.profile().runtime().sharesProcessBudgetWithTheSandbox()
                ? boundedBelowCeiling
                : stoppedAtCeiling;
        boolean bounded = requested > 0 && started < requested && !completed && ceilingHeld;
        return mandatory(
                outcome,
                "PID_LIMIT",
                bounded,
                "processes_started=" + started + " processes_requested=" + requested
                        + " pids_limit=" + ceiling + " loop_completed=" + completed
                        + " runtime_shares_budget="
                        + launcher.profile().runtime().sharesProcessBudgetWithTheSandbox());
    }

    private SecurityCheck memoryCheck(SandboxOutcome outcome) {
        // The kernel killing this sandbox is the expected outcome, and the daemon is asked whether it did.
        //
        // The probe cannot be the witness here: under the real profile it is OOM-killed mid-allocation and
        // never reaches the line that would have reported the ceiling working. This check previously treated
        // that silence as success — and treated the silence of a sandbox that never started as success too,
        // because a missing observation and a stopped allocation were the same value.
        int allocated = intObservation(outcome, "memory_allocated_mb");
        int requested = intObservation(outcome, "memory_requested_mb");
        boolean probeStarted = requested > 0;
        boolean stoppedShort = allocated >= 0 && allocated < requested;
        boolean loopCompleted = outcome.observation("memory_loop_completed").isPresent();
        boolean constrained = probeStarted && !loopCompleted && (outcome.outOfMemory() || stoppedShort);
        return mandatory(
                outcome,
                "MEMORY_LIMIT",
                constrained,
                "memory_allocated_mb=" + allocated + " memory_requested_mb=" + requested
                        + " oom_killed=" + outcome.outOfMemory() + " exit=" + outcome.exitCode().orElse(null));
    }

    private SecurityCheck timeoutCheck(SandboxOutcome outcome) {
        // The probe sleeps for an hour. Four things are required, and the first two are new: the probe must
        // have reported that it actually started sleeping, and the run must have lasted at least as long as
        // the deadline. Without them a daemon fault two seconds in satisfied every remaining condition, and a
        // launcher that waited on nothing at all passed this check in 0.164 seconds.
        boolean probeStarted = outcome.observation("sleeping").isPresent();
        boolean ranToDeadline =
                outcome.elapsed().compareTo(launcher.profile().wallClockTimeout()) >= 0;
        boolean withinTolerance = outcome.elapsed()
                .compareTo(launcher.profile().wallClockTimeout().plusSeconds(15)) < 0;
        boolean terminated = probeStarted
                && outcome.timedOut()
                && ranToDeadline
                && withinTolerance
                && outcome.observation("sleep_completed").isEmpty();
        return mandatory(
                outcome,
                "WALL_CLOCK_TIMEOUT",
                terminated,
                "sleeping=" + probeStarted + " timedOut=" + outcome.timedOut()
                        + " elapsed=" + outcome.elapsed());
    }

    private SecurityCheck outputCheck(SandboxOutcome outcome) {
        // Truncation is claimed by the collector, so it is corroborated with what the collector actually kept.
        // A flag set while every byte was still retained would otherwise satisfy this, which is exactly what
        // one mutation demonstrated: the launcher buffered 13 MB and the check stayed green.
        boolean started = outcome.observation("output_requested_lines").isPresent();
        boolean withinCeiling = outcome.retainedBytes() <= launcher.profile().maximumOutputBytes();
        return mandatory(
                outcome,
                "OUTPUT_BOUNDED",
                started && outcome.outputTruncated() && withinCeiling,
                "truncated=" + outcome.outputTruncated() + " retained_bytes=" + outcome.retainedBytes()
                        + " ceiling=" + launcher.profile().maximumOutputBytes());
    }

    /**
     * Whether the sandbox is really running under the runtime the profile demanded.
     *
     * <h2>Two observations, from two sides, on purpose</h2>
     *
     * <p>The launcher already read back {@code HostConfig.Runtime} from the daemon and refused to start
     * anything if it disagreed. That is the daemon answering a question about itself: it says which runtime
     * was <em>assigned</em>. This is the workload reporting what is actually underneath it.
     *
     * <p>A mediating runtime's guest kernel names itself, and a container cannot choose what {@code uname -r}
     * says about it. So a {@code runc} container cannot produce this marker unless the host kernel is
     * literally named after the runtime — which makes "requested runsc" and "running under runsc" two
     * separately falsifiable claims rather than the same claim counted twice.
     *
     * <p>For the baseline runtime there is no marker to look for: whatever kernel the host runs is not a
     * property this platform gets to assert, and inventing an assertion about it would be a control that
     * passes for reasons nobody chose. The check reports {@code UNSUPPORTED} there — not a pass, and not
     * required of the baseline either, because {@link Enforcement#DEPLOYMENT_SPECIFIC} controls never block.
     */
    /**
     * {@code NO_NEW_PRIVILEGES}, reported at the strength the runtime actually supports.
     *
     * <p>Under a runtime that exposes the kernel flag this is unchanged and unweakened: {@code 1} passes,
     * anything else fails, and a missing observation fails — evidence suppression must not buy a pass.
     *
     * <p>Under a runtime that does not expose it, there is no in-sandbox observation to make. The honest
     * report is {@code UNSUPPORTED}, not a pass: the control is still applied by the launcher and still in the
     * OCI spec, but "we asked for it" is the *requested* side of exactly the distinction this gate exists to
     * keep, and recording a request as though it were an observation is the failure mode that makes a gate
     * decorative.
     *
     * <p><strong>This means the mediating runtime carries one fewer demonstrable mandatory control than the
     * baseline.</strong> That is a real reduction, it is recorded as a finding in the runtime evaluation, and
     * {@code NO_SETUID_BINARIES} — which reads identically under both — is what covers the underlying
     * escalation path in the meantime.
     */
    private SecurityCheck noNewPrivilegesCheck(SandboxOutcome outcome, String noNewPrivs) {
        // Blank counts as not reported. The probe emits the key unconditionally and fills it from an awk over
        // /proc/self/status, so a runtime with no NoNewPrivs line yields an empty VALUE rather than a missing
        // key -- and testing for null alone silently put the mediating runtime back on the mandatory path,
        // where it failed on the empty string it was never going to be able to fill.
        boolean reported = noNewPrivs != null && !noNewPrivs.isBlank();
        if (!launcher.profile().runtime().exposesNoNewPrivilegesFlag() && !reported) {
            return check(
                    "NO_NEW_PRIVILEGES",
                    Enforcement.DEPLOYMENT_SPECIFIC,
                    Verdict.UNSUPPORTED,
                    "this runtime does not expose NoNewPrivs; the control is applied but not observable "
                            + "from inside, and NO_SETUID_BINARIES covers the escalation path it closes");
        }
        // "1" passes, anything else fails, and nothing at all fails. This previously passed on any non-null
        // value, so no_new_privs=0 — the kernel reporting the control switched off — was read as the control
        // being enforced.
        return mandatory(outcome, "NO_NEW_PRIVILEGES", "1".equals(noNewPrivs), "no_new_privs=" + noNewPrivs);
    }

    private SecurityCheck runtimeCheck(SandboxOutcome inspect) {
        ExecutionRuntimeType expected = launcher.profile().runtime();
        String observed = inspect.observation("runtime_kernel_release").orElse("");
        if (!inspect.evidenceIsComplete() || observed.isEmpty()) {
            // Unusable evidence is not a pass. A probe that could not report its kernel tells us nothing
            // about which kernel served it.
            return check(
                    RUNTIME_MEDIATION_CONTROL,
                    expected.mediatesHostKernelSyscalls() ? Enforcement.MANDATORY : Enforcement.DEPLOYMENT_SPECIFIC,
                    Verdict.FAIL,
                    "the sandbox did not report which kernel served it");
        }
        return expected
                .inSandboxKernelMarker()
                .map(marker -> check(
                        RUNTIME_MEDIATION_CONTROL,
                        Enforcement.MANDATORY,
                        // The runtime owns the comparison. Doing it here would put a second, drifting copy of
                        // "what counts as mediation" in the one place that must not disagree with the first.
                        expected.servesKernelRelease(observed) ? Verdict.PASS : Verdict.FAIL,
                        expected.servesKernelRelease(observed)
                                ? "the sandbox reports the mediating runtime's own kernel: " + observed
                                : "the sandbox reports a kernel this runtime does not serve: " + observed))
                .orElseGet(() -> check(
                        RUNTIME_MEDIATION_CONTROL,
                        Enforcement.DEPLOYMENT_SPECIFIC,
                        Verdict.UNSUPPORTED,
                        "the baseline runtime asserts no kernel identity of its own"));
    }

    /**
     * The control naming the property a stronger runtime exists to provide.
     *
     * <p>Named for what is observed rather than for the product that provides it. A control called
     * {@code RUNTIME_GVISOR} would have to be renamed the day a second mediating runtime is supported, and
     * every attestation in existence would stop satisfying the coverage rule for a reason that has nothing to
     * do with security.
     */
    public static final String RUNTIME_MEDIATION_CONTROL = "HOST_KERNEL_SYSCALL_MEDIATION";

    private SecurityCheck check(String control, Enforcement enforcement, Verdict verdict, String evidence) {
        return new SecurityCheck(control, verdict, enforcement, evidence);
    }

    private static int intObservation(SandboxOutcome outcome, String key) {
        return outcome.observation(key).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException notANumber) {
                return -1;
            }
        }).orElse(-1);
    }
}
