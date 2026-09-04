package com.kaas.api.controlplane.domain;

/**
 * How far a run got before it ended.
 *
 * <p>The phase is what makes a termination reason legible months later: "timed out" means something different
 * waiting in a queue than it does mid-execution, and an operator reading an audit trail needs to know which
 * without reconstructing the lifecycle from events.
 */
public enum TerminationPhase {
    /** Never claimed. The platform did not get to it. */
    QUEUE,
    /** Claimed, but nothing was ever authorized to run. */
    CLAIM,
    /** A sandbox was being prepared. */
    PROVISIONING,
    /** A workload was running. This is also the phase a run that finished normally ended in. */
    EXECUTION,
    /** Results were being collected or processed. */
    RESULTS,
    /** A tenant asked for it to stop, from whichever phase it was in. */
    CANCELLATION
}
