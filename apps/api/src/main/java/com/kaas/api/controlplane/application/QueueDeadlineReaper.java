package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.SchedulingAttempt;
import com.kaas.api.controlplane.domain.SchedulingBackoff;
import com.kaas.api.controlplane.domain.SchedulingFailure;
import com.kaas.api.controlplane.domain.SchedulingOutcome;
import com.kaas.api.controlplane.domain.RunLifecycle;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ends runs whose queue deadline has passed.
 *
 * <p>Without it a queue deadline is a field nothing reads. A run that no worker ever claims stays QUEUED forever,
 * holds one of its organization's queued slots and one of its active slots, and keeps a dispatch alive for work
 * that has already missed the window it was promised. The deadline was written by the scheduler from the first
 * slice; this is the first thing that enforces it.
 *
 * <p>It adds no lifecycle semantics of its own: it selects and calls {@link RunTerminationService}, so the same
 * compare-and-set, the same guards, and the same suppression apply as when a tenant cancels.
 *
 * <p>Retry state is shared with the scheduler's {@code run_scheduling_control} rather than duplicated. A run being
 * reaped has already left CREATED, so the two uses can never describe the same run at the same time, and reusing
 * the table means a run whose termination keeps failing gets a durable delay instead of being retried every tick.
 */
@Component
public class QueueDeadlineReaper {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueDeadlineReaper.class);

    private final RunTerminationRepository terminations;
    private final RunTerminationService terminator;
    private final SchedulingControlRepository control;
    private final SchedulingBackoff backoff;
    private final int batchSize;
    private final double backoffJitter;
    private final MeterRegistry meters;

    /**
     * Refreshed by the reaper's own pass. This is the highest-value standing signal the slice produces: a
     * quarantined expired run stays QUEUED, holding one active and one queued admission slot, which is exactly
     * the condition this slice exists to remove. It is separate from the scheduler's gauge because a scheduler
     * quarantine parks a CREATED row that costs almost nothing, and because the scheduler's pass never runs for
     * a QUEUED run and so would never refresh it.
     */
    private final AtomicLong quarantinedRuns = new AtomicLong();

    public QueueDeadlineReaper(
            RunTerminationRepository terminations,
            RunTerminationService terminator,
            SchedulingControlRepository control,
            MeterRegistry meters,
            @Value("${kaas.reaping.batch-size}") int batchSize,
            @Value("${kaas.reaping.backoff.max-failures}") int maxFailures,
            @Value("${kaas.reaping.backoff.base-delay}") Duration baseDelay,
            @Value("${kaas.reaping.backoff.max-delay}") Duration maxDelay,
            @Value("${kaas.reaping.backoff.jitter}") double backoffJitter) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Reaping batch size must be bounded between 1 and 1000.");
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw new IllegalArgumentException("Backoff jitter must be a fraction between 0 and 1.");
        }
        this.terminations = terminations;
        this.terminator = terminator;
        this.control = control;
        this.backoff = new SchedulingBackoff(maxFailures, baseDelay, maxDelay);
        this.batchSize = batchSize;
        this.backoffJitter = backoffJitter;
        this.meters = meters;
        meters.gauge("kaas.queue-reaper.quarantined", quarantinedRuns, AtomicLong::get);
    }

    /**
     * Reaps one bounded batch. Each run is terminated in its own transaction, so one failure cannot roll back the
     * others, and a run another replica already ended is simply skipped.
     *
     * @return how many runs this pass moved to COMPLETED
     */
    public int reapExpired() {
        int reaped = 0;
        for (SchedulableRun expired : terminations.findExpired(batchSize)) {
            try {
                if (reap(expired)) {
                    reaped++;
                }
            } catch (RuntimeException failure) {
                // One run must not abandon the rest of the batch. This catches failures outside the termination
                // call itself — writing the backoff row, or clearing stale control state — which would otherwise
                // lose every remaining run in the pass.
                LOGGER.atWarn()
                        .addKeyValue("event", "RUN_REAPING_PASS_ERROR")
                        .addKeyValue("runId", expired.runId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("Skipped a run after an error outside the termination transaction");
            }
        }
        try {
            quarantinedRuns.set(control.countQuarantined(RunLifecycle.QUEUED));
        } catch (RuntimeException unavailable) {
            // A stale gauge is better than a failing metrics scrape during a database outage.
        }
        return reaped;
    }

    private boolean reap(SchedulableRun expired) {
        try {
            if (terminator.expire(expired.organizationId(), expired.runId()).isPresent()) {
                return true;
            }
            // The run moved on: a tenant cancelled it, or another replica reaped it first. Selection will not
            // return it again, so clearing removes stale state rather than re-arming it.
            control.clear(expired.runId());
            return false;
        } catch (RuntimeException failure) {
            // Nothing partial can have committed: the run is still QUEUED and still past its deadline, so it
            // stays selectable and only a durable delay keeps the next pass from retrying it immediately.
            record(expired, SchedulingFailure.TRANSIENT, failureCode(failure), false);
            return false;
        }
    }

    private void record(SchedulableRun expired, SchedulingFailure failure, String failureCode, boolean permanent) {
        SchedulingOutcome outcome;
        try {
            outcome = control.recordAttempt(
                    SchedulingAttempt.of(expired, failure, failureCode, permanent, backoff, jitter()));
        } catch (RuntimeException unrecorded) {
            // The backoff row is the only thing standing between a failing run and a retry on every tick, and it
            // fails in exactly the situations that produce it: whatever denied expire() a connection denies this
            // one too. Rethrowing would be silent — the caller logs a generic pass error whose wording implies
            // the durable path handled it — so a missing delay gets its own signal instead.
            Counter.builder("kaas.queue-reaper.backoff-unrecorded")
                    .tag("reason", failureCode)
                    .register(meters)
                    .increment();
            LOGGER.atWarn()
                    .addKeyValue("event", "RUN_REAPING_BACKOFF_UNRECORDED")
                    .addKeyValue("organizationId", expired.organizationId())
                    .addKeyValue("runId", expired.runId())
                    .addKeyValue("failureCode", failureCode)
                    .addKeyValue("exceptionType", unrecorded.getClass().getName())
                    .log("Could not persist reaping backoff; this run stays eligible on the next tick");
            return;
        }
        boolean quarantined = outcome.quarantined();
        Counter.builder("kaas.queue-reaper.failures")
                .tag("reason", quarantined ? "QUARANTINED" : failure.name())
                .register(meters)
                .increment();
        LOGGER.atWarn()
                .addKeyValue("event", quarantined ? "RUN_REAPING_QUARANTINED" : "RUN_REAPING_FAILED")
                .addKeyValue("organizationId", expired.organizationId())
                .addKeyValue("runId", expired.runId())
                .addKeyValue("failureCode", failureCode)
                .addKeyValue("failureCount", outcome.failureCount())
                .log(quarantined
                        ? "Reaping an expired run is quarantined and needs an operator; the run remains QUEUED"
                        : "Deferred reaping of an expired run");
    }

    /** Spreads a backlog that all failed in the same pass, so it does not re-converge on one instant. */
    private double jitter() {
        return backoffJitter == 0 ? 1.0 : 1.0 + ThreadLocalRandom.current().nextDouble(backoffJitter);
    }

    /** Bounded, low-cardinality reasons. Exception text would be unbounded and is never used as a label. */
    private static String failureCode(RuntimeException failure) {
        if (failure instanceof org.springframework.dao.DataAccessException) {
            return "DATABASE_UNAVAILABLE";
        }
        return "INTERNAL_ERROR";
    }
}
