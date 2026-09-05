package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Docker implementation: one internal network and one trusted proxy per execution.
 *
 * <h2>Why the proxy's address is discovered rather than assigned</h2>
 *
 * <p>IPAM stays the runtime's job. Reserving or predicting an address would mean this code has an opinion
 * about a subnet it did not allocate, and the failure mode of a wrong opinion is a sandbox pointed at an
 * address that belongs to something else.
 *
 * <h2>What the sandbox is told, and why that is safe</h2>
 *
 * <p>It is told where its proxy is and what credential to present. Anything delivered into a sandbox must be
 * assumed readable by whatever runs there, so nothing here relies on the workload keeping it. Knowing the
 * proxy's address is worth nothing without a live capability; knowing the capability is worth nothing off this
 * execution's network, in another epoch, or for another destination.
 *
 * <p>It is deliberately <em>not</em> told a resolver. The security-relevant resolution of a target name is the
 * proxy's, and a sandbox that could resolve names for itself could learn addresses without the proxy's
 * decision being involved at all.
 */
public final class DockerEgressExecutions implements EgressExecutions {

    private final DockerClient docker;

    private final EgressDeployment deployment;

    private final String generation;

    private final EgressMetrics metrics;

    public DockerEgressExecutions(DockerClient docker, EgressDeployment deployment, String generation) {
        this(docker, deployment, generation, new EgressMetrics());
    }

    public DockerEgressExecutions(
            DockerClient docker, EgressDeployment deployment, String generation, EgressMetrics metrics) {
        this.docker = docker;
        this.deployment = deployment;
        this.generation = generation;
        this.metrics = metrics;
    }

    /** What this runner has observed about its own egress. Categories only; see {@link EgressMetrics}. */
    public EgressMetrics metrics() {
        return metrics;
    }

    @Override
    public Duration maximumRevocationLatency() {
        return deployment.maximumRevocationLatency();
    }

    @Override
    public EgressExecution start(UUID correlationId, EgressPlan plan) {
        ExecutionNetwork network;
        try {
            // Verified internal WITH THE DAEMON inside create(), which destroys the network and throws if it
            // is not. A network that exists under the expected name with the wrong flag would attach
            // successfully and route freely, while every label said otherwise.
            network = ExecutionNetwork.create(docker, generation, correlationId);
        } catch (RuntimeException notIsolated) {
            metrics.proxyFailed(EgressFailure.EGRESS_NETWORK_FAILED);
            throw new EgressProxyStartFailed(
                    EgressFailure.EGRESS_NETWORK_FAILED,
                    "No isolated execution network could be created.",
                    notIsolated);
        }

        EgressProxy proxy = null;
        try {
            proxy = EgressProxy.start(
                    docker,
                    EgressProxyProfile.version1(
                            deployment.proxyImageReference(), deployment.proxyEnvironment()),
                    generation,
                    correlationId,
                    network,
                    deployment.egressNetworkIds(),
                    deployment.hostAliases());

            // The deployment's runtime, not the default. An allowlist sandbox is still a sandbox, and it
            // runs behind the same boundary every other execution on this host does.
            SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                    deployment.probeImageReference(),
                    network.name(),
                    sandboxEnvironment(proxy, network, plan),
                    deployment.sandboxRuntime());
            metrics.proxyLaunched();
            return new EgressExecution(
                    network, proxy, profile, new DockerSandboxLauncher(docker, profile, generation));
        } catch (RuntimeException failure) {
            // Everything already created goes, in the order that actually works: the proxy holds an endpoint
            // on the network, and a network with an endpoint cannot be removed.
            if (proxy != null) {
                proxy.close();
            }
            network.close();
            if (failure instanceof EgressProxyStartFailed started) {
                metrics.proxyFailed(started.failure());
                throw started;
            }
            metrics.proxyFailed(EgressFailure.EGRESS_PROXY_START_FAILED);
            throw new EgressProxyStartFailed(
                    EgressFailure.EGRESS_PROXY_START_FAILED, "The egress mechanism could not be started.",
                    failure);
        }
    }

    /**
     * The sandbox's egress environment: server-owned, built from the execution's own policy, never tenant
     * input.
     *
     * <p>The denied port is computed from the policy's own entries rather than invented. The workload has to
     * demonstrate that something is refused, and a hostname chosen for that purpose would be a guess — a
     * tenant's policy could name any hostname anyone thought of. A port absent from the delivered list is
     * refused by construction.
     */
    private static Map<String, String> sandboxEnvironment(
            EgressProxy proxy, ExecutionNetwork network, EgressPlan plan) {
        EgressTarget primary = plan.primary();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("KAAS_EGRESS_PROXY_HOST", proxy.addressOn(network.networkId()));
        environment.put("KAAS_EGRESS_PROXY_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        environment.put("KAAS_EGRESS_CAPABILITY", plan.capabilityToken());
        environment.put("KAAS_EGRESS_ALLOWED_HOST", primary.host());
        environment.put("KAAS_EGRESS_ALLOWED_PORT", String.valueOf(primary.port()));
        environment.put("KAAS_EGRESS_ALLOWED_SCHEME", primary.scheme());
        environment.put("KAAS_EGRESS_DENIED_PORT", String.valueOf(plan.unlistedPortOnPrimary()));
        return Map.copyOf(environment);
    }
}
