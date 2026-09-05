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

    /** Whether this runtime is the one ADR-022 requires before hostile tenant content could be considered. */
    public boolean mediatesHostKernelSyscalls() {
        return this == GVISOR;
    }
}
