package com.kaas.api;

import com.kaas.api.execution.domain.SecretValueProvider;
import com.kaas.api.execution.domain.UnavailableSecretValueProvider;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    /**
     * The production secret provider: one that cannot resolve anything.
     *
     * <p>{@code @ConditionalOnMissingBean} so a test can substitute a fake without this becoming a runtime
     * switch. There is no property, profile, or flag that replaces it — a deployment gets a real provider by
     * someone deliberately implementing and wiring one, which is a code change with a review, not a
     * configuration change with none.
     */
    @Bean
    @ConditionalOnMissingBean(SecretValueProvider.class)
    SecretValueProvider secretValueProvider() {
        return new UnavailableSecretValueProvider();
    }
}
