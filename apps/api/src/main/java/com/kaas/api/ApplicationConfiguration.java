package com.kaas.api;

import com.kaas.api.execution.domain.SecretValueProvider;
import com.kaas.api.execution.domain.UnavailableSecretValueProvider;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
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
