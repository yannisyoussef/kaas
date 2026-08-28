package com.kaas.api.controlplane.domain;

import java.time.Duration;

/**
 * Bounded, durable retry delay for a background pass over a run. Distinct from broker publication retry,
 * execution retry, and Karate scenario retry: this one governs only how soon a run may be offered to that pass
 * again — the scheduler's pass over CREATED runs, or the reaper's over QUEUED runs past their deadline.
 *
 * <p>Each pass configures its own instance, because they park different costs: a quarantined CREATED run is one
 * cheap row, while a quarantined QUEUED run still holds an active and a queued admission slot.
 *
 * <p>A failed pass never terminalizes the run. Infrastructure being briefly unhealthy is not a verdict on a test
 * run, so the delay grows and is eventually capped, and only a bounded number of failures moves the run into
 * quarantine for an operator to look at.
 *
 * <p>This record carries the policy; it does not evaluate it. The curve and the quarantine decision are applied
 * in the same statement that records the attempt, so two replicas cannot derive a shorter delay or a later
 * quarantine than the stored failure count warrants. Re-implementing either here would be a second source of
 * truth that nothing executes.
 */
public record SchedulingBackoff(int maxFailuresBeforeQuarantine, Duration baseDelay, Duration maxDelay) {

    public SchedulingBackoff {
        if (maxFailuresBeforeQuarantine < 1 || maxFailuresBeforeQuarantine > 100) {
            throw new IllegalArgumentException("Scheduling failures before quarantine must be between 1 and 100.");
        }
        if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("Scheduling delay must be positive and within its own maximum.");
        }
    }

}
