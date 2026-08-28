package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.CancellationStatus;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.ExecutionDispatchPolicy;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.ScheduleDisposition;
import com.kaas.api.controlplane.domain.ScheduleRunResult;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.shared.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Internal scheduler use case. It is intentionally not exposed by an HTTP controller or polling component. */
@Service
public class RunSchedulingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunSchedulingService.class);

    /**
     * The dispatch payload is durable, digest-bound, and validated field by field by the database. It therefore
     * uses a private mapper rather than the shared web {@code ObjectMapper}, so that changing an HTTP-shaping
     * {@code spring.jackson.*} property can never alter a persisted message or break scheduling at runtime.
     */
    private static final ObjectMapper DISPATCH_MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final RunIntentRepository runs;
    private final RunSchedulingRepository scheduling;
    private final Duration queueTimeout;

    public RunSchedulingService(
            RunIntentRepository runs,
            RunSchedulingRepository scheduling,
            @Value("${kaas.scheduling.queue-timeout}") Duration queueTimeout) {
        if (queueTimeout.isNegative() || queueTimeout.isZero() || queueTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Queue timeout must be between one nanosecond and 24 hours.");
        }
        if (queueTimeout.getNano() % 1000 != 0) {
            // PostgreSQL timestamptz keeps microseconds. A finer timeout would make the persisted deadline and the
            // digested deadline disagree after a round trip.
            throw new IllegalArgumentException("Queue timeout must be a whole number of microseconds.");
        }
        this.runs = runs;
        this.scheduling = scheduling;
        this.queueTimeout = queueTimeout;
    }

    /**
     * Schedules one CREATED run. Losing a race is not an error: the loser observes {@code ALREADY_SCHEDULED} and
     * writes nothing.
     *
     * <p>READ COMMITTED is pinned deliberately. Idempotency relies on PostgreSQL re-qualifying the locked row after
     * the winner commits, so that the loser's {@code lifecycle_state = 'CREATED'} predicate stops matching. A
     * stricter isolation level would raise a serialization failure instead, and there is no retry here.
     *
     * <p>This method joins the caller's transaction, so {@code SCHEDULED} means the writes were made, not that they
     * were committed. Only the caller's commit makes the dispatch durable.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleRunResult schedule(UUID organizationId, UUID runId, long expectedRunVersion) {
        if (organizationId == null || runId == null || expectedRunVersion < 1) {
            throw new IllegalArgumentException("Trusted organization, run, and expected version are required.");
        }
        var locked = scheduling.lockCreated(organizationId, runId, expectedRunVersion);
        if (locked.isEmpty()) {
            return disposition(organizationId, runId, expectedRunVersion);
        }

        TestRun previous = locked.orElseThrow();
        Instant queueStartedAt = queueStart(previous);
        Instant queueDeadlineAt = queueStartedAt.plus(queueTimeout);
        TestRun queued = previous.queued(queueStartedAt, queueDeadlineAt);
        UUID attemptId = UUID.randomUUID();
        var attempt = ExecutionAttempt.waitingForClaim(attemptId, runId, queueStartedAt);
        var dispatch = ExecutionDispatchPolicy.create(
                UUID.randomUUID(), UUID.randomUUID(), queueStartedAt, organizationId, previous.projectId(), runId,
                queued.runVersion(), attemptId, runId, previous.snapshotDigest(), queueDeadlineAt);
        UUID lifecycleEventId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        scheduling.persistSchedule(
                organizationId, previous, queued, attempt, dispatch, lifecycleEventId, outboxId, payload(dispatch));
        logAfterCommit(organizationId, queued, attemptId);
        return new ScheduleRunResult(ScheduleDisposition.SCHEDULED, queued);
    }

    /**
     * Queue timing is owned by the database clock. It is clamped so it can never precede the run's own last update,
     * because run creation stamps its audit fields from the application clock and the two hosts can drift.
     */
    private Instant queueStart(TestRun previous) {
        Instant databaseTime = scheduling.currentDatabaseTime();
        return databaseTime.isBefore(previous.updatedAt()) ? previous.updatedAt() : databaseTime;
    }

    private static String payload(ExecutionDispatch dispatch) {
        try {
            return DISPATCH_MAPPER.writeValueAsString(dispatch);
        } catch (JacksonException exception) {
            throw new IllegalStateException("The bounded execution dispatch could not be serialized.", exception);
        }
    }

    private ScheduleRunResult disposition(UUID organizationId, UUID runId, long expectedRunVersion) {
        TestRun current = runs.findRun(organizationId, runId).orElseThrow(ApiException::notFound);
        if (current.cancellationStatus() != CancellationStatus.NOT_REQUESTED) {
            return new ScheduleRunResult(ScheduleDisposition.INVALID_STATE, current);
        }
        if (current.lifecycleState() == RunLifecycle.QUEUED) {
            // A caller that expected either the pre-transition or the post-transition version is looking at this
            // exact scheduling decision. Any other expectation belongs to a version lineage it has not observed.
            boolean sameDecision = current.runVersion() == expectedRunVersion
                    || current.runVersion() == expectedRunVersion + 1;
            return new ScheduleRunResult(
                    sameDecision ? ScheduleDisposition.ALREADY_SCHEDULED : ScheduleDisposition.STALE_VERSION, current);
        }
        if (current.lifecycleState() == RunLifecycle.CREATED && current.runVersion() != expectedRunVersion) {
            return new ScheduleRunResult(ScheduleDisposition.STALE_VERSION, current);
        }
        return new ScheduleRunResult(ScheduleDisposition.INVALID_STATE, current);
    }

    private static void logAfterCommit(UUID organizationId, TestRun run, UUID attemptId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Observability must never decide whether scheduling succeeds.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOGGER.atInfo()
                        .addKeyValue("event", "RUN_QUEUED")
                        .addKeyValue("organizationId", organizationId)
                        .addKeyValue("projectId", run.projectId())
                        .addKeyValue("runId", run.runId())
                        .addKeyValue("attemptId", attemptId)
                        .addKeyValue("runVersion", run.runVersion())
                        .log("Queued test run dispatch intent");
            }
        });
    }
}
