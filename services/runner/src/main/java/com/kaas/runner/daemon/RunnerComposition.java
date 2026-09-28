package com.kaas.runner.daemon;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.execution.ExecutionLoop;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.EgressDeployment;
import com.kaas.runner.sandbox.EgressExecutions;
import com.kaas.runner.sandbox.EgressMetrics;
import com.kaas.runner.sandbox.OrphanSandboxReconciler;
import com.kaas.runner.sandbox.SandboxLabels;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * THE production runner, assembled (KAAS-DEPLOY-001).
 *
 * <p>Before this slice {@code RunnerApplication.main} printed a banner: nothing constructed an
 * {@link ExecutionLoop}, and the only compositions of the real execution path lived in test code. This is the
 * one place the deployed runner is built, and the tests that make claims about the deployed runner build it
 * here too -- through {@link #compose(RunnerConfiguration, DockerClient, HttpClient, Clock)} -- rather than
 * through a parallel test wiring that could drift from it.
 *
 * <p>What is composed: the Docker client; the refreshable runner identity and, when allowlisting, the proxy's own;
 * the internal API client; a sandbox launcher for the Karate engine under the CONFIGURED runtime; the command
 * validator for exactly the policies this host can enforce; the execution loop, which owns authority
 * revalidation, lease renewal, source and secret redemption, egress, output capture and result submission; the
 * runtime preflight; the attestation refresher over the real gates; and the orphan reconciler.
 *
 * <p>What is not: anything that speaks AMQP.
 */
public final class RunnerComposition {
    private RunnerComposition() {}

    /** The daemon and its health endpoint, started together and stopped together. */
    public record Runner(RunnerDaemon daemon, HealthServer health) implements AutoCloseable {
        public void start() {
            health.start();
            daemon.start();
        }

        public int healthPort() {
            return health.port();
        }

        @Override
        public void close() {
            try {
                daemon.stop();
            } finally {
                health.close();
            }
        }
    }

    /** Production: every client built from configuration. */
    public static Runner compose(RunnerConfiguration configuration) throws java.io.IOException {
        return compose(configuration, docker(configuration.dockerHost()),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC(), true);
    }

    /**
     * The same composition with the clients supplied: the Docker client a test suite already resolved, and the
     * HTTP client it wants requests to go through. Nothing else differs.
     */
    public static Runner compose(RunnerConfiguration configuration, DockerClient docker, HttpClient http, Clock clock)
            throws java.io.IOException {
        return compose(configuration, docker, http, clock, false);
    }

    /** @param ownsClients whether shutdown closes the clients; false when the caller supplied and still owns them */
    private static Runner compose(RunnerConfiguration configuration, DockerClient docker, HttpClient http,
            Clock clock, boolean ownsClients) throws java.io.IOException {
        RunnerDaemon.Parts parts = parts(configuration, docker, http, clock, ownsClients);
        RunnerDaemon daemon = new RunnerDaemon(parts);
        HealthServer health = new HealthServer(configuration.healthHost(), configuration.healthPort(),
                daemon::live, parts.readiness(), parts.metrics());
        return new Runner(daemon, health);
    }

    /**
     * Every part the daemon drives, exactly as production builds them. Package-private so the composition test can
     * exercise each real part -- the execution loop, the preflight, the reconciler, the client -- directly.
     */
    static RunnerDaemon.Parts parts(RunnerConfiguration configuration, DockerClient docker, HttpClient http,
            Clock clock, boolean ownsClients) {
        ObjectMapper mapper = JsonMapper.builder().build();
        RunnerMetrics metrics = new RunnerMetrics();
        Readiness readiness = new Readiness();
        Duration timeout = configuration.requestTimeout();

        ServiceIdentity runnerIdentity = new ServiceIdentity(configuration.workerId(),
                ServiceIdentity.sourceFor(configuration.credentials(), http, mapper, timeout), clock, metrics, "runner");
        ControlPlaneClient controlPlane = new ControlPlaneClient(
                http, configuration.apiBaseUri(), runnerIdentity, timeout, ControlPlaneClient.Sleeper.real());

        // One generation per process: every sandbox, proxy and network this process creates carries it, which is
        // how shutdown removes exactly its own leftovers and nothing any other process owns.
        String generation = "runner-" + UUID.randomUUID();
        EgressMetrics egressMetrics = new EgressMetrics();
        metrics.includeEgress(egressMetrics);

        SandboxSecurityProfile profile =
                SandboxSecurityProfile.version1(configuration.engineImage(), configuration.sandboxRuntime());
        DockerSandboxLauncher launcher = new DockerSandboxLauncher(docker, profile, generation);

        EgressExecutions egress = configuration.egress().map(allowlist -> (EgressExecutions)
                        new RefreshingEgressExecutions(
                                docker,
                                new ServiceIdentity(EGRESS_PROXY_SUBJECT,
                                        ServiceIdentity.sourceFor(allowlist.proxyCredentials(), http, mapper, timeout),
                                        clock, metrics, "egress_proxy"),
                                credential -> new EgressDeployment(
                                        allowlist.proxyImage(),
                                        configuration.engineImage(),
                                        allowlist.proxyControlPlaneUri().toString(),
                                        credential,
                                        allowlist.dnsServer(),
                                        allowlist.egressNetworkIds(),
                                        List.of(),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(3),
                                        configuration.sandboxRuntime()),
                                generation,
                                egressMetrics))
                .orElse(null);
        // Exactly the policies this host can enforce. Without a proxy an ALLOWLIST command is refused by the
        // validator before anything is provisioned -- and the loop refuses it again if one ever got through.
        Set<String> policies = egress == null ? Set.of("DENY_ALL") : Set.of("DENY_ALL", "ALLOWLIST");
        CommandValidator validator = new CommandValidator(mapper, policies, Optional.empty(),
                CommandValidator.KARATE_ENGINE, CommandValidator.KARATE_VERSION);

        ExecutionLoop loop = new ExecutionLoop(controlPlane, validator, launcher, mapper, clock,
                SyntheticProbe.KARATE_ENGINE, egress, true, CommandValidator.KARATE_ENGINE);

        RuntimePreflight preflight = new RuntimePreflight(docker, configuration, readiness);
        AttestationRefresher attestation = new AttestationRefresher(
                AttestationRefresher.gates(docker, configuration), controlPlane, readiness, metrics, clock, mapper);
        OrphanSandboxReconciler reconciler = new OrphanSandboxReconciler(
                docker, generation, configuration.sandboxWallClockTimeout(), clock, egressMetrics);

        return new RunnerDaemon.Parts(
                configuration,
                readiness,
                metrics,
                controlPlane,
                runnerIdentity,
                preflight::check,
                attestation,
                reconciler::reconcile,
                () -> removeOwnGeneration(docker, generation),
                loop::execute,
                () -> {
                    if (!ownsClients) {
                        return;
                    }
                    try {
                        docker.close();
                    } catch (java.io.IOException ignored) {
                        // Closing on the way out; nothing left to protect.
                    }
                    http.close();
                },
                clock,
                RunnerDaemon.TICK);
    }

    /** The subject the proxy's credential must carry; the control plane grants it one authority and no other. */
    static final String EGRESS_PROXY_SUBJECT = "kaas.egress-proxy";

    /**
     * Removes what THIS process created and still holds, at shutdown and only then.
     *
     * <p>Matched on the managed label AND this process's generation, never on age alone: by the time this runs
     * every execution of this process has ended or been interrupted, so nothing of its generation is live, and
     * nothing of any other generation is touched. Orphans from other processes are the reconciler's, judged by
     * age, on its own schedule.
     */
    static void removeOwnGeneration(DockerClient docker, String generation) {
        Map<String, String> own = Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.GENERATION, generation);
        for (var container : docker.listContainersCmd().withShowAll(true).withLabelFilter(own).exec()) {
            try {
                docker.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
            } catch (RuntimeException alreadyGone) {
                // Removed by its own cleanup a moment ago.
            }
        }
        for (var network : docker.listNetworksCmd().exec()) {
            Map<String, String> labels = network.getLabels();
            if (labels != null && "true".equals(labels.get(SandboxLabels.MANAGED))
                    && generation.equals(labels.get(SandboxLabels.GENERATION))) {
                try {
                    docker.removeNetworkCmd(network.getId()).exec();
                } catch (RuntimeException inUseOrGone) {
                    // A network still in use is left for the reconciler; the daemon refuses to remove it anyway.
                }
            }
        }
    }

    private static DockerClient docker(String host) {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(host).build();
        return DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .responseTimeout(Duration.ofSeconds(120))
                .connectionTimeout(Duration.ofSeconds(30))
                .build());
    }
}
