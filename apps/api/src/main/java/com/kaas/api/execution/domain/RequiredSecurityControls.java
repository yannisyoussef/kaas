package com.kaas.api.execution.domain;

import java.util.Set;

/**
 * The controls this build of the control plane requires before it will authorize an execution.
 *
 * <p>Held here rather than imported from the module that produces the assessment. A component that both
 * performs a check and defines what the check is has not been checked, and importing would also mean depending
 * on the module that holds container-runtime access — which this module's build forbids outright.
 *
 * <p>The duplication is deliberate and it is guarded: a contract test on each side asserts its own set equals
 * {@code packages/api-contracts/mandatory-sandbox-controls.json}, so the two cannot drift silently in either
 * direction. Adding a control to the gate without teaching the control plane about it fails the build.
 */
public final class RequiredSecurityControls {

    private RequiredSecurityControls() {}

    /**
     * The controls required of a baseline-runtime sandbox.
     *
     * <p>Private on purpose. Every caller must say which profile version it is talking about, because the two
     * runtimes do not require the same set — reaching for a bare {@code MANDATORY} is how an assessment
     * produced under one boundary comes to be judged against the other's rules.
     */
    private static final Set<String> BASELINE = Set.of(
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
            "NO_SETUID_BINARIES",
            "MINIMAL_ENVIRONMENT",
            "NETWORK_DENIED",
            "PID_LIMIT",
            "MEMORY_LIMIT",
            "WALL_CLOCK_TIMEOUT",
            "OUTPUT_BOUNDED");

    /**
     * The controls required of a sandbox under the mediating runtime.
     *
     * <p>Not a relaxation of {@link #BASELINE} and not a superset of it — a different set, because the two
     * boundaries make different things observable.
     *
     * <p>{@code NO_NEW_PRIVILEGES} is absent because that runtime's guest does not expose the kernel flag at
     * all. The control is still applied by the launcher; what is missing is any way to observe it from inside,
     * and requiring evidence a runtime cannot produce would only mean the requirement gets satisfied by
     * something that is not evidence. {@code NO_SETUID_BINARIES} covers the escalation path it closes, and is
     * required of both runtimes precisely because it reads the same under each.
     *
     * <p>{@code HOST_KERNEL_SYSCALL_MEDIATION} is present because it is the property this runtime exists to
     * provide, and demonstrating it is the difference between running under the mediating runtime and merely
     * having asked for it.
     */
    private static final Set<String> MEDIATED = Set.of(
            "NON_ROOT_UID",
            "NON_ROOT_GID",
            "READ_ONLY_ROOT",
            "WRITABLE_TMPFS",
            "NO_DOCKER_SOCKET",
            "NO_HOST_MOUNTS",
            "NO_HOST_DEVICES",
            "KERNEL_PATHS_MASKED",
            "CAPABILITIES_DROPPED",
            "NO_SETUID_BINARIES",
            "HOST_KERNEL_SYSCALL_MEDIATION",
            "MINIMAL_ENVIRONMENT",
            "NETWORK_DENIED",
            "PID_LIMIT",
            "MEMORY_LIMIT",
            "WALL_CLOCK_TIMEOUT",
            "OUTPUT_BOUNDED");

    private static final java.util.Map<String, Set<String>> BY_PROFILE_VERSION = java.util.Map.of(
            "kaas.sandbox.v1", BASELINE,
            "kaas.sandbox.gvisor.v1", MEDIATED);

    /**
     * What the named security profile version must have demonstrated.
     *
     * <p>Coverage is checked for <strong>exact equality</strong>, not containment, in both directions.
     * Containment would let a truncated assessment pass by omitting the control it failed, and would let this
     * build accept an assessment produced for a different, possibly weaker, control set. When the runner gains
     * a control, every existing attestation stops satisfying this — authorization fails closed until a fresh
     * assessment is produced, because silence is not a pass.
     *
     * <p>An unrecognised profile version throws rather than returning an empty set. An empty required set is
     * satisfied by an attestation that demonstrates nothing, so "this build has never heard of that boundary"
     * has to be a refusal and not a permissive default.
     */
    public static Set<String> mandatoryFor(String securityProfileVersion) {
        Set<String> required = BY_PROFILE_VERSION.get(securityProfileVersion);
        if (required == null) {
            throw new IllegalArgumentException(
                    "No mandatory control set is defined for security profile version "
                            + securityProfileVersion + "; this build cannot judge that boundary.");
        }
        return required;
    }

    /** Every profile version this build can authorize, for the contract test and for operators. */
    public static Set<String> knownProfileVersions() {
        return BY_PROFILE_VERSION.keySet();
    }

    /**
     * Required only before an {@code ALLOWLIST} execution.
     *
     * <p>A separate set from the mandatory controls because it is required under a different condition. Every
     * execution needs the mandatory ones; demanding these of a {@code DENY_ALL} run would make a sandbox that
     * wants no network at all depend on the egress subsystem being healthy — increasing the attack surface of
     * precisely the runs that were supposed to have none.
     *
     * <p>Each is a property of the host that would run the execution rather than of the source tree, which is
     * why it has to be assessed. Together they say: this host can create an isolated network and has verified
     * it is isolated; it built the proxy image from the repository and knows its digest; a proxy on that
     * network came up and is serving; a sandbox on that network can reach nothing on its own; and the proxy
     * refuses traffic it cannot authorize.
     */
    public static final Set<String> EGRESS = Set.of(
            "EGRESS_NETWORK_INTERNAL",
            "EGRESS_PROXY_IMAGE_PINNED",
            "EGRESS_PROXY_READY",
            "EGRESS_NO_DIRECT_ROUTE",
            "EGRESS_PROXY_FAILS_CLOSED");

    /** The one verdict that counts as demonstrated. {@code UNSUPPORTED} is not a pass and never was. */
    public static final String PASS = "PASS";
}
