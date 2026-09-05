package com.kaas.api;

import com.kaas.api.shared.PersistableClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The application clock over a source that genuinely produces nanoseconds.
 *
 * <p>Imported by every suite that asserts exact representational equality between a creation response, an
 * idempotent replay, and a later read. Those assertions passed locally and failed in CI, and the difference was
 * the host: {@code Clock.systemUTC()} returns microseconds on macOS and nanoseconds on Linux, so on a developer
 * machine there was nothing to lose at the database boundary and the defect could not appear.
 *
 * <p>This makes that difference irrelevant. The source always carries sub-microsecond digits, so the
 * invariant is exercised the same way everywhere.
 *
 * <p>It wraps the source in the PRODUCTION wrapper deliberately. Substituting a plain microsecond clock would
 * make these suites pass by arranging for the problem not to arise — which is precisely what the environment
 * was already doing, and why nobody noticed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NanosecondSourceClockConfiguration {

    /**
     * A distinct bean NAME rather than an override.
     *
     * <p>Definition overriding is disabled in this application, so a method named {@code clock} would fail the
     * context outright. {@code @Primary} is what makes this the instance injected.
     */
    @Bean
    @Primary
    public Clock nanosecondSourceClock() {
        Clock system = Clock.systemUTC();
        Clock nanosecondSource = new Clock() {
            @Override
            public ZoneId getZone() {
                return system.getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                // Sub-microsecond detail on every reading. PostgreSQL cannot store it and would ROUND it
                // rather than truncate, which is why normalising at the persistence boundary would be wrong
                // by a microsecond and normalising at the source is not.
                return system.instant().truncatedTo(ChronoUnit.MICROS).plusNanos(789);
            }
        };
        return PersistableClock.wrapping(nanosecondSource);
    }
}
