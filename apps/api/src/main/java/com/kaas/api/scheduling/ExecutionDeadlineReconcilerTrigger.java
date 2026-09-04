package com.kaas.api.scheduling;

import com.kaas.api.execution.application.ExecutionDeadlineReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically reclaims runs whose execution phase has outlived its deadline. Bounded batch, fixed delay. */
@Component
@ConditionalOnProperty(name = "kaas.execution.reconcile.enabled", havingValue = "true", matchIfMissing = true)
class ExecutionDeadlineReconcilerTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionDeadlineReconcilerTrigger.class);

    private final ExecutionDeadlineReconciler reconciler;

    ExecutionDeadlineReconcilerTrigger(ExecutionDeadlineReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            fixedDelayString = "${kaas.execution.reconcile.interval}",
            initialDelayString = "${kaas.execution.reconcile.initial-delay}")
    void reconcileDeadlines() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException failure) {
            // Never let one pass kill the timer; the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "EXECUTION_DEADLINE_RECONCILIATION_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Execution deadline reconciliation pass failed");
        }
    }
}
