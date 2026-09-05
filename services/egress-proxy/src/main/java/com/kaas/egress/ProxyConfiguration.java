package com.kaas.egress;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Everything the proxy needs, all of it supplied by the trusted launcher and none of it by a client.
 *
 * <p>No value here can be influenced by the sandbox. That is the point of it being a launch-time record
 * rather than anything the proxy reads at runtime: a proxy whose upstream DNS server or revalidation interval
 * could be changed by the workload it is constraining would be constraining nothing.
 *
 * @param listenPort the port the sandbox connects to, on the execution-internal network
 * @param dnsServer the resolver the proxy queries; never inherited from a client request
 * @param controlPlane the base URI of the authorization service
 * @param dnsTimeout how long one resolution may take
 * @param authorizationTimeout how long one authorization exchange may take
 * @param revalidationInterval how often an established tunnel is re-checked against authoritative state
 * @param connectTimeout how long one target connection attempt may take
 */
public record ProxyConfiguration(
        int listenPort,
        InetSocketAddress dnsServer,
        String controlPlane,
        Duration dnsTimeout,
        Duration authorizationTimeout,
        Duration revalidationInterval,
        Duration connectTimeout) {

    /**
     * The documented upper bound on how long an established tunnel can outlive the fencing of its assignment.
     *
     * <p>This is a polling bound, and it is stated as one. The revocation is not immediate: the assignment can
     * be fenced at any point between two revalidations, so the worst case is a full interval before the next
     * check begins, plus the time that check is allowed to take before its own timeout forces a fail-closed
     * answer. Calling this "immediate" would be a documented property the implementation cannot prove.
     */
    public Duration maximumRevocationLatency() {
        return revalidationInterval.plus(authorizationTimeout);
    }
}
