package com.kaas.runner.sandbox;

/**
 * Why a sandbox did not produce a usable result.
 *
 * <p>Bounded categories, never raw runtime errors. A daemon error message can carry a socket path, a host
 * directory, an image reference, or a fragment of the untrusted workload's own output — none of which belongs
 * anywhere a caller or a metric label can see it.
 */
public enum SandboxFailure {
    /** The sandbox could not be created. Configuration, image, or daemon refusal. */
    SANDBOX_CREATE_FAILED,
    /** Created but could not start. */
    SANDBOX_START_FAILED,
    /** Exceeded its wall-clock deadline and was terminated by the launcher. */
    SANDBOX_TIMEOUT,
    /**
     * The sandbox ran but the launcher lost its view of it: a daemon fault, a transport reset, or output that
     * never finished draining.
     *
     * <p>Distinct from {@link #SANDBOX_TIMEOUT} on purpose. Both once mapped to a timeout, which meant an
     * unreachable daemon two seconds in satisfied a check that existed to demonstrate a thirty-second
     * deadline. "We stopped it" and "we lost contact with it" support entirely different conclusions, and
     * nothing may be concluded from the second.
     */
    SANDBOX_OBSERVE_FAILED,
    /** Stopped by a resource ceiling: memory, processes, or output. */
    SANDBOX_RESOURCE_LIMIT,
    /** Observed doing something the profile forbids. Evidence of a boundary failure, not of a bad workload. */
    SANDBOX_SECURITY_VIOLATION,
    /** The sandbox ran but could not be removed afterwards. Always reported; never silently swallowed. */
    SANDBOX_CLEANUP_FAILED
}
