package com.kaas.api.deployment;

import com.kaas.api.secrets.domain.SecretTransit;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The secret provider's availability, reported on its own and kept OUT of readiness.
 *
 * <p>A sealed or unreachable Vault blocks secret-bearing executions and nothing else, so it must not take the
 * API out of service: the production profile places this indicator in its own {@code secrets} health group and
 * leaves it out of {@code readiness}. It reports a status word only -- never the provider's address.
 */
@Component("secretProvider")
class SecretProviderHealthIndicator implements HealthIndicator {
    private final SecretTransit secrets;

    SecretProviderHealthIndicator(SecretTransit secrets) {
        this.secrets = secrets;
    }

    @Override
    public Health health() {
        SecretTransit.ProviderState state = secrets.state();
        return switch (state) {
            case AVAILABLE -> Health.up().withDetail("state", state.name()).build();
            case UNCONFIGURED -> Health.unknown().withDetail("state", state.name()).build();
            default -> Health.down().withDetail("state", state.name()).build();
        };
    }
}
