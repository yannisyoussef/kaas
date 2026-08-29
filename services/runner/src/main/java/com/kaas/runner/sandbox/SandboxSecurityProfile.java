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
        Map<String, String> environment) {

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
        if (!"none".equals(networkMode)) {
            // Deny-all is the baseline this slice is proving. A destination allowlist is a real product
            // requirement and gets its own policy model; approximating one here would mean claiming egress
            // control that has never been tested.
            throw new IllegalArgumentException("The synthetic sandbox has no network.");
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
    public static SandboxSecurityProfile version1(String imageReference) {
        return new SandboxSecurityProfile(
                "kaas.sandbox.v1",
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
                Duration.ofSeconds(30),
                64 * 1024,
                // What the daemon may write to host disk for this sandbox. Separate from the output ceiling
                // above, which bounds only what the launcher keeps in memory: the daemon's own copy is written
                // outside the container's cgroup, so nothing inside the sandbox accounts for it.
                8L * 1024 * 1024,
                // An explicit allowlist built from nothing, never the host environment with secrets removed
                // afterwards. Subtraction requires knowing every name worth removing, which nobody does.
                Map.of("KAAS_SANDBOX", "1", "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
    }
}
