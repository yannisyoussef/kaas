package com.kaas.api.execution.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Evidence that a deployment's sandbox actually enforces the controls this platform requires.
 *
 * <p>This is the bridge between two modules that deliberately cannot see each other. The hostile-execution gate
 * lives in {@code services/runner}, which holds container-runtime access; the control plane is build-guarded
 * against ever depending on it, because a component with daemon access is a component with the host. So the
 * gate's verdict reaches the control plane as a document rather than a method call, and this record is what a
 * verified document becomes.
 *
 * <p><strong>It is not a flag.</strong> A boolean — {@code securityGatePassed=true}, {@code kaas.security.approved} —
 * would be configuration optimism wearing the costume of evidence, and it is exactly what an attacker or a
 * hurried operator would reach for. What is required instead is the full control set with a verdict for each,
 * checked against the set this build of the control plane independently requires. Three properties follow:
 *
 * <ul>
 *   <li>An operator cannot hand-write a passing attestation without enumerating every control the platform
 *       demands, and getting each one right.</li>
 *   <li>When the runner gains a control, every existing attestation stops satisfying the control plane, because
 *       coverage is checked for exact equality rather than containment. Authorization fails closed until a fresh
 *       assessment is produced. Silence is not a pass.</li>
 *   <li>No API path can supply one. There is no endpoint that accepts an attestation, so no worker and no tenant
 *       can assert its own security posture — which is the confused deputy this whole design exists to prevent.</li>
 * </ul>
 *
 * <p>The honest limitation, stated because the alternative is pretending otherwise: this trusts whoever controls
 * the deployment's configuration. That is the same party that controls the database credentials and the JWT
 * issuer, so it is not a new trust boundary. It is emphatically not the same party as a tenant or a worker.
 */
public record SandboxSecurityAttestation(
        String schemaVersion,
        String securityProfileVersion,
        String probeImageDigest,
        String runtime,
        Instant assessedAt,
        Map<String, String> mandatoryControls,
        /**
         * Whether this deployment can actually enforce a destination allowlist, control by control.
         *
         * <p>Kept separate from the mandatory set rather than folded into it, because the two are required
         * under different conditions. Every execution needs the mandatory controls. Only an ALLOWLIST
         * execution needs these, and demanding them of a DENY_ALL run would make a sandbox that wants no
         * network at all depend on the egress subsystem being healthy — increasing the attack surface of
         * precisely the runs that were supposed to have none.
         *
         * <p>Within their own scope the same rule applies as for the mandatory set: exact equality in both
         * directions, and every one must pass. An assessment that omits the control it failed is not a
         * shorter assessment, it is a false one.
         */
        Map<String, String> egressControls,
        String digest) {

    /** The canonicalization the digest is taken over. A later form would be a different schema version. */
    public static final String SCHEMA_VERSION = "kaas.sandbox-security-attestation.v2";

    /**
     * The controls this build of the control plane requires before it will authorize any execution.
     *
     * <p>Held here rather than imported, because importing it would mean depending on the module that produces
     * it — and a component that both performs a check and defines what the check is has not been checked. The
     * duplication is deliberate and it is guarded: a contract test on each side asserts this set matches the
     * shared schema, so the two cannot drift silently in either direction.
     */
    public static final Set<String> REQUIRED_MANDATORY_CONTROLS = Set.of(
            "NON_ROOT_UID",
            "NON_ROOT_GID",
            "READ_ONLY_ROOT",
            "WRITABLE_TMPFS",
            "NO_DOCKER_SOCKET",
            "NO_HOST_MOUNTS",
            "NO_HOST_DEVICES",
            "KERNEL_PATHS_MASKED",
            "CAPABILITIES_DROPPED",
            "NO_NEW_PRIVILEGES",
            "MINIMAL_ENVIRONMENT",
            "NETWORK_DENIED",
            "PID_LIMIT",
            "MEMORY_LIMIT",
            "WALL_CLOCK_TIMEOUT",
            "OUTPUT_BOUNDED");

    /**
     * What a deployment must demonstrate before this platform will authorize an ALLOWLIST execution.
     *
     * <p>Each one is a property of the host that would run the execution, not of the source tree, which is
     * why it has to be assessed rather than assumed. Together they say: this host can create an isolated
     * network and has verified it is isolated; it built the proxy image from the repository and knows its
     * digest; a proxy on that network came up and is serving; a sandbox on that network can reach nothing on
     * its own; and the proxy refuses traffic it cannot authorize.
     *
     * <p>What they deliberately do not cover is whether the proxy honours a control-plane decision, because
     * assessing that would require the gate to hold a control plane — and the gate runs in the module that is
     * build-forbidden from depending on one. That property is proven by the egress test suites and the
     * full-pipeline test instead, which is the right place for it: it is a property of the code, and the code
     * is the same everywhere, whereas these five are properties of the machine.
     */
    public static final Set<String> REQUIRED_EGRESS_CONTROLS = Set.of(
            "EGRESS_NETWORK_INTERNAL",
            "EGRESS_PROXY_IMAGE_PINNED",
            "EGRESS_PROXY_READY",
            "EGRESS_NO_DIRECT_ROUTE",
            "EGRESS_PROXY_FAILS_CLOSED");

    private static final String PASS = "PASS";

    /** How far ahead of database time an assessment may be stamped before it is treated as wrong. */
    private static final java.time.Duration CLOCK_SKEW_TOLERANCE = java.time.Duration.ofMinutes(1);

    public SandboxSecurityAttestation {
        mandatoryControls = Map.copyOf(mandatoryControls);
        egressControls = Map.copyOf(egressControls);
    }

    /**
     * Why this attestation cannot be relied on, or empty when it can.
     *
     * <p>Returns a reason rather than a boolean so a refusal can say which property failed, and so the caller
     * cannot accidentally treat "could not evaluate" as "evaluated successfully". Every branch is a refusal;
     * there is no path that reaches the end without every condition having been positively established.
     */
    public java.util.Optional<String> reasonItCannotBeTrusted(
            Instant now, java.time.Duration maximumAge, String expectedProfileVersion) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            return java.util.Optional.of("schema version is not " + SCHEMA_VERSION);
        }
        if (securityProfileVersion == null || !securityProfileVersion.equals(expectedProfileVersion)) {
            return java.util.Optional.of("assessment is for a different sandbox security profile");
        }
        if (probeImageDigest == null || !probeImageDigest.matches("sha256:[a-f0-9]{64}")) {
            return java.util.Optional.of("probe image is not identified by a digest");
        }
        if (assessedAt == null || assessedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            // An assessment from the future is a clock problem or a forgery, and neither is a reason to proceed.
            //
            // The tolerance is not slack, it is a correction. `assessedAt` is stamped by whichever host ran the
            // gate and `now` comes from the database, so these are two clock domains — and with zero tolerance
            // ordinary sub-second drift makes a FRESHLY PRODUCED attestation unusable, refusing all execution
            // with a message that would not lead anyone to the clock. The staleness bound on the other side is
            // measured in hours; a minute here is the same judgement applied symmetrically.
            return java.util.Optional.of("assessment is not dated in the past");
        }
        if (assessedAt.isBefore(now.minus(maximumAge))) {
            // Freshness matters because the thing being attested is a property of a running host, not of the
            // source tree. A host reconfigured last month is not described by an assessment from last year.
            return java.util.Optional.of("assessment is older than the configured maximum age");
        }
        if (!mandatoryControls.keySet().equals(REQUIRED_MANDATORY_CONTROLS)) {
            // Exact equality in both directions. Containment would let a truncated assessment pass by omitting
            // the control it failed, and would let this build accept an assessment produced for a different,
            // possibly weaker, control set.
            return java.util.Optional.of("assessment does not cover exactly the required mandatory controls");
        }
        List<String> notPassing = mandatoryControls.entrySet().stream()
                .filter(control -> !PASS.equals(control.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!notPassing.isEmpty()) {
            return java.util.Optional.of("mandatory controls did not pass: " + notPassing);
        }
        if (!expectedDigest().equals(digest)) {
            // The digest is checked against the content rather than trusted, so a document whose verdicts were
            // edited after it was produced does not keep the digest that described what it used to say.
            return java.util.Optional.of("assessment digest does not match its content");
        }
        return java.util.Optional.empty();
    }

    /**
     * Why this deployment cannot be relied on to enforce a destination allowlist, or empty when it can.
     *
     * <p>Separate from {@link #reasonItCannotBeTrusted} and called only for an ALLOWLIST policy. Calling it
     * unconditionally would be the mistake described on {@link #egressControls}: a DENY_ALL run refused
     * because the egress subsystem is unhealthy is a run refused for a subsystem it does not use.
     *
     * <p>An empty map is a refusal, not a pass. That is the whole reason this returns a reason: an attestation
     * produced before this control existed carries no egress controls at all, and the fail-closed reading of
     * "no evidence" is "not enforceable".
     */
    public java.util.Optional<String> reasonEgressCannotBeEnforced() {
        if (!egressControls.keySet().equals(REQUIRED_EGRESS_CONTROLS)) {
            return java.util.Optional.of("assessment does not cover exactly the required egress controls");
        }
        List<String> notPassing = egressControls.entrySet().stream()
                .filter(control -> !PASS.equals(control.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!notPassing.isEmpty()) {
            return java.util.Optional.of("egress controls did not pass: " + notPassing);
        }
        return java.util.Optional.empty();
    }

    /** The digest this attestation's content implies, recomputed rather than read back. */
    public String expectedDigest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, SCHEMA_VERSION);
            update(sha, "PROFILE_VERSION");
            update(sha, String.valueOf(securityProfileVersion));
            update(sha, "PROBE_IMAGE_DIGEST");
            update(sha, String.valueOf(probeImageDigest));
            update(sha, "RUNTIME");
            update(sha, String.valueOf(runtime));
            update(sha, "ASSESSED_AT");
            update(sha, String.valueOf(assessedAt));
            update(sha, "CONTROL_COUNT");
            update(sha, Integer.toString(mandatoryControls.size()));
            // Sorted, so the digest describes the content rather than the order a parser happened to produce.
            new TreeMap<>(mandatoryControls).forEach((control, verdict) -> {
                update(sha, "CONTROL");
                update(sha, control);
                update(sha, verdict);
            });
            // Covered by the digest like everything else. A field the digest does not cover is a field an
            // editor can change without the document ceasing to describe itself — and this one decides whether
            // a deployment may open network connections on a tenant's behalf.
            update(sha, "EGRESS_CONTROL_COUNT");
            update(sha, Integer.toString(egressControls.size()));
            new TreeMap<>(egressControls).forEach((control, verdict) -> {
                update(sha, "EGRESS_CONTROL");
                update(sha, control);
                update(sha, verdict);
            });
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
