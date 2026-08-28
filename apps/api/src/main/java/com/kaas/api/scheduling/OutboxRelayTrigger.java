package com.kaas.api.scheduling;

import com.kaas.api.outbox.application.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically drains one bounded batch of the outbox to the broker. */
@Component
@ConditionalOnProperty(name = "kaas.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayTrigger.class);

    private final OutboxRelay relay;

    OutboxRelayTrigger(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${kaas.outbox.relay.interval}",
            initialDelayString = "${kaas.outbox.relay.initial-delay}")
    void drainOutbox() {
        try {
            relay.drainOnce();
        } catch (RuntimeException failure) {
            // A broker or database outage must not stop the timer; claims expire and the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "OUTBOX_RELAY_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Outbox relay pass failed");
        }
    }
}
