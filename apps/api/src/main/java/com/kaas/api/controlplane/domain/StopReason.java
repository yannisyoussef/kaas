package com.kaas.api.controlplane.domain;

/**
 * Why a run entered STOPPING. Recorded when it does, and retained through terminalization.
 *
 * <p>It is stored rather than inferred, because the alternative is to read "stopping with no cancellation
 * request" as "the lease was lost" — a fact represented by the absence of another fact. The reconciler that
 * settles a stopping run has to know which outcome it owes, and deriving that from a silence is how the wrong
 * outcome gets written.
 */
public enum StopReason {
    USER_REQUESTED(TerminationReason.USER_REQUESTED),
    LEASE_LOST(TerminationReason.LEASE_LOST);

    private final TerminationReason terminationReason;

    StopReason(TerminationReason terminationReason) {
        this.terminationReason = terminationReason;
    }

    /** The outcome this stop owes once it settles. Fixed when the run enters STOPPING, never revisited. */
    public TerminationReason terminationReason() {
        return terminationReason;
    }
}
