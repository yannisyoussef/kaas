package com.kaas.runner.authority;

/**
 * Elapsed time, from a source that only ever moves forwards.
 *
 * <p>A narrow type rather than {@link java.time.Clock} on purpose. {@code Clock} is a wall clock: it can step
 * backwards on an NTP correction, jump forwards across a suspend, and differs from the database's wall clock
 * by whatever has not been corrected yet. Using one to answer "how long have I been unable to renew" invites
 * exactly the bug this slice exists to close — a lease budget that a clock adjustment can silently extend.
 *
 * <p>One method, so tests can drive elapsed time deterministically instead of sleeping through it.
 */
@FunctionalInterface
public interface MonotonicClock {

    /** Nanoseconds from an arbitrary but fixed origin. Only differences are meaningful. */
    long nanoTime();

    /** The real one. */
    static MonotonicClock system() {
        return System::nanoTime;
    }
}
