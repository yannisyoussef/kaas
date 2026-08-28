package com.kaas.api.scheduling;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically invokes the scheduling use case. Bounded batch, fixed delay, never a busy loop. */
@Component
@ConditionalOnProperty(name = "kaas.scheduling.auto.enabled", havingValue = "true", matchIfMissing = true)
class PendingRunSchedulerTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingRunSchedulerTrigger.class);

    private final PendingRunScheduler scheduler;

    PendingRunSchedulerTrigger(PendingRunScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(
            fixedDelayString = "${kaas.scheduling.auto.interval}",
            initialDelayString = "${kaas.scheduling.auto.initial-delay}")
    void scheduleDueRuns() {
        try {
            scheduler.scheduleDue();
        } catch (RuntimeException failure) {
            // Never let one pass kill the timer; the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "RUN_SCHEDULING_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Scheduling pass failed");
        }
    }
}
