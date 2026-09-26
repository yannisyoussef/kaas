package com.kaas.pipeline;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.kaas.egress.TestDnsServer;
import com.kaas.runner.sandbox.EgressDeployment;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.EgressProxyImage;
import com.kaas.runner.sandbox.ProbeImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The half of an allowlist execution that a deployment owns: a resolver, a target, and a network to reach it.
 *
 * <p>Everything else in the picture is real and comes from somewhere else — the control plane is the one this
 * test class starts, the proxy is the repository's own image, the network and sandbox are created by the
 * production {@code DockerEgressExecutions}, and the policy is a row in the real database.
 *
 * <h2>No public internet, and a target that still classifies as global</h2>
 *
 * <p>The production address classifier permits global unicast and nothing else, so a target on an ordinary
 * Docker bridge — RFC 1918 — would be refused by the very control under test, and exempting private addresses
 * "for tests" would delete that control from every test claiming to exercise it. So the target sits on
 * 11.0.0.0/24: allocated and globally unique, therefore global unicast to the production classifier, but not
 * routed on the public internet. The network is created {@code --internal} and that flag is asserted rather
 * than assumed, so the synthetic "global" address space provably has no route off this host.
 */
final class EgressPipelineTopology implements AutoCloseable {

    /** Global unicast to the production classifier, and unroutable in practice. See the class comment. */
    private static final String EGRESS_SUBNET = "11.0.0.0/24";

    /** The name the run's policy will permit, and the only name this resolver answers for a target. */
    static final String ALLOWED_HOST = "api.pipeline-egress.example";

    static final int TARGET_PORT = 80;

    /**
     * A port the target accepts on and then holds without saying anything.
     *
     * <p>Stands in for an HTTPS destination. The proxy tunnels rather than terminating TLS, so what a CONNECT
     * proves is the whole of the proxy's decision — authorized, resolved, classified, connected — with the
     * payload left end to end. A target that spoke TLS would add nothing to that and would make the test
     * depend on a certificate nobody issued.
     */
    static final int TARGET_HOLD_PORT = 8443;

    /** Matches the proxy's own configuration below, so the documented bound cannot drift from the real one. */
    static final long REVALIDATION_INTERVAL_MS = 2000;

    static final long AUTHORIZATION_TIMEOUT_MS = 2000;

    private final DockerClient docker;

    private final List<String> containers = new ArrayList<>();

    private final List<String> networks = new ArrayList<>();

    private final String egressNetworkId;

    private final String proxyImage;

    private final String probeImage;

    final TestDnsServer dns;

    final String targetAddress;

    private final Map<String, String> targetEnvironment;

    private String targetId;

    EgressPipelineTopology(DockerClient docker, String probeImage) throws IOException {
        this(docker, probeImage, Map.of());
    }

    /**
     * A topology whose target is configured with the given environment -- for the secret-execution suite, the
     * SHA-256 of the one bearer token its {@code /auth} path accepts. Never the token itself.
     */
    EgressPipelineTopology(DockerClient docker, String probeImage, Map<String, String> targetEnvironment)
            throws IOException {
        this.docker = docker;
        this.probeImage = probeImage;
        this.targetEnvironment = Map.copyOf(targetEnvironment);
        this.dns = new TestDnsServer(false);
        try {
            this.proxyImage = EgressProxyImage.build(docker, proxyContext());
            this.egressNetworkId = createEgressNetwork();
            this.targetAddress = addressOn(startTarget(), egressNetworkId);
            dns.answering(ALLOWED_HOST, targetAddress);
        } catch (RuntimeException failure) {
            // A partially built topology left behind is a container and a network nobody owns. Everything
            // already created goes before the failure is re-raised.
            close();
            throw failure;
        }
    }

    /**
     * The build context Gradle assembled, located from a system property rather than guessed at.
     *
     * <p>A guessed path into another module's build directory silently builds whatever a previous build left
     * there, which is an image nobody compiled in this run.
     */
    private static Path proxyContext() {
        String context = System.getProperty("kaas.egress.proxy.context");
        if (context == null) {
            throw new IllegalStateException(
                    "kaas.egress.proxy.context is set by the build from the proxy image context dependency; "
                            + "without it this test would silently build nothing.");
        }
        return Path.of(context);
    }

    private String createEgressNetwork() {
        String id = docker.createNetworkCmd()
                .withName("kaas-egress-pipeline-" + UUID.randomUUID())
                .withInternal(true)
                .withAttachable(false)
                .withIpam(new Network.Ipam().withConfig(new Network.Ipam.Config().withSubnet(EGRESS_SUBNET)))
                .withLabels(Map.of("kaas.managed", "true", "kaas.resource", "egress-pipeline"))
                .exec()
                .getId();
        networks.add(id);
        Network inspected = docker.inspectNetworkCmd().withNetworkId(id).exec();
        if (!Boolean.TRUE.equals(inspected.getInternal())) {
            throw new IllegalStateException(
                    "The synthetic egress network must be internal, or this test reaches the real internet.");
        }
        return id;
    }

    private String startTarget() {
        List<String> environment = new ArrayList<>();
        targetEnvironment.forEach((name, value) -> environment.add(name + "=" + value));
        CreateContainerResponse created = docker.createContainerCmd(targetImage())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(egressNetworkId))
                .withLabels(Map.of("kaas.managed", "true", "kaas.resource", "egress-pipeline"))
                .withEnv(environment)
                .exec();
        containers.add(created.getId());
        docker.startContainerCmd(created.getId()).exec();
        targetId = created.getId();
        return created.getId();
    }

    /** Requests the target's {@code /auth} path accepted, from the target's own log. */
    int authenticatedRequests() {
        return count("auth_result=AUTHENTICATED");
    }

    /** Requests the target's {@code /auth} path refused, from the target's own log. */
    int rejectedRequests() {
        return count("auth_result=REJECTED");
    }

    private int count(String verdict) {
        String logs = logsOf(targetId);
        int count = 0;
        for (String line : logs.split("\n")) {
            if (line.strip().equals(verdict)) {
                count++;
            }
        }
        return count;
    }

    private String logsOf(String containerId) {
        var collected = new java.io.ByteArrayOutputStream();
        try {
            docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withFollowStream(false)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            collected.writeBytes(frame.getPayload());
                        }
                    })
                    .awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return collected.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Watches every egress proxy of a runner generation for as long as it lives, and keeps what it logged.
     *
     * <p>A proxy container is removed with its execution, and its log with it, so "the proxy did not log the
     * secret" has to be observed while the proxy exists. The watcher reads each proxy's full log repeatedly
     * until the proxy is gone, keeping the longest copy it saw.
     */
    ProxyLogWatcher watchProxies(String generation) {
        return new ProxyLogWatcher(generation);
    }

    final class ProxyLogWatcher implements AutoCloseable {
        private final java.util.Map<String, String> logs = new java.util.concurrent.ConcurrentHashMap<>();
        private final Thread thread;
        private volatile boolean stopped;

        private ProxyLogWatcher(String generation) {
            thread = new Thread(() -> {
                while (!stopped) {
                    try {
                        for (var proxy : docker.listContainersCmd()
                                .withLabelFilter(Map.of(
                                        "kaas.launcher.generation", generation, "kaas.resource", "egress-proxy"))
                                .exec()) {
                            String log = logsOf(proxy.getId());
                            logs.merge(proxy.getId(), log, (old, fresh) -> fresh.length() >= old.length() ? fresh : old);
                        }
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        return;
                    } catch (RuntimeException gone) {
                        // A proxy removed between listing and reading. The previous copy stands.
                    }
                }
            }, "proxy-log-watcher");
            thread.setDaemon(true);
            thread.start();
        }

        /** Everything every watched proxy logged. */
        String captured() {
            return String.join("\n", logs.values());
        }

        int proxiesSeen() {
            return logs.size();
        }

        @Override
        public void close() throws InterruptedException {
            stopped = true;
            thread.join(5_000);
        }
    }

    /** Anything this runner generation left behind: containers and networks that should be gone. */
    List<String> leftovers(String generation) {
        List<String> left = new ArrayList<>();
        docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("kaas.launcher.generation", generation))
                .exec()
                .forEach(container -> left.add("container " + container.getId()));
        docker.listNetworksCmd().withFilter("label", List.of("kaas.launcher.generation=" + generation))
                .exec()
                .forEach(network -> left.add("network " + network.getId()));
        return left;
    }

    private String targetImage() {
        // From the repository, like everything else that runs here. Located from the root because this
        // module's working directory is its own and the build context belongs to another module.
        return ProbeImage.build(
                docker, Path.of("..", "..", "services", "runner", "src", "main", "docker", "egress-target"));
    }

    /**
     * This topology as deployment wiring.
     *
     * <p>The proxy is attached to the egress network and the bridge; the sandbox will be attached to neither.
     * That asymmetry is the no-bypass property, and it is topological — nothing in the sandbox's own
     * configuration is asked to cooperate. The bridge is here because the control plane and the resolver run
     * in this JVM, on the host; in a real deployment it is whatever reaches them.
     */
    EgressDeployment deployment(int controlPlanePort, String serviceAuthorization) {
        return deployment(controlPlanePort, serviceAuthorization, ExecutionRuntimeType.DOCKER);
    }

    /** The same deployment, with its sandboxes under the given runtime. */
    EgressDeployment deployment(int controlPlanePort, String serviceAuthorization, ExecutionRuntimeType runtime) {
        return new EgressDeployment(
                proxyImage,
                probeImage,
                "http://host.docker.internal:" + controlPlanePort,
                serviceAuthorization,
                "host.docker.internal:" + dns.port(),
                List.of(egressNetworkId, "bridge"),
                // Added explicitly rather than relied upon: the name exists by default only on Docker
                // Desktop, and CI is plain Linux.
                List.of("host.docker.internal:host-gateway"),
                Duration.ofSeconds(5),
                Duration.ofMillis(AUTHORIZATION_TIMEOUT_MS),
                Duration.ofMillis(REVALIDATION_INTERVAL_MS),
                Duration.ofSeconds(3),
                runtime);
    }

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

    @Override
    public void close() {
        for (String container : containers) {
            try {
                docker.removeContainerCmd(container).withForce(true).withRemoveVolumes(true).exec();
            } catch (RuntimeException alreadyGone) {
                // Already removed. The point of this loop is that nothing survives it.
            }
        }
        for (String network : networks) {
            try {
                docker.removeNetworkCmd(network).exec();
            } catch (RuntimeException alreadyGone) {
                // Already removed.
            }
        }
        try {
            dns.close();
        } catch (IOException ignored) {
            // A test fixture shutting down.
        }
    }
}
