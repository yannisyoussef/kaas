package com.kaas.api.outbox.infrastructure;

import com.kaas.api.outbox.application.OutboxRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the relay's durable backlog. It exposes counts and an age only: never broker host, virtual host,
 * credentials, topology names, or any run, project, or tenant identity.
 *
 * <p>Status stays UP while the outbox is readable, even when the broker is unreachable. A stalled broker shows up
 * as a growing oldest-pending age rather than as an unhealthy control plane, because publication is asynchronous
 * and must never take the API out of service. A terminal backlog is an alerting concern, surfaced through
 * {@code kaas.outbox.terminal}, not a liveness or readiness signal.
 */
@Component
class OutboxRelayHealthIndicator implements HealthIndicator {

    private final OutboxRepository outbox;

    OutboxRelayHealthIndicator(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public Health health() {
        try {
            return Health.up()
                    .withDetail("pending", outbox.countPending())
                    .withDetail("terminal", outbox.countTerminal())
                    .withDetail("oldestPendingAgeSeconds", outbox.oldestPendingAgeSeconds())
                    .build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("reason", "OUTBOX_UNAVAILABLE").build();
        }
    }
}
