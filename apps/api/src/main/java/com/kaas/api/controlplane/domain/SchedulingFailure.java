package com.kaas.api.controlplane.domain;

/**
 * Why a background pass did not produce the transition it attempted — scheduling a CREATED run, or reaping a
 * QUEUED one whose queue deadline has passed.
 *
 * <p>This is technical eligibility state and is deliberately unrelated to a TestRun's test outcome or its
 * infrastructure outcome. A run whose pass is deferred or quarantined has not failed as a test and has earned no
 * outcome: its lifecycle and version are untouched, and it stays in whatever state the pass found it in.
 */
public enum SchedulingFailure {
    /**
     * Capacity, not a fault. The run is simply held back and accrues no failures and no quarantine risk. Only
     * scheduling produces this; nothing about reaping is gated on capacity.
     */
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
