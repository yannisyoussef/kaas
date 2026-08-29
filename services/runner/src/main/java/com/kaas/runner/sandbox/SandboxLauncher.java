package com.kaas.runner.sandbox;

/**
 * The trusted boundary between a container runtime and everything else.
 *
 * <p>Two things make this trustworthy, and both are structural rather than procedural. Its input names a
 * probe and a profile version and nothing else, so there is no container setting a caller could reach. And it
 * lives in a module the control plane cannot depend on, verified by the build, so the process that handles
 * tenant requests never acquires the ability to talk to a daemon.
 *
 * <p>The distinction the whole design rests on is between the <em>trusted launcher process</em>, which holds
 * daemon access, and the <em>untrusted sandbox process</em>, which holds none. Handing a sandbox a daemon
 * socket would collapse that distinction completely: a container with the socket is a root shell on the host
 * wearing a container's clothes.
 */
public interface SandboxLauncher {

    /**
     * Runs one synthetic probe to completion, termination, or deadline, and cleans up afterwards.
     *
     * <p>Never throws for a workload's behaviour. A probe that is killed by a memory ceiling, terminated at
     * its deadline, or truncated for talking too much has behaved exactly as the boundary intends, and the
     * outcome reports that rather than raising. Exceptions are reserved for the launcher failing.
     */
    SandboxOutcome run(SandboxLaunchRequest request);

    /** The profile every sandbox this launcher creates is bound to. */
    SandboxSecurityProfile profile();
}
