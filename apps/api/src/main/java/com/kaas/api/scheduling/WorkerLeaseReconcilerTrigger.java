package com.kaas.api.scheduling;

import com.kaas.api.controlplane.application.WorkerLeaseReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically invokes lease recovery. Bounded batch, fixed delay, never a busy loop. */
@Component
@ConditionalOnProperty(name = "kaas.claim.reconcile.enabled", havingValue = "true", matchIfMissing = true)
class WorkerLeaseReconcilerTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerLeaseReconcilerTrigger.class);

    private final WorkerLeaseReconciler reconciler;

    WorkerLeaseReconcilerTrigger(WorkerLeaseReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            fixedDelayString = "${kaas.claim.reconcile.interval}",
            initialDelayString = "${kaas.claim.reconcile.initial-delay}")
    void reconcileLeases() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException failure) {
            // Never let one pass kill the timer; the next tick retries.
            LOGGER.atWarn()
                    .addKeyValue("event", "LEASE_RECONCILIATION_PASS_FAILED")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Lease reconciliation pass failed");
        }
    }
}
