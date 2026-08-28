package com.kaas.api.scheduling;

import com.kaas.api.controlplane.application.QueueDeadlineReaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically invokes the reaping use case. Bounded batch, fixed delay, never a busy loop. */
@Component
@ConditionalOnProperty(name = "kaas.reaping.auto.enabled", havingValue = "true", matchIfMissing = true)
class QueueDeadlineReaperTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueDeadlineReaperTrigger.class);

    private final QueueDeadlineReaper reaper;

    QueueDeadlineReaperTrigger(QueueDeadlineReaper reaper) {
        this.reaper = reaper;
    }

    @Scheduled(
            fixedDelayString = "${kaas.reaping.auto.interval}",
            initialDelayString = "${kaas.reaping.auto.initial-delay}")
    void reapExpiredRuns() {
        try {
            reaper.reapExpired();
        } catch (RuntimeException failure) {
            // Never let one pass kill the timer; the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "RUN_REAPING_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Queue deadline reaping pass failed");
        }
    }
}
