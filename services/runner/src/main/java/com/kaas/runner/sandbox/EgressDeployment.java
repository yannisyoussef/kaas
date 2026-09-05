package com.kaas.runner.sandbox;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How <em>this deployment</em> runs an egress proxy: images, networks, resolver, control plane, and bounds.
 *
 * <h2>Why none of this is per-execution</h2>
 *
 * <p>Every field here is deployment wiring, established once when the runner starts, and there is deliberately
 * no path by which a command, a tenant, or a caller can influence one. The two that matter most are the image
 * and the networks. A caller that could choose the proxy image would choose the enforcement; a caller that
 * could name a network could name the one the control plane, the database, or the daemon is on, and the
 * sandbox's isolation is exactly the fact that it is on one network and the proxy is on more.
 *
 * <p>The per-execution part — which destinations, which credential — arrives separately as an
 * {@link EgressPlan}, and none of it is a container setting either.
 *
 * @param proxyImageReference the digest-pinned proxy image; {@link EgressProxyProfile} refuses a tag
 * @param probeImageReference the digest-pinned trusted probe image the sandbox runs
 * @param controlPlaneBaseUri the control plane as the PROXY reaches it, which is not necessarily how the
 *     runner reaches it — the proxy sits on different networks
 * @param serviceAuthorization the proxy's own service credential, complete with its scheme
 * @param dnsServer {@code host:port} of the resolver the proxy queries for target names. The sandbox is never
 *     given one; the whole point is that the security-relevant resolution happens in the proxy
 * @param egressNetworkIds the networks through which the proxy reaches targets, DNS, and the control plane.
 *     The sandbox is attached to none of them
 * @param hostAliases extra {@code name:address} entries for the proxy's own hosts file, for deployments where
 *     the control plane or resolver is reached by a name its resolver does not know. Never consulted for a
 *     tenant destination
 */
public record EgressDeployment(
        String proxyImageReference,
        String probeImageReference,
        String controlPlaneBaseUri,
        String serviceAuthorization,
        String dnsServer,
        List<String> egressNetworkIds,
        List<String> hostAliases,
        Duration dnsTimeout,
        Duration authorizationTimeout,
        Duration revalidationInterval,
        Duration connectTimeout,
        /**
         * The sandbox runtime an allowlist execution runs behind on this deployment.
         *
         * <p>Carried here because the allowlist path builds its <em>own</em> sandbox profile — it has to, the
         * sandbox joins a per-execution network the deny-all profile knows nothing about — and a profile built
         * without this defaulted to the baseline runtime. A deployment configured for the mediating runtime
         * would then have run every allowlist execution behind the weaker boundary.
         *
         * <p>The command-versus-profile check would have refused it rather than running it, so this was a
         * fail-closed gap rather than a downgrade: allowlist execution under the mediating runtime was
         * impossible instead of silently weaker. Both are worth fixing, and only one of them is dangerous.
         */
        ExecutionRuntimeType sandboxRuntime) {

    public EgressDeployment {
        Objects.requireNonNull(proxyImageReference, "The egress proxy image is resolved before it is run.");
        Objects.requireNonNull(probeImageReference, "The sandbox image is resolved before it is run.");
        Objects.requireNonNull(controlPlaneBaseUri, "The proxy must know where to ask.");
        Objects.requireNonNull(serviceAuthorization, "The proxy authenticates when it asks.");
        Objects.requireNonNull(dnsServer, "The proxy resolves target names against a named resolver.");
        Objects.requireNonNull(sandboxRuntime, "An allowlist sandbox runs behind a named runtime.");
        egressNetworkIds = List.copyOf(egressNetworkIds);
        hostAliases = List.copyOf(hostAliases);
        for (Duration bound : List.of(dnsTimeout, authorizationTimeout, revalidationInterval, connectTimeout)) {
            if (bound.isNegative() || bound.isZero()) {
                throw new IllegalArgumentException("Every egress bound is positive; an unbounded one is a hang.");
            }
        }
    }

    /**
     * The longest an established tunnel can outlive the fencing of its assignment.
     *
     * <p>Derived from the two values it is actually made of rather than written down separately, so the number
     * in the documentation cannot drift away from the number the proxy is configured with.
     *
     * <p>It is a <em>polling</em> bound and is named as one. Revocation here is not immediate, and describing
     * it as immediate would be a security claim the implementation cannot support.
     */
    public Duration maximumRevocationLatency() {
        return revalidationInterval.plus(authorizationTimeout);
    }

    /**
     * Everything the proxy container runs with, built from nothing rather than inherited and filtered.
     *
     * <p>Subtraction requires knowing every name worth removing, which nobody does.
     */
    public Map<String, String> proxyEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("KAAS_EGRESS_LISTEN_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        environment.put("KAAS_EGRESS_DNS_SERVER", dnsServer);
        environment.put("KAAS_EGRESS_CONTROL_PLANE", controlPlaneBaseUri);
        environment.put("KAAS_EGRESS_SERVICE_AUTHORIZATION", serviceAuthorization);
        environment.put("KAAS_EGRESS_DNS_TIMEOUT_MS", String.valueOf(dnsTimeout.toMillis()));
        environment.put(
                "KAAS_EGRESS_AUTHORIZATION_TIMEOUT_MS", String.valueOf(authorizationTimeout.toMillis()));
        environment.put(
                "KAAS_EGRESS_REVALIDATION_INTERVAL_MS", String.valueOf(revalidationInterval.toMillis()));
        environment.put("KAAS_EGRESS_CONNECT_TIMEOUT_MS", String.valueOf(connectTimeout.toMillis()));
        return Map.copyOf(environment);
    }

    /**
     * Redacted.
     *
     * <p>{@link #serviceAuthorization} is the credential the proxy authenticates to the control plane with. A
     * record's generated {@code toString} prints every component, and this is the kind of object that reaches
     * a log by being interpolated into a message about something else entirely.
     */
    @Override
    public String toString() {
        return "EgressDeployment[proxyImage=" + proxyImageReference
                + ", probeImage=" + probeImageReference
                + ", controlPlane=" + controlPlaneBaseUri
                + ", serviceAuthorization=<redacted>"
                + ", dnsServer=" + dnsServer
                + ", egressNetworks=" + egressNetworkIds.size()
                + ", maximumRevocationLatency=" + maximumRevocationLatency() + "]";
    }
}
