package com.kaas.egress;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

/**
 * The proxy's entry point. Every setting comes from the launcher's environment and none from a request.
 *
 * <p>There is no configuration file and no default for anything security-relevant. A missing setting is a
 * refusal to start, not a fallback: a proxy that started with no DNS server, no control plane, or no service
 * credential would either fail every request or — worse, depending on how the defaults were chosen — carry
 * traffic it could not authorize. Failing to start is loud, immediate, and cannot be mistaken for working.
 */
public final class ProxyMain {

    private ProxyMain() {}

    public static void main(String[] args) throws Exception {
        ProxyConfiguration configuration = new ProxyConfiguration(
                intFromEnvironment("KAAS_EGRESS_LISTEN_PORT"),
                dnsServerFromEnvironment(),
                requiredEnvironment("KAAS_EGRESS_CONTROL_PLANE"),
                Duration.ofMillis(intFromEnvironment("KAAS_EGRESS_DNS_TIMEOUT_MS")),
                Duration.ofMillis(intFromEnvironment("KAAS_EGRESS_AUTHORIZATION_TIMEOUT_MS")),
                Duration.ofMillis(intFromEnvironment("KAAS_EGRESS_REVALIDATION_INTERVAL_MS")),
                Duration.ofMillis(intFromEnvironment("KAAS_EGRESS_CONNECT_TIMEOUT_MS")));

        EgressAuthorizer authorizer = new ControlPlaneAuthorizer(
                URI.create(configuration.controlPlane()),
                requiredEnvironment("KAAS_EGRESS_SERVICE_AUTHORIZATION"),
                configuration.authorizationTimeout());
        TargetResolver resolver = new TargetResolver(configuration.dnsServer(), configuration.dnsTimeout());

        try (ProxyServer server = new ProxyServer(configuration, authorizer, resolver)) {
            // Deliberately printed without any of the values that matter. The listening port and the
            // revocation bound are operational facts; the service credential, the control-plane host, and
            // every destination are not, and this line is read by whoever can read container logs.
            System.out.println("kaas-egress-proxy listening port=" + server.port()
                    + " revocationBoundMs=" + configuration.maximumRevocationLatency().toMillis());
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.serve();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The egress proxy requires " + name + " and will not start without it.");
        }
        return value;
    }

    private static int intFromEnvironment(String name) {
        return Integer.parseInt(requiredEnvironment(name));
    }

    /**
     * The resolver to query, as {@code host:port}.
     *
     * <p>Parsed here rather than resolved: the value is an address supplied by the launcher, so there is no
     * name to look up and no opportunity for the proxy's own startup to depend on a resolver it does not have
     * yet.
     */
    private static InetSocketAddress dnsServerFromEnvironment() {
        String value = requiredEnvironment("KAAS_EGRESS_DNS_SERVER");
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalStateException("KAAS_EGRESS_DNS_SERVER is an address and a port.");
        }
        return new InetSocketAddress(
                value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
    }
}
