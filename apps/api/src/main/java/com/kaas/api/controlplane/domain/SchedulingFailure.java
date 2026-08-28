package com.kaas.api.controlplane.domain;

/**
 * Why a scheduling attempt did not produce a QUEUED run.
 *
 * <p>This is technical scheduler state and is deliberately unrelated to a TestRun's test outcome or its
 * infrastructure outcome. A run whose scheduling is deferred or quarantined has not failed as a test and has no
 * outcome at all: it is still CREATED, and its lifecycle is untouched.
 */
public enum SchedulingFailure {
    /** Capacity, not a fault. The run is simply held back and accrues no failures and no quarantine risk. */
    QUEUE_CAPACITY(false),
    /** A database or internal error that may well succeed later. Backed off, bounded. */
    TRANSIENT(true),
    /** Trusted input that cannot be valid, so retrying it is guaranteed waste. Quarantined immediately. */
    PERMANENT(true);

    private final boolean counted;

    SchedulingFailure(boolean counted) {
        this.counted = counted;
    }

    /** Whether this outcome advances the failure count that eventually leads to quarantine. */
    public boolean counted() {
        return counted;
    }
}
