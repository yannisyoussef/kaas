package com.kaas.api.controlplane.domain;

/**
 * Why a run ended, and the two facts that follow from it.
 *
 * <p>Reason, phase, and infrastructure outcome are one fact written three ways, so they are derived here rather
 * than assembled by callers. The database enforces the same mapping independently; a caller that got it wrong
 * would be refused rather than recorded.
 *
 * <p>Note what {@link #EXECUTION_COMPLETED} adds: until execution existed, every terminal run had failed, been
 * cancelled, or timed out, so the platform had no vocabulary at all for a run that simply finished. Its
 * infrastructure outcome is SUCCEEDED, and — uniquely — it says nothing about whether the tests passed. That is
 * an orthogonal dimension carried by the result.
 */
public enum TerminationReason {
    USER_REQUESTED(TerminationPhase.CANCELLATION, InfrastructureOutcome.CANCELLED),
    QUEUE_DEADLINE(TerminationPhase.QUEUE, InfrastructureOutcome.TIMED_OUT),
    LEASE_LOST(TerminationPhase.CLAIM, InfrastructureOutcome.FAILED),
    /** A sandbox could not be prepared within the server-owned provisioning window. */
    PROVISIONING_DEADLINE(TerminationPhase.PROVISIONING, InfrastructureOutcome.TIMED_OUT),
    /** The workload ran past the execution deadline and was terminated. */
    EXECUTION_DEADLINE(TerminationPhase.EXECUTION, InfrastructureOutcome.TIMED_OUT),
    /** Execution finished but its evidence never arrived within the collection window. */
    RESULT_DEADLINE(TerminationPhase.RESULTS, InfrastructureOutcome.TIMED_OUT),
    /** The sandbox or the launcher failed. There is no test result, because no test ran to completion. */
    INFRASTRUCTURE_FAILURE(TerminationPhase.EXECUTION, InfrastructureOutcome.FAILED),
    /** Execution ran to completion and produced evidence. Whether the tests passed is a separate question. */
    EXECUTION_COMPLETED(TerminationPhase.EXECUTION, InfrastructureOutcome.SUCCEEDED);

    private final TerminationPhase phase;
    private final InfrastructureOutcome outcome;

    TerminationReason(TerminationPhase phase, InfrastructureOutcome outcome) {
        this.phase = phase;
        this.outcome = outcome;
    }

    public TerminationPhase phase() {
        return phase;
    }

    public InfrastructureOutcome infrastructureOutcome() {
        return outcome;
    }
}
