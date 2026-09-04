package com.kaas.api.controlplane.domain;

/**
 * Why a run entered STOPPING, recorded so the reconciler that terminalizes it knows which outcome it owes.
 *
 * <p>It is a stored reason rather than an inference, because "stopping without a cancellation request" would
 * otherwise be the only evidence that something else went wrong — a fact stored as the absence of another fact,
 * which is how the wrong outcome gets written.
 *
 * <p>{@code EXECUTION_COMPLETED} is deliberately absent: a run that finished normally goes to COMPLETED through
 * PROCESSING_RESULTS carrying its evidence, and never passes through STOPPING. STOPPING means something ended
 * this run before it could finish.
 */
public enum StopReason {
    USER_REQUESTED(TerminationReason.USER_REQUESTED),
    LEASE_LOST(TerminationReason.LEASE_LOST),
    PROVISIONING_DEADLINE(TerminationReason.PROVISIONING_DEADLINE),
    EXECUTION_DEADLINE(TerminationReason.EXECUTION_DEADLINE),
    RESULT_DEADLINE(TerminationReason.RESULT_DEADLINE),
    INFRASTRUCTURE_FAILURE(TerminationReason.INFRASTRUCTURE_FAILURE);

    private final TerminationReason terminationReason;

    StopReason(TerminationReason terminationReason) {
        this.terminationReason = terminationReason;
    }

    public TerminationReason terminationReason() {
        return terminationReason;
    }
}
