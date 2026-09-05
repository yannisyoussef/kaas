package com.kaas.runner.sandbox;

import java.util.Map;
import java.util.UUID;

/**
 * Durable identity for sandboxes, so a crashed launcher leaves something a later one can find.
 *
 * <p>Labels carry only what reconciliation needs: that KaaS created this container, which launcher generation
 * created it, and a correlation id. Nothing tenant-identifying and nothing sensitive — labels are readable by
 * anyone who can talk to the daemon, and a label is exactly the wrong place to learn that.
 */
public final class SandboxLabels {
    /** Present on every container KaaS creates, and the only thing reconciliation will ever act on. */
    public static final String MANAGED = "kaas.managed";
    public static final String GENERATION = "kaas.launcher.generation";
    public static final String CORRELATION = "kaas.correlation";
    public static final String PROFILE = "kaas.security.profile";

    /**
     * What kind of resource this is, so reconciliation can tell a sandbox from the proxy that constrains it.
     *
     * <p>Without it the two are indistinguishable to a reconciler, and the failure is asymmetric: removing a
     * sandbox and leaving its proxy leaves a running gateway with no execution behind it, which is exactly the
     * surviving egress path this slice exists to prevent.
     */
    public static final String RESOURCE = "kaas.resource";

    /** An untrusted execution sandbox. */
    public static final String RESOURCE_SANDBOX = "sandbox";

    /** A trusted egress proxy. */
    public static final String RESOURCE_PROXY = "egress-proxy";

    /** A per-execution internal network. */
    public static final String RESOURCE_NETWORK = "execution-network";

    private SandboxLabels() {}

    public static Map<String, String> of(String generation, UUID correlationId, String profileVersion) {
        return of(generation, correlationId, profileVersion, RESOURCE_SANDBOX);
    }

    public static Map<String, String> of(
            String generation, UUID correlationId, String profileVersion, String resource) {
        return Map.of(
                MANAGED, "true",
                GENERATION, generation,
                CORRELATION, correlationId.toString(),
                PROFILE, profileVersion,
                RESOURCE, resource);
    }
}
