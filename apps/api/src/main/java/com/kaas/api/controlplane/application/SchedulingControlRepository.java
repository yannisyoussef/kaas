package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.SchedulingAttempt;
import com.kaas.api.controlplane.domain.SchedulingOutcome;
import java.util.UUID;

/**
 * Durable eligibility for the background passes that act on a run. This lived in a process-local map before,
 * which a restart erased: a pass that had just backed a run off would retry it immediately on the next boot, and
 * two replicas never agreed.
 *
 * <p>Two passes share it — the scheduler, which acts on CREATED runs, and the queue-deadline reaper, which acts
 * on QUEUED ones. They cannot describe the same run at the same time, because scheduling removes the control row
 * inside the transaction that moves the run out of CREATED.
 *
 * <p>It is a separate table rather than columns on {@code test_runs} because eligibility is technical state.
 * Storing it on the aggregate would mean relaxing the guard that makes the lifecycle transitions the only
 * permitted mutations, trading a real invariant for a bookkeeping convenience.
 */
public interface SchedulingControlRepository {

    /**
     * Records a failed or deferred attempt in one statement and returns what was actually written. A deferral
     * does not advance the count; a counted failure does, and quarantines the run once the policy says so. The
     * caller must not recompute either value: the statement is authoritative.
     */
    SchedulingOutcome recordAttempt(SchedulingAttempt attempt);

    /** Removes the control row once the run has moved on, so no stale eligibility can linger. */
    boolean clear(UUID runId);

    /**
     * Quarantined runs in one lifecycle state. Scoped by state because the two passes park very different things:
     * a quarantined CREATED run is one cheap row, while a quarantined QUEUED run is holding both an active and a
     * queued admission slot and needs an operator before that capacity comes back.
     */
    long countQuarantined(RunLifecycle lifecycleState);
}
