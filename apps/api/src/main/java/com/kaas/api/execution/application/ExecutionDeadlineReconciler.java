package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reclaims runs whose current execution phase has outlived its deadline.
 *
 * <p>This is what makes every phase's exit bounded. Without it, each of the four phases would be a state whose
 * only way out is a worker choosing to act — and a worker that has crashed, been partitioned, or simply hung
 * chooses nothing. The run would hold admission capacity indefinitely, which is a failure that grows quietly:
 * one stuck run is invisible, and a hundred is an outage with no obvious cause.
 *
 * <p>One reconciler for all four phases rather than four, because they differ only in which reason they record.
 * Four timers scanning one index for four disjoint predicates would be four times the work to answer one
 * question, and the three that fired least often would be the three nobody noticed had stopped.
 */
@Component
public class ExecutionDeadlineReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionDeadlineReconciler.class);

    private final ExecutionLifecycleRepository lifecycle;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public ExecutionDeadlineReconciler(
            ExecutionLifecycleRepository lifecycle,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager,
            @Value("${kaas.execution.reconcile.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            // Bounded on both sides. Zero would disable recovery while looking configured, and an unbounded
            // batch would turn one slow pass into a long-held set of row locks across unrelated tenants.
            throw new IllegalArgumentException("The execution reconcile batch size must be between 1 and 500.");
        }
        this.lifecycle = lifecycle;
        this.meters = meters;
        // An EXPLICIT transaction boundary, not @Transactional on the method below.
        //
        // That annotation was there and did nothing: stop() is called from reconcile() on the same object, so
        // the Spring proxy is bypassed entirely and each statement auto-committed on its own. Stopping a run
        // takes two writes — the run enters STOPPING and its assignment is fenced — and the scheduling-bundle
        // constraint is DEFERRED, so it is evaluated at commit. Committing the first write alone put a STOPPING
        // run with a live assignment in front of that constraint, and every deadline stop failed.
        //
        // It failed quietly, too: the per-run catch below exists so one bad run cannot abandon the batch, so
        // the only visible symptom was a reconciler that ran forever and moved nothing.
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchSize = batchSize;
    }

    /** @return how many runs this pass stopped */
    public int reconcile() {
        int moved = 0;
        for (ExecutionLifecycleRepository.OverdueRun overdue : lifecycle.findOverdue(batchSize)) {
            try {
                if (stop(overdue)) {
                    moved++;
                }
            } catch (RuntimeException failure) {
                // One run must not abandon the batch. Nothing partial can have committed and the run stays
                // selectable, so the next pass tries again.
                //
                // Counted, because a run that is selected every pass and never moves is otherwise invisible —
                // and since the ordering key is the deadline, a deterministically failing run stays at the head
                // of the batch forever, starving every run behind it. This counter is the only signal that the
                // phase holding admission capacity has stopped draining.
                Counter.builder("kaas.execution.reconcile.failures")
                        .tag("phase", overdue.lifecycleState().name())
                        .tag("reason", failure instanceof org.springframework.dao.DataAccessException
                                ? "DATABASE_UNAVAILABLE"
                                : "INTERNAL_ERROR")
                        .register(meters)
                        .increment();
                LOGGER.atWarn()
                        .addKeyValue("event", "EXECUTION_DEADLINE_RECONCILIATION_ERROR")
                        .addKeyValue("phase", overdue.lifecycleState())
                        .addKeyValue("runId", overdue.runId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("Skipped a run during execution deadline reconciliation");
            }
        }
        return moved;
    }

    boolean stop(ExecutionLifecycleRepository.OverdueRun overdue) {
        StopReason reason = reasonFor(overdue.lifecycleState());
        boolean stopped = Boolean.TRUE.equals(transactions.execute(status ->
                lifecycle.stopOverdue(
                        overdue.organizationId(), overdue.runId(), overdue.lifecycleState(), reason)));
        if (stopped) {
            Counter.builder("kaas.execution.reconcile.stopped")
                    .tag("phase", overdue.lifecycleState().name())
                    .tag("reason", reason.name())
                    .register(meters)
                    .increment();
            LOGGER.atInfo()
                    .addKeyValue("event", "EXECUTION_DEADLINE_EXCEEDED")
                    .addKeyValue("phase", overdue.lifecycleState())
                    .addKeyValue("runId", overdue.runId())
                    .addKeyValue("deadline", overdue.phaseDeadlineAt())
                    .addKeyValue("reason", reason)
                    .log("Stopped a run whose phase deadline had passed");
        }
        // Not stopped is the ordinary case, not an error: the worker advanced the phase between the scan and
        // this statement, and the run is no longer overdue.
        return stopped;
    }

    /**
     * Which deadline elapsed, named honestly.
     *
     * <p>Enumerated rather than defaulted so a new phase cannot silently inherit some other phase's reason.
     * The database checks the same pairing independently, so a mistake here fails loudly rather than being
     * recorded as a plausible-looking lie in the one place operators look to find out what broke.
     */
    private static StopReason reasonFor(RunLifecycle phase) {
        return switch (phase) {
            case PROVISIONING -> StopReason.PROVISIONING_DEADLINE;
            case RUNNING -> StopReason.EXECUTION_DEADLINE;
            case COLLECTING_RESULTS, PROCESSING_RESULTS -> StopReason.RESULT_DEADLINE;
            case CREATED, QUEUED, CLAIMED, STOPPING, COMPLETED -> throw new IllegalStateException(
                    "Not a deadline-bearing execution phase: " + phase);
        };
    }
}
