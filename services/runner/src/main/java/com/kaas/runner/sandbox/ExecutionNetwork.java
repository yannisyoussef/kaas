package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-execution network a sandbox is attached to, and nothing else is.
 *
 * <p>This is the mechanism that makes an allowlist enforceable at all. A destination check in application code
 * is a suggestion if the container can open its own socket to somewhere else; the sandbox must have no route to
 * a target except through the proxy, and that has to be a property of the topology rather than of anything the
 * workload agrees to.
 *
 * <p><strong>One network per execution, never a shared one.</strong> A shared egress network would put every
 * tenant's sandbox on one broadcast domain, where reaching another tenant's sandbox or proxy needs no route out
 * at all — only a neighbour. Per-execution networks make that unreachable by construction rather than by
 * filtering.
 *
 * <p><strong>Created internal, and verified internal.</strong> Docker's {@code internal} flag is what removes
 * external forwarding. The flag is checked after creation rather than assumed from the request, because a
 * network that exists under the expected name with the wrong flag is precisely the failure that would leave
 * every sandbox on it with a working route while every label said otherwise.
 */
public final class ExecutionNetwork implements AutoCloseable {

    /**
     * The name prefix, which the launcher also matches on.
     *
     * <p>A sandbox may attach only to a network this class created. Accepting an arbitrary caller-supplied
     * network name would let a request place an untrusted container on the bridge, the host network, or another
     * execution's network, which is the whole of the isolation given away in one string.
     */
    public static final String NAME_PREFIX = "kaas-exec-";

    private final DockerClient docker;
    private final String networkId;
    private final String name;

    private ExecutionNetwork(DockerClient docker, String networkId, String name) {
        this.docker = docker;
        this.networkId = networkId;
        this.name = name;
    }

    /**
     * Creates an internal network for one execution.
     *
     * @param generation the launcher generation, recorded as a label so the orphan reconciler can find networks
     *     this platform created without matching on names it might share with something else
     */
    public static ExecutionNetwork create(DockerClient docker, String generation, UUID correlationId) {
        String name = NAME_PREFIX + correlationId;
        String id = docker.createNetworkCmd()
                .withName(name)
                // The property everything else rests on: no external forwarding for containers on this network.
                .withInternal(true)
                // Not attachable, so nothing outside this launcher can join a running execution's network.
                .withAttachable(false)
                .withLabels(Map.of(
                        SandboxLabels.MANAGED, "true",
                        SandboxLabels.GENERATION, generation,
                        SandboxLabels.CORRELATION, correlationId.toString(),
                        SandboxLabels.RESOURCE, SandboxLabels.RESOURCE_NETWORK))
                .exec()
                .getId();

        ExecutionNetwork network = new ExecutionNetwork(docker, id, name);
        try {
            network.requireInternal();
        } catch (RuntimeException notInternal) {
            // Fail closed and take the network with us. A half-created network that is not internal is worse
            // than none: a sandbox would attach to it successfully and route freely.
            network.close();
            throw notInternal;
        }
        return network;
    }

    private void requireInternal() {
        requireInternal(docker, networkId);
    }

    /**
     * Confirms with the daemon that a network really is internal.
     *
     * <p>Read back rather than trusted from the create call. The flag is the entire isolation guarantee, and a
     * guarantee nobody verifies is a comment — a network that exists under the expected name with the wrong
     * flag would attach successfully and route freely, while every label said otherwise.
     *
     * <p>Package-private and taking its network as a parameter so it can be proved against a network that is
     * genuinely not internal. Left private and reachable only from {@link #create}, it could never fire in any
     * test — creation always sets the flag — and deleting it would kill nothing.
     */
    static void requireInternal(DockerClient docker, String networkId) {
        Network inspected = docker.inspectNetworkCmd().withNetworkId(networkId).exec();
        if (!Boolean.TRUE.equals(inspected.getInternal())) {
            throw new IllegalStateException(
                    "The execution network was not created internal; a sandbox on it would have a route out.");
        }
    }

    public String name() {
        return name;
    }

    public String networkId() {
        return networkId;
    }

    /** Every network this launcher generation created that still exists. Used by orphan reconciliation. */
    public static List<Network> managedNetworks(DockerClient docker) {
        return docker.listNetworksCmd().withFilter("label", List.of(SandboxLabels.MANAGED + "=true")).exec();
    }

    @Override
    public void close() {
        try {
            docker.removeNetworkCmd(networkId).exec();
        } catch (RuntimeException alreadyGone) {
            // Another reconciler removed it, or it never fully existed. Either way it is not there now, which
            // is the outcome this method exists to produce.
        }
    }
}
