package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.SchedulingAttempt;
import com.kaas.api.controlplane.domain.SchedulingOutcome;
import java.util.UUID;

/**
 * Durable scheduler eligibility. This lived in a process-local map before, which a restart erased: a scheduler
 * that had just backed a run off would retry it immediately on the next boot, and two replicas never agreed.
 *
 * <p>It is a separate table rather than columns on {@code test_runs} because scheduling is technical state.
 * Storing it on the aggregate would mean relaxing the guard that makes CREATED to QUEUED the only permitted
 * mutation, trading a real lifecycle invariant for a bookkeeping convenience.
 */
public interface SchedulingControlRepository {

    /**
     * Records a failed or deferred attempt in one statement and returns what was actually written. A deferral
     * does not advance the count; a counted failure does, and quarantines the run once the policy says so. The
     * caller must not recompute either value: the statement is authoritative.
     */
    SchedulingOutcome recordAttempt(SchedulingAttempt attempt);

    /** Removes the control row once the run is scheduled, so no stale eligibility can linger. */
    boolean clear(UUID runId);

    long countQuarantined();
}
