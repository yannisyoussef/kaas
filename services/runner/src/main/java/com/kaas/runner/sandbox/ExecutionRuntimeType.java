package com.kaas.runner.sandbox;

/**
 * Which container runtime a sandbox runs under. Platform-owned, and deliberately a closed set of two.
 *
 * <h2>Why this is an enum and not a string</h2>
 *
 * <p>A runtime name is the name of a program the daemon will execute. If any part of it came from a request,
 * a tenant, a profile field somebody could edit, or deployment configuration shaped like
 * {@code runtimeCommand=/whatever}, then choosing the runtime would be choosing what runs — which is a larger
 * privilege than anything else in this system. Two constants, selected by the platform, means there is no
 * string to inject.
 *
 * <h2>There is no ordering here, and no fallback</h2>
 *
 * <p>{@link #GVISOR} is not "{@link #DOCKER} but better", and the code must never treat it as a preference
 * that can be downgraded when unavailable. A sandbox authorized for {@code GVISOR} that ran under
 * {@code DOCKER} would be an execution whose security evidence describes a boundary it did not have — and the
 * failure would be silent, because everything else about the container would look identical.
 *
 * <p>So there is no {@code preferred}, no {@code fallbackTo}, and no comparison operator. The only question
 * anything asks is whether the runtime that was <em>observed</em> equals the runtime that was
 * <em>authorized</em>.
 */
public enum ExecutionRuntimeType {

    /**
     * Standard OCI runtime — {@code runc}. The historical baseline.
     *
     * <p>Hardened and extensively evidenced, and still <strong>not approved for hostile tenant content</strong>
     * under ADR-022: the container shares the host kernel, and a kernel bug is an escape. It remains correct
     * for the trusted synthetic workload and for the security probes, which is what it has always been used
     * for.
     */
    DOCKER("runc", "kaas.sandbox.v1"),

    /**
     * gVisor — {@code runsc}, a userspace application kernel between the workload and the host.
     *
     * <p>Materially reduces the host-kernel syscall surface reachable from inside the sandbox. It is
     * <strong>not</strong> a virtual machine and this enum will not describe it as one: the sentry is a
     * userspace process on the host kernel, itself seccomp-confined, so a sentry compromise is host-adjacent
     * rather than impossible.
     */
    GVISOR("runsc", "kaas.sandbox.gvisor.v1");

    private final String daemonRuntimeName;

    private final String profileVersion;

    ExecutionRuntimeType(String daemonRuntimeName, String profileVersion) {
        this.daemonRuntimeName = daemonRuntimeName;
        this.profileVersion = profileVersion;
    }

    /**
     * What the container runtime is called to the daemon, and what {@code docker inspect} reports back.
     *
     * <p>For {@link #DOCKER} this is {@code runc}, which is also the daemon's default — so a container that
     * requested nothing reports it too. That is why the name alone is never sufficient evidence of the
     * stronger runtime, and why {@link #inSandboxKernelMarker()} exists.
     */
    public String daemonRuntimeName() {
        return daemonRuntimeName;
    }

    /**
     * The security profile version this runtime's sandboxes run under.
     *
     * <p>Different runtimes get different profile versions because they are different boundaries. An
     * attestation binds a profile version, and one gathered under {@code runc} must not be able to vouch for a
     * sandbox under {@code runsc} — the controls may read the same and mean something different, which is the
     * whole reason the runtime evaluation had to check each one.
     */
    public String profileVersion() {
        return profileVersion;
    }

    /**
     * The suffix {@code uname -r} carries inside a sandbox under this runtime, or empty when nothing
     * distinctive can be observed from inside.
     *
     * <p>This is the difference between <em>requested</em> and <em>enforced</em>. The daemon reporting
     * {@code runsc} says the launcher asked for it. This says what is actually serving the workload's
     * syscalls: gVisor's guest kernel names itself, and a container cannot choose what {@code uname} says
     * about it.
     *
     * <h2>Why an identity suffix and not the emulated version number</h2>
     *
     * <p>This was originally the version prefix {@code 4.4.0}, measured from release-20240729. The release
     * this repository pins now reports {@code 4.19.0-gvisor}, so that marker would have silently stopped
     * matching — and a marker that stops matching is a mandatory control that starts failing for a reason
     * that has nothing to do with the boundary.
     *
     * <p>The emulated version is a number gVisor is free to bump; {@code -gvisor} is what the runtime calls
     * itself. Binding evidence to the identity rather than to the version is the difference between a control
     * that tracks the property and one that tracks a release note.
     *
     * <p>{@link #DOCKER} has no marker, and deliberately reports empty rather than something like
     * "not gVisor": the baseline runtime's identity is whatever kernel the host happens to run, which is not a
     * property this platform gets to assert.
     */
    public java.util.Optional<String> inSandboxKernelMarker() {
        return this == GVISOR ? java.util.Optional.of("-gvisor") : java.util.Optional.empty();
    }

    /**
     * Whether a kernel release observed from inside a sandbox is one this runtime serves.
     *
     * <p>The matching rule lives here rather than at the call site so that there is exactly one answer to
     * "does this evidence show mediation", and so a second mediating runtime cannot arrive with a subtly
     * different comparison.
     *
     * <p>A suffix, not a substring: {@code 6.1.0-gvisor-patched} is a host kernel someone named after the
     * runtime, not a kernel the runtime served. This cannot defend against an operator who names their own
     * host kernel to satisfy their own gate, and it is not meant to — the adversary here is the workload,
     * which cannot influence either value.
     */
    public boolean servesKernelRelease(String observedRelease) {
        return inSandboxKernelMarker().map(observedRelease::endsWith).orElse(false);
    }

    /**
     * Character devices this runtime <em>emulates</em>, which a sandbox under it will therefore see.
     *
     * <p>gVisor's guest presents {@code /dev/fuse} and {@code /dev/net/tun}. Neither is a host device: both are
     * served by the sentry's own VFS and netstack, and reaching either means talking to the userspace kernel
     * rather than to the host's. Under {@link #DOCKER} the same two names would mean a real host device was
     * passed in, which is exactly what {@code NO_HOST_DEVICES} exists to catch.
     *
     * <p>So the allowance is scoped to the runtime rather than added to the shared list. A global allowance
     * would silently stop catching a genuinely exposed {@code /dev/fuse} under the baseline runtime — the
     * check would keep its name and stop meaning what it says.
     *
     * <p>This is a real difference in surface, not a formality: it is two more interfaces reachable from
     * inside the sandbox, and they are recorded as a finding of the runtime evaluation rather than waved
     * through.
     */
    public java.util.Set<String> emulatedCharacterDevices() {
        return this == GVISOR ? java.util.Set.of("/dev/fuse", "/dev/net/tun") : java.util.Set.of();
    }

    /**
     * Whether a sandbox under this runtime can read the kernel's {@code NoNewPrivs} flag out of
     * {@code /proc/self/status}.
     *
     * <p>runc can. gVisor does not emit the line at all — the control is still in the OCI spec and still
     * applied, but the guest exposes no way to observe it, so the gate's evidence for it disappears while the
     * control name stays.
     *
     * <h2>Why the platform answers this and not the probe</h2>
     *
     * <p>The obvious implementation is "if the observation is missing, the runtime must not support it". That
     * hands the decision to whatever produced the observation: a workload that simply withheld the line would
     * downgrade a mandatory blocking control into a non-blocking one, which is evidence suppression rewarded
     * with a weaker gate. Answering it from the runtime constant instead means a missing flag under
     * {@link #DOCKER} stays a failure, and only a runtime the platform knows cannot report it is excused.
     *
     * <p>The excusal is not a pass. Where this is false the check reports {@code UNSUPPORTED} and says so —
     * see the runtime evaluation, which records the reduced mandatory set under the mediating runtime as a
     * finding rather than a footnote.
     */
    public boolean exposesNoNewPrivilegesFlag() {
        return this == DOCKER;
    }

    /**
     * Whether the runtime's own processes are counted against the sandbox's process ceiling.
     *
     * <p>gVisor's sentry is an ordinary host process living in the container's cgroup, so its threads consume
     * the same {@code pids.max} budget the workload draws on. A ceiling of 64 therefore does not leave 64
     * processes for the workload — measured on a GitHub-hosted runner, a fork loop under this runtime stopped
     * at 16–21 rather than 63, and <strong>raising the memory limit from 256m to 512m did not move it</strong>,
     * which is what rules out the memory cgroup as the thing that bound it.
     *
     * <p>The consequence for evidence: the baseline gate proves the ceiling is what stopped the loop by
     * requiring it to stop <em>at</em> the ceiling, specifically so that a run stopped by the memory cgroup
     * cannot satisfy it. That test cannot hold here, because stopping short of the ceiling is the correct
     * behaviour. What each assessment can still establish is that a bound exists and is at or below the
     * configured one; that the bound <em>tracks</em> the ceiling is proven separately, once, by
     * {@code StrongRuntimeBoundaryTests} moving the ceiling and requiring the stopping point to follow.
     *
     * <p>Recorded as a finding rather than smoothed over: this is a weaker per-assessment check than the
     * baseline's, and the sandbox gets fewer processes than its configuration says.
     */
    public boolean sharesProcessBudgetWithTheSandbox() {
        return this == GVISOR;
    }

    /** Whether this runtime is the one ADR-022 requires before hostile tenant content could be considered. */
    public boolean mediatesHostKernelSyscalls() {
        return this == GVISOR;
    }
}
