package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.kaas.egress.TestDnsServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole egress topology, built from real containers and real networks, with no public internet anywhere.
 *
 * <pre>
 *   host                          bridge            kaas-egress-*            kaas-exec-*
 *   ┌──────────────┐                 │              (internal,                (internal)
 *   │ DNS server   │◀────────────────┤               11.0.0.0/24)                 │
 *   │ auth service │◀────────────────┤                    │                       │
 *   └──────────────┘                 │                    │                       │
 *                                    └──────────── proxy ─┴───────────────────────┤
 *                                                                                 │
 *                                    target ──────────────┘               sandbox ┘
 * </pre>
 *
 * <h2>Why the targets sit on 11.0.0.0/24</h2>
 *
 * <p>The production classifier permits global unicast and nothing else, so a test target on a default Docker
 * bridge — which is RFC 1918 — would be refused by the very control under test. The alternative of exempting
 * private addresses "just for tests" is the one thing that must not happen: it would delete the control from
 * every test that claims to exercise it.
 *
 * <p>So the test network is given a subnet the production classifier genuinely treats as global unicast.
 * 11.0.0.0/8 is allocated and globally unique but is not routed on the public internet, so nothing here can
 * collide with a destination anyone would legitimately reach. The network is also created {@code --internal},
 * which is asserted rather than assumed, so the synthetic "global" network provably has no route off this
 * host: the tests reach a global-classified address without a packet ever leaving the machine.
 *
 * <h2>Why the proxy is on three networks and the sandbox on one</h2>
 *
 * <p>That asymmetry <em>is</em> the no-bypass property. The proxy can reach targets, DNS, and the control
 * plane; the sandbox can reach the proxy and nothing else, because the only network it is attached to has no
 * route to any of them. Nothing in the sandbox's configuration is asked to cooperate.
 */
final class EgressTestTopology implements AutoCloseable {

    /** Global unicast to the production classifier, and unroutable in practice. See the class comment. */
    static final String EGRESS_SUBNET = "11.0.0.0/24";

    static final String ALLOWED_HOST = "allowed.egress-test.example";

    static final String DENIED_HOST = "denied.egress-test.example";

    static final String PRIVATE_HOST = "private.egress-test.example";

    static final String REDIRECT_HOST = "redirect-escape.egress-test.example";

    static final int TARGET_PORT = 80;

    static final int TARGET_HOLD_PORT = 8443;

    /** How often the proxy in this topology re-asks whether an open tunnel may continue. */
    static final long REVALIDATION_INTERVAL_MS = 2000;

    /** How long one of those questions may take before its own timeout answers it fail-closed. */
    static final long AUTHORIZATION_TIMEOUT_MS = 2000;

    /**
     * The documented maximum revocation latency for this topology: an assignment fenced at T leaves an
     * established tunnel usable until at most T plus this. It is derived from the two values above rather
     * than written down separately, so it cannot drift away from what the proxy is actually configured to do.
     *
     * <p>It is a polling bound and is stated as one. Revocation is not immediate and the documentation must
     * not say it is.
     */
    static final long MAXIMUM_REVOCATION_LATENCY_MS = REVALIDATION_INTERVAL_MS + AUTHORIZATION_TIMEOUT_MS;

    private final DockerClient docker;

    private final List<String> containers = new ArrayList<>();

    private final List<String> networks = new ArrayList<>();

    final TestDnsServer dns;

    final TestAuthorizationService authorization;

    final ExecutionNetwork executionNetwork;

    final String egressNetworkId;

    final String targetAddress;

    final EgressProxy proxy;

    /**
     * Cached at construction, because a test may stop or remove the proxy and then still need to describe the
     * topology it built. Reading it back from the daemon afterwards fails with a 404 that has nothing to do
     * with the property under test.
     */
    private final String proxyAddress;

    final String capabilityToken = "kaas-egress-capability-" + UUID.randomUUID();

    EgressTestTopology(String generation) throws IOException {
        this(generation, true);
    }

    /**
     * The same topology with no execution network and no proxy of its own.
     *
     * <p>For the tests that drive {@link DockerEgressExecutions}, which creates those two itself — that being
     * the thing under test. Starting a second proxy here and never using it would leave a reader unable to
     * tell which one an assertion was about.
     */
    static EgressTestTopology withoutProxy(String generation) throws IOException {
        return new EgressTestTopology(generation, false);
    }

    private EgressTestTopology(String generation, boolean startProxy) throws IOException {
        this.docker = SandboxTestSupport.docker();
        this.dns = new TestDnsServer(false);
        this.authorization = new TestAuthorizationService();

        UUID correlationId = UUID.randomUUID();
        try {
            this.egressNetworkId = createEgressNetwork(correlationId);
            String targetId = startTarget();
            this.targetAddress = addressOn(targetId, egressNetworkId);

            // Every name the tests use resolves through the controlled server, so no test depends on any
            // name that exists in the world.
            dns.answering(ALLOWED_HOST, targetAddress);
            dns.answering(DENIED_HOST, targetAddress);
            dns.answering(REDIRECT_HOST, targetAddress);
            // A name the policy may permit that resolves into private space. This is the only shape that
            // reaches the address classifier at all: a name the policy refuses is stopped before resolution.
            dns.answering(PRIVATE_HOST, "10.0.0.7");

            this.executionNetwork = startProxy ? ExecutionNetwork.create(docker, generation, correlationId) : null;
            this.proxy = startProxy ? startProxy(generation, correlationId) : null;
            this.proxyAddress = startProxy ? addressOn(proxy.containerId(), executionNetwork.networkId()) : null;
            authorization.allowOnly(ALLOWED_HOST + ":" + TARGET_PORT + "/HTTP");
        } catch (RuntimeException failure) {
            // Anything at all past this point removes everything already created. A partially built topology
            // left behind is a running proxy with no execution, which is precisely the artefact these tests
            // exist to prove cannot survive.
            close();
            throw failure;
        }
    }

    /**
     * The egress network: internal, so the synthetic "global" address space provably has no route off this
     * host, and asserted to be internal rather than trusted to be.
     */
    private String createEgressNetwork(UUID correlationId) {
        String id = docker.createNetworkCmd()
                .withName("kaas-egress-test-" + correlationId)
                .withInternal(true)
                .withAttachable(false)
                .withIpam(new Network.Ipam().withConfig(new Network.Ipam.Config().withSubnet(EGRESS_SUBNET)))
                .withLabels(Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.RESOURCE, "egress-test"))
                .exec()
                .getId();
        networks.add(id);
        Network inspected = docker.inspectNetworkCmd().withNetworkId(id).exec();
        if (!Boolean.TRUE.equals(inspected.getInternal())) {
            throw new IllegalStateException(
                    "The synthetic egress network must be internal, or the test reaches the real internet.");
        }
        return id;
    }

    private String startTarget() {
        CreateContainerResponse created = docker.createContainerCmd(SandboxTestSupport.egressTargetImage())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(egressNetworkId))
                .withEnv("KAAS_REDIRECT_TARGET=http://" + DENIED_HOST + ":" + TARGET_PORT + "/ok")
                .withLabels(Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.RESOURCE, "egress-test"))
                .exec();
        containers.add(created.getId());
        docker.startContainerCmd(created.getId()).exec();
        return created.getId();
    }

    private EgressProxy startProxy(String generation, UUID correlationId) {
        // The host, as reached from inside a container. Added explicitly rather than relying on the name
        // existing: it is present by default only on Docker Desktop, and the CI runner is plain Linux.
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_EGRESS_LISTEN_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        environment.put("KAAS_EGRESS_DNS_SERVER", "host.docker.internal:" + dns.port());
        environment.put("KAAS_EGRESS_CONTROL_PLANE", "http://host.docker.internal:" + authorization.port());
        environment.put("KAAS_EGRESS_SERVICE_AUTHORIZATION", "Bearer kaas-egress-proxy-service-token");
        environment.put("KAAS_EGRESS_DNS_TIMEOUT_MS", "5000");
        environment.put("KAAS_EGRESS_AUTHORIZATION_TIMEOUT_MS", String.valueOf(AUTHORIZATION_TIMEOUT_MS));
        // Short on purpose, so the documented revocation bound can be measured inside a test's patience. The
        // bound reported by the proxy is derived from these values rather than written down separately.
        environment.put("KAAS_EGRESS_REVALIDATION_INTERVAL_MS", String.valueOf(REVALIDATION_INTERVAL_MS));
        environment.put("KAAS_EGRESS_CONNECT_TIMEOUT_MS", "3000");

        EgressProxyProfile profile =
                EgressProxyProfile.version1(SandboxTestSupport.egressProxyImage(), environment);
        // Three networks for the proxy, one for the sandbox. The bridge is here because the DNS server and
        // the authorization service run on the host, and the egress network is internal precisely so that the
        // synthetic global address space cannot route anywhere. In a real deployment the second network is
        // whatever reaches the control plane and the internet; the shape — proxy on more networks than the
        // sandbox — is the same.
        EgressProxy started = EgressProxy.start(
                docker,
                profile,
                generation,
                correlationId,
                executionNetwork,
                List.of(egressNetworkId, "bridge"),
                List.of("host.docker.internal:host-gateway"));
        containers.add(started.containerId());
        return started;
    }

    /** The address a container holds on one network. Discovered, never assigned, so IPAM stays Docker's job. */
    private String addressOn(String containerId, String networkId) {
        InspectContainerResponse inspected = docker.inspectContainerCmd(containerId).exec();
        for (Map.Entry<String, ContainerNetwork> attachment :
                inspected.getNetworkSettings().getNetworks().entrySet()) {
            ContainerNetwork network = attachment.getValue();
            if (networkId.equals(network.getNetworkID())) {
                return network.getIpAddress();
            }
        }
        throw new IllegalStateException("The container is not attached to the network it was created on.");
    }

    /** The environment a sandbox needs to talk to this topology's proxy. Server-owned, never tenant input. */
    Map<String, String> sandboxEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_EGRESS_PROXY_HOST", proxyAddress());
        environment.put("KAAS_EGRESS_PROXY_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        environment.put("KAAS_EGRESS_CAPABILITY", capabilityToken);
        environment.put("KAAS_EGRESS_ALLOWED_HOST", ALLOWED_HOST);
        environment.put("KAAS_EGRESS_ALLOWED_PORT", String.valueOf(TARGET_PORT));
        environment.put("KAAS_EGRESS_DENIED_HOST", DENIED_HOST);
        environment.put("KAAS_EGRESS_PRIVATE_HOST", PRIVATE_HOST);
        // The address the proxy is allowed to reach, handed to the sandbox deliberately. The bypass probe
        // aims straight at it: the sandbox knowing exactly where the target is must not help it get there.
        environment.put("KAAS_EGRESS_DIRECT_IP", targetAddress);
        return environment;
    }

    String proxyAddress() {
        return proxyAddress;
    }

    /**
     * This topology expressed as deployment wiring, for the tests that drive the production egress path.
     *
     * <p>Everything a real deployment would supply once at startup: the image, the resolver, the control
     * plane, and the networks the proxy — and only the proxy — is attached to. Nothing here is per-execution
     * and nothing here is reachable from a command.
     */
    EgressDeployment deployment() {
        return new EgressDeployment(
                SandboxTestSupport.egressProxyImage(),
                SandboxTestSupport.probeImage(),
                "http://host.docker.internal:" + authorization.port(),
                "Bearer kaas-egress-proxy-service-token",
                "host.docker.internal:" + dns.port(),
                List.of(egressNetworkId, "bridge"),
                List.of("host.docker.internal:host-gateway"),
                java.time.Duration.ofMillis(5000),
                java.time.Duration.ofMillis(AUTHORIZATION_TIMEOUT_MS),
                java.time.Duration.ofMillis(REVALIDATION_INTERVAL_MS),
                java.time.Duration.ofMillis(3000),
                ExecutionRuntimeType.DOCKER);
    }

    @Override
    public void close() {
        for (String container : containers) {
            try {
                docker.removeContainerCmd(container).withForce(true).withRemoveVolumes(true).exec();
            } catch (RuntimeException alreadyGone) {
                // Already removed. The point of this loop is that nothing survives it.
            }
        }
        if (executionNetwork != null) {
            executionNetwork.close();
        }
        for (String network : networks) {
            try {
                docker.removeNetworkCmd(network).exec();
            } catch (RuntimeException alreadyGone) {
                // Already removed.
            }
        }
        try {
            authorization.close();
            dns.close();
        } catch (IOException ignored) {
            // Test fixtures shutting down.
        }
    }
}
