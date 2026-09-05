package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.shared.PersistableClock;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock the application actually wires, checked in a way that does not depend on the host.
 *
 * <p>Asserting that the configured clock merely <em>produces</em> microsecond instants would be useless on
 * macOS, whose system clock already does — the assertion would hold with the wrapper removed, which is exactly
 * how the original defect survived local verification and failed in CI.
 *
 * <p>So this compares the wiring itself. {@code Clock.tick} defines equality over its base clock and tick
 * length, so a configuration that stopped wrapping — or wrapped at the wrong resolution — fails here on every
 * platform, including the one where the symptom is invisible.
 */
class ApplicationClockTest {

    @Test
    @DisplayName("the application clock is wrapped at a resolution PostgreSQL can store")
    void theApplicationClockIsPersistable() {
        Clock configured = new ApplicationConfiguration().clock();

        assertThat(configured)
                .as("the clock bean must be the system clock wrapped for storable precision")
                .isEqualTo(PersistableClock.wrapping(Clock.systemUTC()));

        // Anti-vacuity: the comparison must be capable of failing. An unwrapped system clock must NOT satisfy
        // it, or the assertion above would hold for any configuration at all.
        assertThat(PersistableClock.wrapping(Clock.systemUTC()))
                .as("the wrapper must be distinguishable from the raw system clock")
                .isNotEqualTo(Clock.systemUTC());
    }
}
