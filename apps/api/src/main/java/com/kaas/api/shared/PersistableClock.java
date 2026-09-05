package com.kaas.api.shared;

import java.time.Clock;
import java.time.Duration;

/**
 * The application clock, at a precision PostgreSQL can store exactly.
 *
 * <p>Every server-generated instant in this system is written to a {@code timestamptz} column and then read
 * back — on an idempotent replay, on a GET, on a reconciler pass. PostgreSQL stores microseconds. A Java
 * {@link java.time.Instant} carries nanoseconds. So an instant taken from a raw system clock is one the
 * database cannot represent, and the value returned to a caller at creation is not the value that was durably
 * committed.
 *
 * <p><strong>This is not cosmetic.</strong> The canonical representation of a resource has to be stable across
 * creation, replay, and retrieval — that is what makes idempotency mean anything. With a nanosecond clock the
 * creation response and every subsequent read of the same unchanged resource differ, and the API contradicts
 * itself about a resource nobody modified.
 *
 * <p><strong>Why this happens at the source rather than at the persistence boundary.</strong> The obvious
 * alternative is to truncate when writing. It is wrong, and subtly: PostgreSQL <em>rounds</em> to microseconds
 * while {@code Instant.truncatedTo} <em>truncates</em>. A value of {@code .057577789} is stored by PostgreSQL
 * as {@code .057578} and truncated by Java to {@code .057577} — so truncating at the boundary produces a
 * representation that is off by one microsecond from what was actually stored, which is the same class of bug
 * wearing a fix's clothing. Truncating at the source avoids the question entirely: the application never holds
 * a value that needs rounding, so there is nothing for the two systems to disagree about.
 *
 * <p><strong>Why it must not depend on the host.</strong> macOS returns microseconds from
 * {@code Clock.systemUTC()} and Linux returns nanoseconds. The defect this prevents was therefore invisible on
 * a developer machine and reproducible only in CI — a correctness property must not be a property of where the
 * code happens to run.
 */
public final class PersistableClock {

    /** PostgreSQL {@code timestamptz} resolution. */
    private static final Duration STORABLE_RESOLUTION = Duration.ofNanos(1_000);

    private PersistableClock() {}

    /**
     * Wraps a clock so every instant it yields can be stored and read back unchanged.
     *
     * <p>Exposed and taking its source as a parameter so the property can be proved against a clock that
     * genuinely produces nanoseconds, rather than against whichever precision the test host happens to offer.
     */
    public static Clock wrapping(Clock source) {
        return Clock.tick(source, STORABLE_RESOLUTION);
    }
}
