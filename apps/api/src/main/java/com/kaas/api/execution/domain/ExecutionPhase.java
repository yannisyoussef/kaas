package com.kaas.api.execution.domain;

import com.kaas.api.controlplane.domain.RunLifecycle;
import java.time.Duration;

/**
 * A phase an assigned worker may drive its own run through, and how long it has to do it.
 *
 * <p>This exists as its own enumeration rather than the service simply accepting a {@link RunLifecycle},
 * because most lifecycle states are nothing a worker may ask for. {@code QUEUED} belongs to the scheduler,
 * {@code STOPPING} to cancellation and the reconcilers, and {@code COMPLETED} is reached only by submitting
 * evidence. Accepting a bare lifecycle value on the wire would make each of those a validation rule somebody
 * has to remember to write; making the wire vocabulary smaller than the state machine means the illegal
 * requests cannot be spelled.
 *
 * <p>The budgets are deliberately different from one another and deliberately not configurable per tenant.
 * They bound how long the platform will hold admission capacity for a worker that has stopped talking, which
 * is a platform capacity decision rather than a tenant preference.
 */
public enum ExecutionPhase {

    /** Creating the sandbox. Short, because provisioning that is slow is provisioning that is broken. */
    PROVISIONING(RunLifecycle.CLAIMED, RunLifecycle.PROVISIONING, Duration.ofMinutes(2)),

    /** The workload itself, and the only phase whose length is a property of the test rather than the platform. */
    RUNNING(RunLifecycle.PROVISIONING, RunLifecycle.RUNNING, Duration.ofMinutes(30)),

    /** Reading results out of the sandbox before it is destroyed. */
    COLLECTING_RESULTS(RunLifecycle.RUNNING, RunLifecycle.COLLECTING_RESULTS, Duration.ofMinutes(2)),

    /** Digesting and submitting them. The sandbox is gone by now; this is control-plane work. */
    PROCESSING_RESULTS(RunLifecycle.COLLECTING_RESULTS, RunLifecycle.PROCESSING_RESULTS, Duration.ofMinutes(2));

    private final RunLifecycle from;
    private final RunLifecycle to;
    private final Duration budget;

    ExecutionPhase(RunLifecycle from, RunLifecycle to, Duration budget) {
        this.from = from;
        this.to = to;
        this.budget = budget;
    }

    /**
     * The state a run must already be in for this phase to be enterable.
     *
     * <p>Checked by the service against live state, not inferred from what the caller says the run is doing.
     */
    public RunLifecycle from() {
        return from;
    }

    public RunLifecycle to() {
        return to;
    }

    public Duration budget() {
        return budget;
    }
}
