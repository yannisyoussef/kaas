package com.kaas.api.execution.domain;

/**
 * Why execution was refused.
 *
 * <p>Bounded and stable, because these reach a worker and a metric label. Each says enough for an operator to
 * act and nothing about the deployment's internals: which controls a particular host cannot enforce, what a
 * capability's scope was, or whether a given identifier exists are all things a refusal deliberately does not
 * reveal.
 */
public enum ExecutionDenial {
    /** The run is not in a state where anything may execute. Covers QUEUED, STOPPING, and COMPLETED alike. */
    EXECUTION_NOT_AUTHORIZED,
    /** The attempt, epoch, or worker named is not the one that currently holds the assignment. */
    ASSIGNMENT_STALE,
    /** The assignment's lease has run out. The reconciler will fence it; nothing may execute under it now. */
    LEASE_EXPIRED,
    /** No usable sandbox security assessment is configured. Absent evidence is never a pass. */
    SECURITY_GATE_UNAVAILABLE,
    /** An assessment exists and says the sandbox does not enforce what this platform requires. */
    SECURITY_GATE_FAILED,
    /** The selected egress policy is defined but no launcher can currently prove it. */
    NETWORK_POLICY_NOT_ENFORCEABLE,
    /**
     * The snapshot is missing, unsealed, empty, or larger than a bundle may carry.
     *
     * <p>Deliberately does not claim a digest recheck. An earlier version of this comment said the snapshot
     * "no longer digests to what the run recorded", and nothing recomputed it — the snapshot's own immutability
     * triggers are what hold that property. Documenting a check that does not exist is worse than not having it,
     * because it stops anyone from adding it.
     */
    RUN_SNAPSHOT_INVALID,
    /** The run binds secrets and no production secret provider exists to satisfy them. */
    SECRET_PROVIDER_UNAVAILABLE,
    /** The capability presented has passed its expiry. */
    CAPABILITY_EXPIRED,
    /** The capability was valid once, and the state it depended on has since moved. */
    CAPABILITY_FENCED,
    /** The capability does not exist, is of the wrong type, or is not this caller's. */
    CAPABILITY_INVALID
}
