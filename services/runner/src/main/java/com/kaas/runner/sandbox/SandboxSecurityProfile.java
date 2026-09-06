package com.kaas.runner.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The one execution policy this platform will run a sandbox under.
 *
 * <p>Immutable, server-owned, and versioned, so that "which policy produced this evidence" always has an
 * answer. Nothing here is negotiable by a caller, and there is deliberately no builder or setter: a profile
 * that can be adjusted at the call site is a profile that will be adjusted at the call site.
 *
 * <p>The fields are split by what can actually be <em>proven</em> on an ordinary host. Everything in the
 * mandatory set is enforced by the container runtime everywhere KaaS runs and is verified from inside the
 * sandbox by the probe. Hardening that varies by host — a custom seccomp profile, AppArmor, SELinux, user
 * namespaces, a rootless daemon — is deliberately absent from this record rather than declared and unenforced,
 * because a policy field the runtime ignores is a claim with no evidence behind it.
 */
public record SandboxSecurityProfile(
        String version,
        String imageReference,
        String runAsUser,
        boolean readOnlyRootFilesystem,
        boolean noNewPrivileges,
        List<String> droppedCapabilities,
        List<String> addedCapabilities,
        String networkMode,
        long memoryLimitBytes,
        long memorySwapLimitBytes,
        long cpuQuotaMicroseconds,
        long cpuPeriodMicroseconds,
        long pidsLimit,
        long temporaryFilesystemBytes,
        Duration wallClockTimeout,
        int maximumOutputBytes,
        long maximumLogBytes,
        Map<String, String> environment,
        /**
         * Which container runtime this sandbox runs under.
         *
         * <p>Part of the profile rather than a launch argument, for the same reason the image and the network
         * mode are: a caller that could choose the runtime would be choosing what confines the workload. The
         * profile version is derived from it, so an attestation gathered under one runtime cannot vouch for a
         * sandbox under another.
         */
        ExecutionRuntimeType runtime,
        /**
         * The host directory holding this execution's inert tenant source, or null when there is none.
         *
         * <p>A host path, and platform-owned in every part: the staging root is operator configuration and the
         * directory beneath it is an opaque identifier. No tenant byte contributes to it. It is mounted
         * read-only at a fixed container path that is likewise never derived from tenant input.
         *
         * <p>Null for every sandbox that carries no source, which is every probe this repository runs outside
         * a tenant assignment.
         */
        java.nio.file.Path sourceMount) {

    /** The only user a sandbox ever runs as: nobody, with no supplementary groups. */
    public static final String NOBODY = "65534:65534";

    /**
     * Every Linux capability is dropped and none is added back.
     *
     * <p>"ALL" is the runtime's own token for the complete set, which is safer than enumerating capabilities
     * here: an enumeration silently stops being complete the day the kernel gains a new one.
     */
    private static final List<String> DROP_ALL = List.of("ALL");

    public SandboxSecurityProfile {
        droppedCapabilities = List.copyOf(droppedCapabilities);
        addedCapabilities = List.copyOf(addedCapabilities);
        environment = Map.copyOf(environment);
        // EXACTLY TWO SHAPES, and everything else is refused.
        //
        //   "none"          no network at all — the DENY_ALL baseline
        //   "kaas-exec-..." one per-execution INTERNAL network created by this launcher
        //
        // The second exists so an allowlist can be enforced by topology: the sandbox's only reachable peer is
        // the proxy on that network. It is deliberately not "any network name the caller supplies" — that
        // string is the whole of the isolation, and accepting an arbitrary one would let a request place an
        // untrusted container on the bridge, the host network, or another execution's network.
        //
        // "host" and "bridge" are refused by this rule rather than by a denylist, because a denylist of unsafe
        // network names is a list that stops being complete.
        boolean denyAll = "none".equals(networkMode);
        boolean perExecution = networkMode != null && networkMode.startsWith(ExecutionNetwork.NAME_PREFIX);
        if (!denyAll && !perExecution) {
            throw new IllegalArgumentException(
                    "A sandbox runs with no network or on one per-execution internal network.");
        }
        if (!addedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("No capability is added back without a concrete requirement.");
        }
        if (!readOnlyRootFilesystem || !noNewPrivileges) {
            throw new IllegalArgumentException("Read-only root and no-new-privileges are not optional.");
        }
        if (NOBODY.equals(runAsUser) == false) {
            throw new IllegalArgumentException("The sandbox runs as nobody.");
        }
        if (memoryLimitBytes <= 0 || pidsLimit <= 0 || cpuQuotaMicroseconds <= 0
                || temporaryFilesystemBytes <= 0 || maximumOutputBytes <= 0 || maximumLogBytes <= 0) {
            throw new IllegalArgumentException("Every resource ceiling must be set.");
        }
        if (memorySwapLimitBytes != memoryLimitBytes) {
            // Swap must equal memory, which is how the runtime expresses "no swap". Leaving it unset lets a
            // workload evade the memory ceiling by swapping, and the limit becomes decorative.
            throw new IllegalArgumentException("Swap must be pinned to the memory limit so it cannot be evaded.");
        }
        if (!isContentAddressed(imageReference)) {
            // The launcher must run an image identified by its content, never by a name something else can
            // repoint. A tag resolves through whatever the daemon happens to hold, and a probe image that can
            // be substituted reports whatever its substitute likes -- which would invalidate every
            // behavioural claim the gate makes.
            throw new IllegalArgumentException(
                    "The sandbox image must be pinned by digest, not by a tag: " + imageReference);
        }
        if (wallClockTimeout.isNegative() || wallClockTimeout.isZero()
                || wallClockTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("The wall-clock deadline must be positive and bounded.");
        }
    }

    /**
     * Whether an image reference names content rather than a mutable pointer to it.
     *
     * <p>Three spellings are content addresses: a bare image id, the {@code sha256:}-prefixed form, and a
     * repository reference with a digest. The build returns the first of these, so accepting only the second
     * would have rejected the very image this launcher builds. What is rejected is a tag, which resolves
     * through whatever the daemon happens to hold at the time.
     */
    private static boolean isContentAddressed(String imageReference) {
        return isContentAddressedReference(imageReference);
    }

    /**
     * The same rule, reachable by the egress proxy's profile.
     *
     * <p>Shared rather than reimplemented: two copies of "what counts as a content address" is two chances to
     * get it wrong, and the one that is wrong is the one that accepts a tag.
     */
    public static boolean isContentAddressedReference(String imageReference) {
        String digest = imageReference.contains("@sha256:")
                ? imageReference.substring(imageReference.indexOf("@sha256:") + "@sha256:".length())
                : imageReference.startsWith("sha256:")
                        ? imageReference.substring("sha256:".length())
                        : imageReference;
        return digest.length() == 64 && digest.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /**
     * The profile the security gate runs under.
     *
     * @param imageReference the digest-pinned probe image; a tag would be a mutable pointer to executable code
     */
    /**
     * The same profile, attached to one per-execution internal network instead of no network at all.
     *
     * <p>Every other control is identical — same user, same dropped capabilities, same read-only root, same
     * ceilings. Only the network differs, and it differs to a network whose sole reachable peer is the trusted
     * proxy. Deriving it from {@link #version1} rather than restating the fields is deliberate: a second full
     * constructor call is a second place for a control to be quietly weakened, and the two would drift.
     *
     * <p>The version string changes with it, because the attestation binds a profile version and a sandbox on a
     * network is not the same security posture as one with no network. Reusing {@code kaas.sandbox.v1} here
     * would let an attestation gathered against an airgapped sandbox vouch for a networked one.
     */
    public static SandboxSecurityProfile version1OnNetwork(String imageReference, String networkName) {
        return version1OnNetwork(imageReference, networkName, Map.of());
    }

    /**
     * What a base profile is called once a sandbox running under it is on a network.
     *
     * <p>Derived rather than written down twice, and public because the execution loop has to check the
     * relationship rather than assume it. A command is authorized under the base profile the deployment's
     * attestation names; an ALLOWLIST execution then runs under the networked derivative of <em>that</em>
     * profile, and the loop refuses if what the launcher holds is not the derivative of what the command
     * authorized. Without that check, a launcher configured against some other profile entirely would run the
     * execution anyway and the evidence would name a policy that did not produce it.
     */
    public static String networkedVersionOf(String baseVersion) {
        return baseVersion + NETWORKED_SUFFIX;
    }

    /**
     * The base profile version for one runtime.
     *
     * <p>Two runtimes are two boundaries, so they are two profile versions. Several mandatory controls read
     * identically under both and mean something different — {@code KERNEL_PATHS_MASKED} is a bind mount over
     * {@code /dev/null} under {@code runc} and an unimplemented path under gVisor — so an assessment gathered
     * under one must not be able to satisfy an execution under the other. Deriving the version from the
     * runtime is what makes that impossible rather than merely discouraged.
     */
    public static String versionFor(ExecutionRuntimeType runtime) {
        return runtime.profileVersion();
    }

    /** What distinguishes the networked derivative from its base. One place, so the two cannot drift. */
    private static final String NETWORKED_SUFFIX = "-internal";

    /**
     * The networked profile, with the egress material the workload needs to reach its proxy.
     *
     * <p>The extra environment is <em>added to</em> the base profile's own allowlist rather than replacing it,
     * so nothing here can remove PATH or the sandbox marker, and it is a map the launcher builds from the
     * execution's policy — never anything a tenant wrote.
     *
     * <p>The capability token travels here, in the sandbox's environment, and that is deliberate. Anything
     * delivered into a sandbox must be assumed readable by whatever runs there, so the credential's protection
     * is not secrecy from the workload but the narrowness of what it authorizes: one execution, one assignment
     * epoch, one policy, briefly. It is kept out of labels, digests, and durable stores for the different
     * reason that those outlive the execution and are readable by things that are not it.
     */
    public static SandboxSecurityProfile version1OnNetwork(
            String imageReference, String networkName, Map<String, String> egressEnvironment) {
        return version1OnNetwork(
                imageReference, networkName, egressEnvironment, ExecutionRuntimeType.DOCKER);
    }

    /** The networked profile under a named runtime. Same controls, same peer, different boundary. */
    public static SandboxSecurityProfile version1OnNetwork(
            String imageReference,
            String networkName,
            Map<String, String> egressEnvironment,
            ExecutionRuntimeType runtime) {
        SandboxSecurityProfile base = version1(imageReference, runtime);
        if (networkName == null || !networkName.startsWith(ExecutionNetwork.NAME_PREFIX)) {
            throw new IllegalArgumentException("A networked sandbox joins a per-execution internal network.");
        }
        Map<String, String> environment = new java.util.HashMap<>(base.environment());
        environment.putAll(egressEnvironment);
        return new SandboxSecurityProfile(
                networkedVersionOf(base.version()),
                base.imageReference(),
                base.runAsUser(),
                base.readOnlyRootFilesystem(),
                base.noNewPrivileges(),
                base.droppedCapabilities(),
                base.addedCapabilities(),
                networkName,
                base.memoryLimitBytes(),
                base.memorySwapLimitBytes(),
                base.cpuQuotaMicroseconds(),
                base.cpuPeriodMicroseconds(),
                base.pidsLimit(),
                base.temporaryFilesystemBytes(),
                base.wallClockTimeout(),
                base.maximumOutputBytes(),
                base.maximumLogBytes(),
                Map.copyOf(environment),
                // The runtime is carried through unchanged. A networked derivative is the SAME boundary with a
                // peer added; deriving it from a different runtime would silently be a different boundary.
                base.runtime(),
                // And so is the source mount, for the same reason. Previous slices found the networked
                // derivative losing a property the deny-all profile had; a source mount lost here would mean
                // an allowlist execution ran with no source rather than failing.
                base.sourceMount());
    }

    /** The baseline profile, under the standard OCI runtime. */
    public static SandboxSecurityProfile version1(String imageReference) {
        return version1(imageReference, ExecutionRuntimeType.DOCKER);
    }

    /**
     * The same controls, under a named runtime.
     *
     * <p>Every ceiling, mount, capability and user below is identical whichever runtime is chosen — the
     * profile describes what the platform demands, and the runtime describes what enforces it. What differs is
     * the version string, because the same control can be enforced by different mechanisms and observed
     * through different evidence, and an assessment must not be transferable between them.
     */
    /**
     * The same profile, carrying one execution's inert tenant source.
     *
     * <p>Derived rather than constructed, so a source-bearing sandbox differs from an ordinary one in exactly
     * one component and cannot quietly differ in another. The version string is unchanged: the security
     * profile is the same profile, and the evidence gathered under it describes the same boundary.
     *
     * @param sourceMount a platform-owned host directory. Never derived from tenant input.
     */
    public static SandboxSecurityProfile withSource(
            SandboxSecurityProfile base, java.nio.file.Path sourceMount) {
        java.util.Objects.requireNonNull(sourceMount, "A source-bearing profile names its staging directory.");
        return new SandboxSecurityProfile(
                base.version(),
                base.imageReference(),
                base.runAsUser(),
                base.readOnlyRootFilesystem(),
                base.noNewPrivileges(),
                base.droppedCapabilities(),
                base.addedCapabilities(),
                base.networkMode(),
                base.memoryLimitBytes(),
                base.memorySwapLimitBytes(),
                base.cpuQuotaMicroseconds(),
                base.cpuPeriodMicroseconds(),
                base.pidsLimit(),
                base.temporaryFilesystemBytes(),
                base.wallClockTimeout(),
                base.maximumOutputBytes(),
                base.maximumLogBytes(),
                base.environment(),
                base.runtime(),
                sourceMount);
    }

    public static SandboxSecurityProfile version1(
            String imageReference, ExecutionRuntimeType runtime) {
        return new SandboxSecurityProfile(
                versionFor(runtime),
                imageReference,
                NOBODY,
                true,
                true,
                DROP_ALL,
                List.of(),
                "none",
                256L * 1024 * 1024,
                256L * 1024 * 1024,
                50_000,
                100_000,
                64,
                16L * 1024 * 1024,
                // Scaled to what the runtime needs, not a constant. See ExecutionRuntimeType#scaleDeadline:
                // a deadline calibrated for the baseline killed the memory probe under the mediating runtime
                // before it could report the ceiling that had already bounded it.
                runtime.scaleDeadline(Duration.ofSeconds(30)),
                64 * 1024,
                // What the daemon may write to host disk for this sandbox. Separate from the output ceiling
                // above, which bounds only what the launcher keeps in memory: the daemon's own copy is written
                // outside the container's cgroup, so nothing inside the sandbox accounts for it.
                8L * 1024 * 1024,
                // An explicit allowlist built from nothing, never the host environment with secrets removed
                // afterwards. Subtraction requires knowing every name worth removing, which nobody does.
                Map.of("KAAS_SANDBOX", "1", "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"),
                runtime,
                // No source. Every profile that carries one is derived from this, so a sandbox with tenant
                // bytes is always the explicit case rather than the default.
                null);
    }
}
