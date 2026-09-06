package com.kaas.runner.sandbox;

import com.kaas.runner.authority.ExecutionAuthority;

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
     * <p><strong>The authority is checked while the workload runs, not only before it starts.</strong> A
     * sandbox whose assignment is cancelled, fenced, or whose lease can no longer be assumed valid is
     * terminated where it stands — gracefully if it cooperates, forcibly if it does not. Before this existed,
     * a workload already inside a sandbox kept running until it finished on its own or hit the profile
     * deadline, whatever the control plane had decided in the meantime.
     *
     * <p>Never throws for a workload's behaviour. A probe that is killed by a memory ceiling, terminated at
     * its deadline, or truncated for talking too much has behaved exactly as the boundary intends, and the
     * outcome reports that rather than raising. Exceptions are reserved for the launcher failing.
     */
    SandboxOutcome run(SandboxLaunchRequest request, ExecutionAuthority authority);

    /**
     * Runs a probe whose execution no assignment authorizes.
     *
     * <p>For the security gate and the contract suites, which launch sandboxes outside any run. It delegates
     * to the interruptible path with an authority that is never lost, rather than being a second way to start
     * a container — one path means the interruption machinery is exercised by every sandbox the repository
     * starts, instead of only by the tests written for it.
     */
    default SandboxOutcome run(SandboxLaunchRequest request) {
        return run(request, ExecutionAuthority.retained());
    }

    /** The profile every sandbox this launcher creates is bound to. */
    SandboxSecurityProfile profile();

    /**
     * The same launcher, whose sandboxes also carry one execution's inert tenant source.
     *
     * <p>Derived rather than constructed, so a source-bearing launcher differs from this one in exactly one
     * respect. A caller assembling a second launcher from scratch could differ in others -- a runtime, a
     * generation, an image -- and the difference would not be visible at the call site.
     */
    SandboxLauncher withSource(SandboxSecurityProfile.SourceDelivery delivery);
}
