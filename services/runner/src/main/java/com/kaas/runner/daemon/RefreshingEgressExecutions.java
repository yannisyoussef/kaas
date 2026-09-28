package com.kaas.runner.daemon;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import com.kaas.runner.sandbox.DockerEgressExecutions;
import com.kaas.runner.sandbox.EgressDeployment;
import com.kaas.runner.sandbox.EgressExecution;
import com.kaas.runner.sandbox.EgressExecutions;
import com.kaas.runner.sandbox.EgressFailure;
import com.kaas.runner.sandbox.EgressMetrics;
import com.kaas.runner.sandbox.EgressPlan;
import com.kaas.runner.sandbox.EgressProxyStartFailed;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * Allowlist egress whose proxy starts with the proxy's CURRENT credential (KAAS-DEPLOY-001).
 *
 * <p>{@link EgressDeployment} carries the proxy's service credential as one string, fixed when it is built, and
 * the proxy receives it in its environment when it starts. A long-lived runner holding one such deployment would
 * hand every proxy the same, eventually expired, token. This builds the deployment per execution from the proxy
 * identity's current token, and otherwise delegates entirely -- same networks, same image, same metrics.
 *
 * <p>The credential is the PROXY's ({@code kaas.egress-proxy}), a separate identity with a separate, narrower
 * authority. The runner's own credential never enters a proxy container.
 *
 * <p>A proxy holds the token it started with for the life of its execution; its lifetime must therefore exceed the
 * longest execution plus the proxy's revalidation interval, or authorization revalidation fails and the proxy
 * closes its tunnels -- closed, never open. Stated in the deployment contract.
 */
final class RefreshingEgressExecutions implements EgressExecutions {
    private final DockerClient docker;
    private final ServiceIdentity proxyIdentity;
    private final Function<String, EgressDeployment> deploymentWithCredential;
    private final String generation;
    private final EgressMetrics metrics;
    private final Duration maximumRevocationLatency;

    RefreshingEgressExecutions(DockerClient docker, ServiceIdentity proxyIdentity,
            Function<String, EgressDeployment> deploymentWithCredential, String generation, EgressMetrics metrics) {
        this.docker = docker;
        this.proxyIdentity = proxyIdentity;
        this.deploymentWithCredential = deploymentWithCredential;
        this.generation = generation;
        this.metrics = metrics;
        this.maximumRevocationLatency = deploymentWithCredential.apply("Bearer <unused>").maximumRevocationLatency();
    }

    @Override
    public EgressExecution start(UUID correlationId, EgressPlan plan) {
        String credential;
        try {
            credential = proxyIdentity.header();
        } catch (ControlPlaneUnavailable noCredential) {
            // No proxy without a valid proxy credential: the execution fails closed as a proxy that never became
            // ready, exactly as it would if the proxy had started and then been refused.
            metrics.proxyFailed(EgressFailure.EGRESS_PROXY_NOT_READY);
            throw new EgressProxyStartFailed(EgressFailure.EGRESS_PROXY_NOT_READY,
                    "The egress proxy has no valid service credential.");
        }
        return new DockerEgressExecutions(docker, deploymentWithCredential.apply(credential), generation, metrics)
                .start(correlationId, plan);
    }

    @Override
    public Duration maximumRevocationLatency() {
        return maximumRevocationLatency;
    }
}
