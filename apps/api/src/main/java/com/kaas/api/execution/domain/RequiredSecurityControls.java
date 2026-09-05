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
     * Required for every execution, whatever its network policy.
     *
     * <p>Coverage is checked for <strong>exact equality</strong>, not containment, in both directions.
     * Containment would let a truncated assessment pass by omitting the control it failed, and would let this
     * build accept an assessment produced for a different, possibly weaker, control set. When the runner gains
     * a control, every existing attestation stops satisfying this — authorization fails closed until a fresh
     * assessment is produced, because silence is not a pass.
     */
    public static final Set<String> MANDATORY = Set.of(
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
