package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.ScheduleDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The production trigger that moves runs from CREATED to QUEUED. It adds no scheduling semantics of its own: it
 * finds eligible runs and invokes the established {@link RunSchedulingService} use case, so every existing
 * invariant, compare-and-set, and database guard still applies.
 *
 * <p>Deliberately kept separate from outbox publication. Scanning for CREATED runs and talking to a broker are
 * different failure domains, and combining them would put broker latency inside a database transaction.
 *
 * <p>Multiple API replicas may run this concurrently: the compare-and-set means at most one wins per run and the
 * losers observe ALREADY_SCHEDULED without writing anything.
 */
@Component
public class PendingRunScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingRunScheduler.class);

    /**
     * Runs that just failed, held out of the next few passes. The batch is ordered by creation time, so without
     * this a handful of deterministically failing runs would occupy every batch and starve all newer runs. This
     * is a local starvation guard, not durable state: a restart or another replica simply retries sooner, which
     * is safe because scheduling is idempotent. Durable per-run backoff is deferred.
     */
    private final Map<UUID, Instant> cooldown = new ConcurrentHashMap<>();

    private final RunSchedulingRepository scheduling;
    private final RunSchedulingService scheduler;
    private final int batchSize;
    private final Duration cooldownAfterFailure = Duration.ofSeconds(30);

    public PendingRunScheduler(
            RunSchedulingRepository scheduling,
            RunSchedulingService scheduler,
            @Value("${kaas.scheduling.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Scheduling batch size must be bounded between 1 and 1000.");
        }
        this.scheduling = scheduling;
        this.scheduler = scheduler;
        this.batchSize = batchSize;
    }

    /**
     * Schedules one bounded batch. Each run is scheduled in its own transaction so that one failure cannot roll
     * back the others, and a run that another replica already took is simply skipped.
     *
     * @return how many runs this pass transitioned to QUEUED
     */
    public int scheduleDue() {
        int scheduled = 0;
        Instant now = Instant.now();
        cooldown.values().removeIf(until -> until.isBefore(now));
        for (SchedulableRun pending : scheduling.findSchedulable(batchSize)) {
            if (cooldown.containsKey(pending.runId())) {
                continue;
            }
            try {
                var result = scheduler.schedule(pending.organizationId(), pending.runId(), pending.runVersion());
                if (result.disposition() == ScheduleDisposition.SCHEDULED) {
                    scheduled++;
                }
            } catch (RuntimeException failure) {
                // The run stays CREATED and is retried after a cooldown; nothing partial can have committed.
                cooldown.put(pending.runId(), now.plus(cooldownAfterFailure));
                LOGGER.atWarn()
                        .addKeyValue("event", "RUN_SCHEDULING_FAILED")
                        .addKeyValue("runId", pending.runId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("Deferred scheduling of a CREATED run");
            }
        }
        return scheduled;
    }
}
