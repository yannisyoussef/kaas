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
    /**
     * The runtime this sandbox was authorized to run under is not available on this host, or the daemon
     * assigned a different one.
     *
     * <p>Distinct from {@link #SANDBOX_CREATE_FAILED} because it is not a launch problem, it is a
     * <em>security</em> condition: the platform cannot confine this workload the way its authorization
     * requires. Folding the two together would tell an operator to look at the image or the daemon when the
     * answer is that the host is missing a runtime.
     *
     * <p>It is a refusal in every case and never a downgrade. A workload authorized for a mediating runtime
     * that ran under the baseline would look identical in every observable respect except the only one that
     * mattered — every mandatory control would genuinely pass, the result would be submitted, and the run
     * would complete inside a boundary ADR-022 says is not sufficient.
     */
    SANDBOX_RUNTIME_UNAVAILABLE,

    /**
     * The daemon created the sandbox under a different runtime than the profile requires.
     *
     * <p>Distinct from {@link #SANDBOX_RUNTIME_UNAVAILABLE}, and deliberately so. "This host does not have
     * that runtime" is a deployment gap an operator fixes by installing it. "The daemon assigned a runtime
     * other than the one requested" means the request and the result disagree on a host that had every
     * chance to honour it, which is a far more alarming statement and sends an operator somewhere else
     * entirely.
     *
     * <p>The distinction is also what makes the read-back testable. Both conditions end in the same refusal
     * with no surviving container, so a launcher that quietly requested the wrong runtime would produce an
     * outcome indistinguishable from a host that simply lacked it — the defence would still hold, and
     * nothing would be able to prove it was the defence that held.
     */
    SANDBOX_RUNTIME_MISMATCH,
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
