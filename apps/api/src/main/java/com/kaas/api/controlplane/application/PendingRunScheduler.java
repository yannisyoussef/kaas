package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.AdmissionPolicy;
import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.SchedulingAttempt;
import com.kaas.api.controlplane.domain.SchedulingBackoff;
import com.kaas.api.controlplane.domain.SchedulingFailure;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.ScheduleDisposition;
import com.kaas.api.controlplane.domain.ScheduleRunResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The production trigger that moves runs from CREATED to QUEUED. It adds no scheduling semantics of its own: it
 * finds eligible runs and invokes the established {@link RunSchedulingService} use case, so every existing
 * invariant, compare-and-set, and database guard still applies.
 *
 * <p>Eligibility and retry delay are durable. A restart used to erase an in-process cooldown and immediately
 * retry a run that had just failed; the delay now lives in {@code run_scheduling_control}, so it survives a
 * restart and is shared by every replica.
 *
 * <p>Queue capacity is enforced here rather than at run creation. A tenant may hold CREATED intent beyond its
 * queue ceiling — that costs one row — but turning that intent into a queued run costs an attempt, a dispatch, a
 * durable outbox record, and a broker message. Deferring at this boundary is what stops the amplification.
 *
 * <p>Deliberately kept separate from outbox publication. Scanning for CREATED runs and talking to a broker are
 * different failure domains, and combining them would put broker latency inside a database transaction.
 */
@Component
public class PendingRunScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingRunScheduler.class);

    private final RunSchedulingRepository scheduling;
    private final RunSchedulingService scheduler;
    private final SchedulingControlRepository control;
    private final AdmissionRepository admission;
    private final AdmissionPolicy admissionPolicy;
    private final SchedulingBackoff backoff;
    private final int batchSize;
    private final double backoffJitter;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;

    /** Refreshed by the scheduler's own pass so the gauge never runs a query on the metrics-scrape thread. */
    private final AtomicLong quarantinedRuns = new AtomicLong();

    public PendingRunScheduler(
            RunSchedulingRepository scheduling,
            RunSchedulingService scheduler,
            SchedulingControlRepository control,
            AdmissionRepository admission,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters,
            @Value("${kaas.scheduling.batch-size}") int batchSize,
            @Value("${kaas.admission.max-active-runs-per-organization}") int maxActiveRuns,
            @Value("${kaas.admission.max-queued-runs-per-organization}") int maxQueuedRuns,
            @Value("${kaas.scheduling.backoff.max-failures}") int maxFailures,
            @Value("${kaas.scheduling.backoff.base-delay}") Duration baseDelay,
            @Value("${kaas.scheduling.backoff.max-delay}") Duration maxDelay,
            @Value("${kaas.scheduling.backoff.jitter}") double backoffJitter) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Scheduling batch size must be bounded between 1 and 1000.");
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw new IllegalArgumentException("Backoff jitter must be a fraction between 0 and 1.");
        }
        this.scheduling = scheduling;
        this.scheduler = scheduler;
        this.control = control;
        this.admission = admission;
        this.admissionPolicy = new AdmissionPolicy(maxActiveRuns, maxQueuedRuns);
        this.backoff = new SchedulingBackoff(maxFailures, baseDelay, maxDelay);
        this.batchSize = batchSize;
        this.backoffJitter = backoffJitter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.meters = meters;
        meters.gauge("kaas.scheduler.quarantined", quarantinedRuns, AtomicLong::get);
    }

    /**
     * Schedules one bounded batch. Each run is scheduled in its own transaction so one failure cannot roll back
     * the others, and a run another replica already took is simply skipped.
     *
     * @return how many runs this pass transitioned to QUEUED
     */
    public int scheduleDue() {
        int scheduled = 0;
        for (SchedulableRun pending : scheduling.findSchedulable(
                batchSize, admissionPolicy.maxQueuedRunsPerOrganization())) {
            try {
                if (schedule(pending)) {
                    scheduled++;
                }
            } catch (RuntimeException failure) {
                // One run must not abandon the rest of the batch. This catches failures outside the scheduling
                // call itself — the capacity probe, or writing the backoff row — which would otherwise lose
                // every remaining run in the pass.
                LOGGER.atWarn()
                        .addKeyValue("event", "RUN_SCHEDULING_PASS_ERROR")
                        .addKeyValue("runId", pending.runId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("Skipped a run after an error outside the scheduling transaction");
            }
        }
        try {
            quarantinedRuns.set(control.countQuarantined(RunLifecycle.CREATED));
        } catch (RuntimeException unavailable) {
            // A stale gauge is better than a failing metrics scrape during a database outage.
        }
        return scheduled;
    }

    private boolean schedule(SchedulableRun pending) {
        try {
            // The capacity check, the count, and the transition share one transaction and one organization lock.
            // Selection already skips saturated organizations, but that read is advisory: without the lock two
            // replicas at the ceiling minus one would each observe the same count and both schedule, and each
            // overshoot is a real attempt, dispatch, outbox row, and broker message.
            var result = transactions.execute(status -> {
                admission.lockOrganization(pending.organizationId());
                if (!admissionPolicy.admitsAnotherQueuedRun(admission.countQueuedRuns(pending.organizationId()))) {
                    return null;
                }
                var scheduled = scheduler.schedule(
                        pending.organizationId(), pending.runId(), pending.runVersion());
                if (scheduled.disposition() == ScheduleDisposition.SCHEDULED) {
                    // Cleared inside the transition, not after it. Clearing afterwards left a window — and, if
                    // the process died or the delete failed, a permanent state — in which a scheduler-written
                    // control row described a run that was already QUEUED. That row is now the reaper's to read:
                    // it would withhold an expired run until next_attempt_at, and a failed clear would record a
                    // scheduling failure against a run that had in fact been scheduled, quarantining it and
                    // leaving it QUEUED, past its deadline, un-reapable, holding admission capacity forever.
                    return new Scheduled(scheduled, control.clear(pending.runId()));
                }
                return new Scheduled(scheduled, false);
            });
            if (result == null) {
                defer(pending);
                return false;
            }
            if (result.outcome().disposition() == ScheduleDisposition.SCHEDULED) {
                if (result.backoffCleared()) {
                    LOGGER.atInfo()
                            .addKeyValue("event", "RUN_SCHEDULING_RECOVERED")
                            .addKeyValue("runId", pending.runId())
                            .log("A previously deferred run was scheduled and its backoff state was cleared");
                }
                return true;
            }
            if (result.outcome().run().lifecycleState() == RunLifecycle.CREATED) {
                // A disposition that leaves the run CREATED and selectable would be re-armed instantly by
                // clearing, so it backs off instead. No such disposition exists today; this makes sure adding
                // one cannot silently create a hot loop.
                record(pending, SchedulingFailure.TRANSIENT, "SCHEDULING_CONFLICT", false);
                return false;
            }
            // The run moved on: another replica queued it, or it was cancelled between selection and scheduling.
            // findSchedulable will not return it again, so clearing removes stale state rather than re-arming.
            control.clear(pending.runId());
            return false;
        } catch (IllegalArgumentException impossible) {
            // Trusted input that cannot be valid. Retrying is guaranteed waste, so quarantine immediately
            // rather than hammering it until a bounded counter happens to run out.
            record(pending, SchedulingFailure.PERMANENT, "INVALID_RUN_STATE", true);
            return false;
        } catch (RuntimeException failure) {
            // The run stays CREATED and nothing partial can have committed. It becomes eligible again only
            // after a durable delay, so a restart cannot turn a failing run into a hot loop.
            record(pending, SchedulingFailure.TRANSIENT, failureCode(failure), false);
            return false;
        }
    }

    /** One scheduling attempt's outcome plus whether it also removed durable backoff, both decided atomically. */
    private record Scheduled(ScheduleRunResult outcome, boolean backoffCleared) {}

    private void defer(SchedulableRun pending) {
        control.recordAttempt(SchedulingAttempt.of(
                pending, SchedulingFailure.QUEUE_CAPACITY, "QUEUE_CAPACITY", false, backoff, jitter()));
        count("kaas.scheduler.deferred", "QUEUE_CAPACITY");
        LOGGER.atInfo()
                .addKeyValue("event", "RUN_SCHEDULING_DEFERRED")
                .addKeyValue("organizationId", pending.organizationId())
                .addKeyValue("runId", pending.runId())
                .addKeyValue("reason", "QUEUE_CAPACITY")
                .log("Held a run at CREATED because the organization's queue is full");
    }

    private void record(SchedulableRun pending, SchedulingFailure failure, String failureCode, boolean permanent) {
        // The database derives the count, the delay, and the quarantine decision in one statement, so two
        // replicas cannot compute a shorter delay or a later quarantine than the stored count warrants.
        var outcome = control.recordAttempt(
                SchedulingAttempt.of(pending, failure, failureCode, permanent, backoff, jitter()));
        int failureCount = outcome.failureCount();
        boolean quarantined = outcome.quarantined();
        count("kaas.scheduler.failures", quarantined ? "QUARANTINED" : failure.name());
        LOGGER.atWarn()
                .addKeyValue("event", quarantined ? "RUN_SCHEDULING_QUARANTINED" : "RUN_SCHEDULING_FAILED")
                .addKeyValue("organizationId", pending.organizationId())
                .addKeyValue("runId", pending.runId())
                .addKeyValue("failureCode", failureCode)
                .addKeyValue("failureCount", failureCount)
                .log(quarantined
                        ? "Run scheduling is quarantined and needs an operator; the run remains CREATED"
                        : "Deferred scheduling of a CREATED run");
    }

    /**
     * Spreads a backlog that all failed in the same pass, so it does not re-converge on one instant. The relay
     * needs this for the same reason and for the same failure shape.
     */
    private double jitter() {
        return backoffJitter == 0 ? 1.0 : 1.0 + ThreadLocalRandom.current().nextDouble(backoffJitter);
    }

    /** Bounded, low-cardinality reasons. Exception text would be unbounded and is never used as a label. */
    private static String failureCode(RuntimeException failure) {
        if (failure instanceof org.springframework.dao.DataAccessException) {
            return "DATABASE_UNAVAILABLE";
        }
        if (failure instanceof IllegalStateException) {
            return "SCHEDULING_CONFLICT";
        }
        return "INTERNAL_ERROR";
    }

    private void count(String name, String reason) {
        Counter.builder(name).tag("reason", reason).register(meters).increment();
    }
}
