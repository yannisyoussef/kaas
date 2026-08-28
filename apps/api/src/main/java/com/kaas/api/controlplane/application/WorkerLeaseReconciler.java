package com.kaas.api.controlplane.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The bounded way out of ownership.
 *
 * <p>Claiming created the first state a run can be stuck in that somebody owns. This is what makes sure being
 * owned is never permanent: an assignment whose lease expired and whose recovery window has passed is fenced, and
 * a run left stopping is settled. Without both, a claimed run would hold an active and a queued admission slot
 * forever — the availability defect the early-terminal slice existed to remove, recreated one state later.
 *
 * <p>It adds no lifecycle semantics of its own. It selects and calls {@link WorkerLeaseService}, so the same
 * compare-and-set and the same guards apply as when a tenant cancels an owned run.
 */
@Component
public class WorkerLeaseReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerLeaseReconciler.class);

    private final WorkerLeaseRepository leases;
    private final WorkerLeaseService lifecycle;
    private final MeterRegistry meters;
    private final int batchSize;

    public WorkerLeaseReconciler(
            WorkerLeaseRepository leases,
            WorkerLeaseService lifecycle,
            MeterRegistry meters,
            @Value("${kaas.claim.reconcile.batch-size}") int batchSize,
            @Value("${kaas.consumer.enabled}") boolean consumerEnabled,
            @Value("${kaas.claim.reconcile.enabled}") boolean reconcileEnabled) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Reconciliation batch size must be bounded between 1 and 1000.");
        }
        if (consumerEnabled && !reconcileEnabled) {
            // Claiming creates the only states that hold an admission slot with no other component able to
            // release them: the queue-deadline reaper only looks at QUEUED runs. Consuming without reconciling
            // therefore leaks capacity permanently, one claimed run at a time, and does it silently.
            throw new IllegalArgumentException(
                    "The dispatch consumer cannot run without lease reconciliation: claimed runs would hold"
                            + " admission capacity with nothing able to release them.");
        }
        this.leases = leases;
        this.lifecycle = lifecycle;
        this.meters = meters;
        this.batchSize = batchSize;
    }

    /**
     * Runs one bounded pass over both halves of recovery.
     *
     * <p>Fencing comes first so a run fenced in this pass is settled in the next one rather than in the same
     * breath. Settling immediately would be correct but would make STOPPING unobservable in practice, and a state
     * nothing can ever see is a state nobody will maintain.
     *
     * @return how many runs this pass moved
     */
    public int reconcile() {
        // The stopping set is read *before* anything is fenced, so a run fenced in this pass settles in the next
        // one. Reading it afterwards would settle a run in the same breath that started it stopping, which is
        // correct but makes STOPPING unobservable in practice — and a state nothing can ever see is a state
        // nobody maintains. It also keeps each pass's work bounded by what it found, not by what it created.
        List<UUID> stopping = leases.findStopping(batchSize);
        int moved = 0;
        for (UUID runId : leases.findExpiredLeases(batchSize)) {
            moved += attempt(runId, "FENCE", () -> lifecycle.fenceExpired(runId));
        }
        for (UUID runId : stopping) {
            moved += attempt(runId, "SETTLE", () -> lifecycle.settleStopping(runId));
        }
        return moved;
    }

    private int attempt(UUID runId, String phase, java.util.function.BooleanSupplier work) {
        try {
            return work.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException failure) {
            // One run must not abandon the rest of the batch. Nothing partial can have committed, and the run
            // stays selectable, so the next pass simply tries again.
            //
            // Counted, because a run that is selected every pass and never moves is otherwise completely
            // invisible — and a deterministically failing one stays at the head of the batch forever, since a
            // failed settle does not advance the ordering key. This counter is the only signal that the state
            // holding both admission slots has stopped draining.
            Counter.builder("kaas.claim.reconcile.failures")
                    .tag("phase", phase)
                    .tag("reason", failure instanceof org.springframework.dao.DataAccessException
                            ? "DATABASE_UNAVAILABLE"
                            : "INTERNAL_ERROR")
                    .register(meters)
                    .increment();
            LOGGER.atWarn()
                    .addKeyValue("event", "LEASE_RECONCILIATION_ERROR")
                    .addKeyValue("phase", phase)
                    .addKeyValue("runId", runId)
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .log("Skipped a run during lease reconciliation");
            return 0;
        }
    }
}
