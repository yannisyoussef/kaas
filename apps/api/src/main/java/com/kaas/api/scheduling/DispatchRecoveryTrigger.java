package com.kaas.api.scheduling;

import com.kaas.api.outbox.application.DispatchRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reconsiders published dispatches the consumer never recorded (KAAS-MSG-001).
 *
 * <p>No in-memory schedule carries state across a restart: every pass starts from PostgreSQL, so after a restart
 * the first pass picks up whatever became eligible while the process was down.
 */
@Component
@ConditionalOnProperty(name = "kaas.dispatch.recovery.enabled", havingValue = "true", matchIfMissing = true)
class DispatchRecoveryTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchRecoveryTrigger.class);

    private final DispatchRecovery recovery;

    DispatchRecoveryTrigger(DispatchRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(
            fixedDelayString = "${kaas.dispatch.recovery.interval}",
            initialDelayString = "${kaas.dispatch.recovery.initial-delay}")
    void recover() {
        try {
            recovery.recoverOnce();
        } catch (RuntimeException failure) {
            // A database or broker outage must not stop the timer; claims expire and the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "DISPATCH_RECOVERY_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Dispatch recovery pass failed");
        }
    }
}
