package com.kaas.runner.sandbox;

/**
 * One ALLOWLIST execution's egress: an internal network, a trusted proxy on it, and a launcher bound to both.
 *
 * <h2>The order, which is not negotiable</h2>
 *
 * <p>Network, then proxy, then — only if the proxy reported itself serving — a sandbox. If the proxy cannot
 * start, no sandbox is created at all. There is no degraded mode: an allowlist execution without a proxy is an
 * execution with no enforcement, and the truthful outcome is an infrastructure failure rather than a run that
 * quietly had no egress control.
 *
 * <p>Teardown reverses it, and the order matters there too: a network with an endpoint on it cannot be
 * removed, so removing the proxy first is what makes removing the network possible rather than silently
 * reclaiming nothing.
 *
 * <h2>What this object does not do</h2>
 *
 * <p>It does not police traffic and it does not react to the proxy dying. Containment is topological — the
 * sandbox's only reachable peer is the proxy, because the only network it is on has no route to anything else
 * — so it holds whether or not anybody is watching. {@link #proxyIsRunning()} exists to classify an execution
 * truthfully, never to contain it.
 */
public final class EgressExecution implements AutoCloseable {

    private final ExecutionNetwork network;

    private final EgressProxy proxy;

    private final SandboxSecurityProfile profile;

    private final SandboxLauncher launcher;

    EgressExecution(
            ExecutionNetwork network,
            EgressProxy proxy,
            SandboxSecurityProfile profile,
            SandboxLauncher launcher) {
        this.network = network;
        this.proxy = proxy;
        this.profile = profile;
        this.launcher = launcher;
    }

    /**
     * The launcher for this execution's sandbox, bound to this execution's network and nothing else.
     *
     * <p>A launcher per execution rather than one shared launcher told which network to use. The network name
     * is the whole of the isolation, so it is fixed in the profile the launcher holds, where no call site can
     * reach it.
     */
    public SandboxLauncher launcher() {
        return launcher;
    }

    /**
     * The security profile version this sandbox will run under.
     *
     * <p>The networked derivative of the attested base profile. The caller checks it against what the command
     * authorized rather than trusting it, because a sandbox on a network is a different security posture from
     * one with none, and evidence has to say which policy produced it.
     */
    public String profileVersion() {
        return profile.version();
    }

    /** Whether the proxy is still up. Used to classify an execution truthfully, never to contain it. */
    public boolean proxyIsRunning() {
        return proxy.isRunning();
    }

    /** The proxy's own output, for a failure detail. Bounded by the container's log limit. */
    String proxyLogs() {
        return proxy.logs();
    }

    @Override
    public void close() {
        // Unconditional, and in this order. Both steps are individually failure-tolerant, so a proxy that was
        // already gone does not prevent the network from being removed — the outcome this method exists to
        // produce is "neither of them is there", not "both removals returned successfully".
        proxy.close();
        network.close();
    }
}
