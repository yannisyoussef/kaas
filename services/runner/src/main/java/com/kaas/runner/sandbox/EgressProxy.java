package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trusted egress proxy for one execution: started before the sandbox, removed after it.
 *
 * <h2>Why the ordering is not negotiable</h2>
 *
 * <p>The proxy is started and proven ready <em>before</em> the sandbox exists. Starting them together would
 * mean a window in which the workload is running with its only egress peer absent, and the workload's
 * behaviour in that window is not something the platform gets to decide. Starting the sandbox first and
 * waiting would be worse.
 *
 * <p>If the proxy cannot start, the sandbox is never created at all. There is no degraded mode: an ALLOWLIST
 * execution without a proxy is an execution with no enforcement, and the correct outcome is an infrastructure
 * failure, not a run that quietly had no egress control.
 *
 * <h2>Why proxy death does not restore connectivity</h2>
 *
 * <p>Nothing here has to react quickly to the proxy dying, and that is by design. The sandbox sits on an
 * internal network whose only route to anything is the proxy container; when that container stops, the
 * sandbox's connections fail because there is nothing there, not because something noticed and intervened.
 * The detection below exists to classify the execution truthfully, not to contain it — containment is
 * topological and holds whether or not anybody is watching.
 */
public final class EgressProxy implements AutoCloseable {

    /** Printed by the proxy once it is bound and serving. Matched exactly; a prefix would match a warning. */
    private static final String READY_MARKER = "kaas-egress-proxy listening";

    /** The port the proxy listens on inside the execution network. Fixed by the platform, not by a caller. */
    public static final int LISTEN_PORT = 3128;

    private final DockerClient docker;

    private final String containerId;

    private final EgressProxyProfile profile;

    private EgressProxy(DockerClient docker, String containerId, EgressProxyProfile profile) {
        this.docker = docker;
        this.containerId = containerId;
        this.profile = profile;
    }

    /**
     * Creates, starts, and waits for the proxy, cleaning up completely on any failure.
     *
     * @param executionNetwork the internal network the sandbox will share with it
     * @param egressNetworkIds the networks through which the proxy reaches targets, DNS, and the control
     *     plane. The sandbox is never attached to any of them, and that asymmetry is the whole of the
     *     no-bypass property — it is topological, so nothing in the sandbox's own configuration is being
     *     asked to cooperate. Which networks these are is deployment wiring the launcher supplies; a caller
     *     cannot name one, because a caller that could name a network could name the one the control plane
     *     is on.
     * @param hostAliases extra {@code name:address} entries for the proxy's own hosts file, for deployments
     *     where the control plane or resolver is reached by a name the container's resolver does not know.
     *     Launcher-supplied like everything else here, and never consulted for a tenant destination — target
     *     names are resolved by {@code TargetResolver} against the configured DNS server alone.
     */
    public static EgressProxy start(
            DockerClient docker,
            EgressProxyProfile profile,
            String generation,
            UUID correlationId,
            ExecutionNetwork executionNetwork,
            List<String> egressNetworkIds,
            List<String> hostAliases) {

        String containerId;
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    // Created on the execution-internal network. The egress network is attached separately
                    // below, because a container is created on exactly one network and joins the rest.
                    .withNetworkMode(executionNetwork.name())
                    .withReadonlyRootfs(true)
                    .withCapDrop(Capability.values())
                    .withSecurityOpts(List.of("no-new-privileges:true"))
                    .withLogConfig(new LogConfig(
                            LogConfig.LoggingType.JSON_FILE,
                            Map.of("max-size", profile.maximumLogBytes() + "b", "max-file", "1")))
                    .withMemory(profile.memoryLimitBytes())
                    .withMemorySwap(profile.memorySwapLimitBytes())
                    .withCpuQuota(profile.cpuQuotaMicroseconds())
                    .withCpuPeriod(profile.cpuPeriodMicroseconds())
                    .withPidsLimit(profile.pidsLimit())
                    // Explicitly empty, and in particular no daemon socket. A proxy that could reach the
                    // daemon would turn any proxy compromise into control of every container on the host,
                    // which is a far larger prize than the network position it already has.
                    .withBinds(List.of())
                    .withExtraHosts(hostAliases.toArray(new String[0]))
                    .withPrivileged(false)
                    .withAutoRemove(false);

            CreateContainerResponse created = docker.createContainerCmd(profile.imageReference())
                    .withHostConfig(hostConfig)
                    .withUser(profile.runAsUser())
                    .withEnv(environment(profile))
                    // Labels carry identity for reconciliation and nothing else. In particular the proxy's
                    // service credential and the execution's egress capability are in the environment, never
                    // here: a label is readable by anything that can list containers and outlives the process.
                    .withLabels(SandboxLabels.of(
                            generation, correlationId, profile.version(), SandboxLabels.RESOURCE_PROXY))
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            containerId = created.getId();
        } catch (RuntimeException refused) {
            throw new EgressProxyStartFailed(
                    EgressFailure.EGRESS_PROXY_START_FAILED, "The egress proxy could not be created.", refused);
        }

        EgressProxy proxy = new EgressProxy(docker, containerId, profile);
        try {
            for (String egressNetworkId : egressNetworkIds) {
                docker.connectToNetworkCmd().withContainerId(containerId).withNetworkId(egressNetworkId).exec();
            }
            docker.startContainerCmd(containerId).exec();
            proxy.awaitReady();
            return proxy;
        } catch (RuntimeException failure) {
            // Any failure past creation removes the container. Leaving a half-started proxy behind would be
            // leaving a gateway with no execution attached to it, which is the one artefact this whole slice
            // exists to make impossible.
            proxy.close();
            if (failure instanceof EgressProxyStartFailed started) {
                throw started;
            }
            throw new EgressProxyStartFailed(
                    EgressFailure.EGRESS_PROXY_START_FAILED, "The egress proxy could not be started.", failure);
        }
    }

    /**
     * Waits until the proxy says it is serving, or fails.
     *
     * <p>Readiness is taken from the proxy's own output rather than from a connection attempt, because the
     * launcher sits on the host and the proxy sits on an internal network the host cannot reach — which is
     * exactly the property being relied on elsewhere. The marker is printed after the listening socket is
     * bound, so it means "accepting connections", not merely "the process started".
     *
     * <p>What this does not prove is that the sandbox can reach it. Nothing observable from the host can
     * prove that, so it is proven separately by the topology suite, which runs a container on the execution
     * network and has it talk to the proxy.
     */
    private void awaitReady() {
        Instant deadline = Instant.now().plus(profile.readinessTimeout());
        while (Instant.now().isBefore(deadline)) {
            if (!isRunning()) {
                throw new EgressProxyStartFailed(
                        EgressFailure.EGRESS_PROXY_START_FAILED, "The egress proxy exited before becoming ready.");
            }
            if (logs().contains(READY_MARKER)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new EgressProxyStartFailed(
                        EgressFailure.EGRESS_PROXY_NOT_READY, "Waiting for the egress proxy was interrupted.");
            }
        }
        throw new EgressProxyStartFailed(
                EgressFailure.EGRESS_PROXY_NOT_READY, "The egress proxy did not become ready within its bound.");
    }

    /**
     * Stops the proxy without removing it.
     *
     * <p>Separate from {@link #close} so that the "the proxy died mid-run" case can be reproduced exactly:
     * the container record and its network attachments survive, so the only thing that changed is that
     * nothing is listening. Removing it instead would also tear down its endpoints, which would prove the
     * sandbox lost connectivity for a reason other than the one under test.
     */
    public void stop() {
        try {
            docker.stopContainerCmd(containerId).withTimeout(2).exec();
        } catch (RuntimeException alreadyStopped) {
            // Already down, which is the state this method exists to reach.
        }
    }

    /** Whether the proxy is still up. Used to classify an execution truthfully, never to contain it. */
    public boolean isRunning() {
        try {
            InspectContainerResponse inspected = docker.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspected.getState().getRunning());
        } catch (RuntimeException gone) {
            return false;
        }
    }

    public String containerId() {
        return containerId;
    }

    /**
     * The address this proxy holds on one of its networks.
     *
     * <p>Discovered from the daemon rather than assigned, so IPAM stays the runtime's job and nothing here
     * has to reserve or predict an address. Named per network because the proxy is on more than one and the
     * sandbox may only ever be told about the one they share.
     */
    public String addressOn(String networkId) {
        InspectContainerResponse inspected = docker.inspectContainerCmd(containerId).exec();
        for (var attachment : inspected.getNetworkSettings().getNetworks().entrySet()) {
            var network = attachment.getValue();
            if (networkId.equals(network.getNetworkID())) {
                return network.getIpAddress();
            }
        }
        throw new IllegalStateException("The proxy is not attached to the network it was asked about.");
    }

    /** The proxy's output. Bounded by the container's own log limit, so this cannot grow without end. */
    public String logs() {
        StringBuilder collected = new StringBuilder();
        try {
            docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<
                            com.github.dockerjava.api.model.Frame>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            collected.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            return collected.toString();
        }
        return collected.toString();
    }

    /** Everything the proxy runs with, built from nothing rather than inherited and filtered. */
    private static List<String> environment(EgressProxyProfile profile) {
        List<String> variables = new ArrayList<>();
        profile.environment().forEach((name, value) -> variables.add(name + "=" + value));
        return List.copyOf(variables);
    }

    @Override
    public void close() {
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException alreadyGone) {
            // Already removed, by another reconciler or by the daemon. The proxy is gone either way, which is
            // the outcome this method exists to guarantee.
        }
    }
}
