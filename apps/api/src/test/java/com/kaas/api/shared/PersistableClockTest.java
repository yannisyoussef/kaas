package com.kaas.api.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock property, proved against a clock that genuinely produces nanoseconds.
 *
 * <p>This is deliberately NOT a test of {@code Clock.systemUTC()}. macOS returns microseconds and Linux returns
 * nanoseconds, so a test that asserted the property against the host clock would pass on a developer machine
 * whatever the production code did — which is precisely how the defect this guards against reached CI. The
 * source clock here is fixed and nanosecond-bearing, so the test means the same thing everywhere.
 */
class PersistableClockTest {

    /** A clock that returns exactly what it is told to, nanoseconds and all. */
    private static Clock fixedAt(String instant) {
        Instant value = Instant.parse(instant);
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("every instant it yields is one PostgreSQL can store exactly")
    void everyInstantIsStorable() {
        // Sub-microsecond digits across the whole range, including the boundary values a naive implementation
        // gets right by accident: .000000001 (would round to zero), .999999999 (would round up into the next
        // second), and a value already at microsecond precision (must be left alone).
        List<String> sources = List.of(
                "2026-09-05T00:13:21.057577789Z",
                "2026-09-05T00:13:21.000000001Z",
                "2026-09-05T00:13:21.999999999Z",
                "2026-09-05T00:13:21.123456000Z",
                "2026-09-05T00:13:21.000000000Z");

        for (String source : sources) {
            Instant produced = PersistableClock.wrapping(fixedAt(source)).instant();
            assertThat(produced.getNano() % 1_000)
                    .as("%s must yield an instant PostgreSQL can store without altering it", source)
                    .isZero();
            // Never later than the real instant: a clock that rounded up could report a time that has not
            // happened yet, and several guards in this schema bound an application instant against the
            // database clock.
            assertThat(produced).isBeforeOrEqualTo(Instant.parse(source));
            // And never more than one microsecond earlier, so the coarsening cannot accumulate into drift.
            assertThat(Duration.between(produced, Instant.parse(source)))
                    .isLessThan(Duration.ofNanos(1_000));
        }
    }

    @Test
    @DisplayName("a value already at microsecond precision passes through untouched")
    void microsecondValuesAreUnchanged() {
        // The no-op case matters: it is what makes the wrapper safe to apply everywhere, and it is why the
        // fix is invisible on a host whose clock is already microsecond-precision.
        Instant already = Instant.parse("2026-09-05T00:13:21.123456Z");
        assertThat(PersistableClock.wrapping(fixedAt(already.toString())).instant()).isEqualTo(already);
    }

    @Test
    @DisplayName("the wrapper is what provides the property, not the host clock")
    void theHostClockIsNotWhatMakesThisPass() {
        // Anti-vacuity. If this assertion ever fails, the source clock in these tests has stopped producing
        // nanoseconds and every assertion above has quietly become trivial.
        assertThat(fixedAt("2026-09-05T00:13:21.057577789Z").instant().getNano() % 1_000)
                .as("the source clock must genuinely carry sub-microsecond digits")
                .isNotZero();
    }
}
