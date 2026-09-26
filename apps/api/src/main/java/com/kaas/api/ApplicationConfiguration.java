package com.kaas.api;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    /**
     * Microsecond precision, because that is what PostgreSQL can store.
     *
     * <p>A raw {@code Clock.systemUTC()} yields nanoseconds on Linux, and every server-generated instant here
     * is persisted and later read back. The value returned at creation would then differ from the value durably
     * committed, and an idempotent replay of an unchanged resource would return a different representation than
     * the original — see {@link com.kaas.api.shared.PersistableClock} for why this is normalised at the source
     * rather than at each persistence boundary.
     */
    @Bean
    Clock clock() {
        return com.kaas.api.shared.PersistableClock.wrapping(Clock.systemUTC());
    }
}
