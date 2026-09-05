package com.kaas.runner.sandbox;

/**
 * Why an execution's egress path did not work.
 *
 * <p>Every one of these is an <em>infrastructure</em> failure, and that classification is the point. A run
 * whose proxy could not start has produced no evidence about the tenant's tests, so reporting it as a test
 * failure would be a lie in the direction that costs a user the most: they would go looking at their own code.
 *
 * <p>Deliberately not the same enum as a policy denial. "The proxy is broken" and "the destination is not
 * allowed" are different facts with different owners, and a single category covering both would make the
 * evidence unable to tell them apart.
 */
public enum EgressFailure {
    /** The proxy image could not be built from the repository-controlled context. */
    EGRESS_PROXY_BUILD_FAILED,

    /** The container could not be created or started. */
    EGRESS_PROXY_START_FAILED,

    /** It started but never reported itself ready inside the bound. */
    EGRESS_PROXY_NOT_READY,

    /** It was ready and then exited while the execution was still running. */
    EGRESS_PROXY_DIED,

    /** The per-execution network could not be created, or was not internal. */
    EGRESS_NETWORK_FAILED,

    /** The proxy or its network could not be removed afterwards. Reported, never swallowed. */
    EGRESS_CLEANUP_FAILED
}
