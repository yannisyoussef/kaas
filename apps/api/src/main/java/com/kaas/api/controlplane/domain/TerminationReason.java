package com.kaas.api.controlplane.domain;

/**
 * Why a run ended without executing. Each reason fixes both the phase it happened in and the infrastructure
 * outcome it produces, so the three can never be combined into a state the database would reject — and so no
 * caller has to remember that a queue timeout is not a cancellation.
 */
public enum TerminationReason {
    /** A tenant asked for the run to stop before any worker owned it. */
    USER_REQUESTED(TerminationPhase.CANCELLATION, InfrastructureOutcome.CANCELLED),
    /** The run waited longer than its queue deadline allowed. Nobody cancelled it; it simply expired. */
    QUEUE_DEADLINE(TerminationPhase.QUEUE, InfrastructureOutcome.TIMED_OUT),
    /**
     * A worker claimed the run and then stopped renewing its lease. Deliberately not a timeout: a queue deadline
     * means the platform never got to the run, while a lost lease means it did and then lost the worker holding
     * it. The two need different diagnoses, so they must not share an outcome.
     */
    LEASE_LOST(TerminationPhase.CLAIM, InfrastructureOutcome.FAILED);

    private final TerminationPhase phase;
    private final InfrastructureOutcome outcome;

    TerminationReason(TerminationPhase phase, InfrastructureOutcome outcome) {
        this.phase = phase;
        this.outcome = outcome;
    }

    public TerminationPhase phase() {
        return phase;
    }

    /**
     * The infrastructure outcome this reason produces. The test outcome is always {@link TestOutcome#NOT_AVAILABLE}
     * and the quality gate stays {@link QualityGateStatus#NOT_EVALUATED}: nothing ran, so there is nothing to
     * report and nothing to judge.
     */
    public InfrastructureOutcome infrastructureOutcome() {
        return outcome;
    }
}
