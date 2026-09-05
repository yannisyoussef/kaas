package com.kaas.runner.sandbox;

import java.util.UUID;

/**
 * Starts the egress mechanism for one execution.
 *
 * <p>An interface for the same reason {@link SandboxLauncher} is one: the execution loop drives a lifecycle
 * and must not be the thing that holds a Docker client. It also means the loop's ALLOWLIST branch can be
 * driven with a factory that fails, which is the only way to prove that a proxy which cannot start produces an
 * infrastructure failure and no sandbox — on a healthy host that path never runs.
 *
 * <p>There is deliberately no argument for an image, a network, a profile, or a proxy setting. Those are
 * deployment wiring held by the implementation; the only per-execution input is the plan, which is a
 * credential and a list of destinations.
 */
public interface EgressExecutions {

    /**
     * Creates the network, starts the proxy, and returns a launcher bound to both.
     *
     * @throws EgressProxyStartFailed if any of it fails. Nothing is left behind when it does — a half-started
     *     egress is a gateway with no execution attached to it, which is the artefact this whole mechanism
     *     exists to make impossible.
     */
    EgressExecution start(UUID correlationId, EgressPlan plan);

    /** How long an established tunnel can outlive the fencing of its assignment, as a polling bound. */
    java.time.Duration maximumRevocationLatency();
}
