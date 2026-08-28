package com.kaas.api.outbox.domain;

import java.time.Duration;

/**
 * Bounded publication retry. Backoff is deterministic and jitter-free so that failure tests stay reproducible;
 * the trade-off is that many relays failing at once retry in step, which is acceptable while the pending set is
 * small and the broker is the only shared dependency.
 *
 * <p>This governs BROKER PUBLICATION only. Execution retry and Karate scenario retry are unrelated concepts.
 */
public record RetryPolicy(int maxAttempts, Duration baseBackoff, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("Publication attempts must be bounded between 1 and 100.");
        }
        if (baseBackoff.isNegative() || baseBackoff.isZero() || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("Backoff must be positive and must not exceed its own maximum.");
        }
    }

    /** True once the attempt just recorded was the last one this policy allows. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** Delay before the attempt following the one just recorded. Doubles per attempt, capped. */
    public Duration backoffAfter(int attemptsMade) {
        int steps = Math.max(0, Math.min(attemptsMade - 1, 30));
        Duration backoff = baseBackoff.multipliedBy(1L << steps);
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
