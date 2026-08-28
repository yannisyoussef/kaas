package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record TestRun(
        UUID runId,
        UUID projectId,
        long runVersion,
        RunLifecycle lifecycleState,
        CancellationStatus cancellationStatus,
        TestOutcome testOutcome,
        InfrastructureOutcome infrastructureOutcome,
        QualityGateStatus qualityGateStatus,
        TerminationReason terminationReason,
        TerminationPhase terminationPhase,
        String snapshotDigest,
        Instant queueStartedAt,
        Instant queueDeadlineAt,
        Instant cancellationRequestedAt,
        Instant cancellationAcknowledgedAt,
        Instant completedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static TestRun created(
            UUID runId, UUID projectId, String snapshotDigest, String createdBy, Instant now) {
        return new TestRun(
                runId,
                projectId,
                1,
                RunLifecycle.CREATED,
                CancellationStatus.NOT_REQUESTED,
                null,
                null,
                QualityGateStatus.NOT_EVALUATED,
                null,
                null,
                snapshotDigest,
                null,
                null,
                null,
                null,
                null,
                createdBy,
                now,
                now);
    }

    public TestRun queued(Instant startedAt, Instant deadlineAt) {
        if (lifecycleState != RunLifecycle.CREATED || !lifecycleState.canTransitionTo(RunLifecycle.QUEUED)) {
            throw new IllegalStateException("Only a CREATED run can be queued.");
        }
        if (startedAt == null || deadlineAt == null || !deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Queue timing is required.");
        }
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), RunLifecycle.QUEUED, cancellationStatus,
                testOutcome, infrastructureOutcome, qualityGateStatus, terminationReason, terminationPhase,
                snapshotDigest, startedAt, deadlineAt, cancellationRequestedAt, cancellationAcknowledgedAt,
                completedAt, createdBy, createdAt, startedAt);
    }

    /**
     * Ends a run that no worker has taken. Cancelling unowned work needs no STOPPING phase and no cooperation
     * from anyone, so the request and its acknowledgement are the same transition — the run is already stopped by
     * the time the caller is told anything.
     *
     * <p>{@code requestedAt} is when the tenant asked and {@code at} is when the control plane made it true. They
     * are separate because the audit trail should not claim the two happened at the same instant when the second
     * is stamped by the database clock.
     */
    public TestRun cancelled(Instant requestedAt, Instant at) {
        if (requestedAt == null || at == null || at.isBefore(requestedAt)) {
            throw new IllegalArgumentException("A cancellation cannot be acknowledged before it was requested.");
        }
        return terminated(TerminationReason.USER_REQUESTED, requestedAt, at);
    }

    /** Ends a run whose queue deadline passed. This is an expiry, never a cancellation, and nobody requested it. */
    public TestRun expired(Instant at) {
        if (lifecycleState != RunLifecycle.QUEUED) {
            throw new IllegalStateException("Only a QUEUED run can reach its queue deadline.");
        }
        if (at == null || queueDeadlineAt == null || at.isBefore(queueDeadlineAt)) {
            throw new IllegalArgumentException("A run may only expire at or after its queue deadline.");
        }
        return terminated(TerminationReason.QUEUE_DEADLINE, null, at);
    }

    private TestRun terminated(TerminationReason reason, Instant requestedAt, Instant at) {
        if (!lifecycleState.canTransitionTo(RunLifecycle.COMPLETED)) {
            throw new IllegalStateException("This run cannot be terminated from " + lifecycleState + ".");
        }
        if (lifecycleState != RunLifecycle.CREATED && lifecycleState != RunLifecycle.QUEUED) {
            // Everything past QUEUED is owned by a worker, and stopping owned work is not this slice's to invent.
            throw new IllegalStateException("Only unowned work can be terminated early.");
        }
        if (cancellationStatus != CancellationStatus.NOT_REQUESTED) {
            throw new IllegalStateException("This run has already been asked to stop.");
        }
        if (at.isBefore(updatedAt)) {
            // The aggregate's audit stamps only ever move forward, and the database enforces the same thing. The
            // service clamps for this, but an invariant the aggregate relies on someone else to keep is one
            // refactor away from becoming a trigger exception surfacing as a 500.
            throw new IllegalArgumentException("A run cannot end before its own last update.");
        }
        boolean cancelling = reason == TerminationReason.USER_REQUESTED;
        return new TestRun(
                runId,
                projectId,
                Math.addExact(runVersion, 1),
                RunLifecycle.COMPLETED,
                cancelling ? CancellationStatus.ACKNOWLEDGED : CancellationStatus.NOT_REQUESTED,
                TestOutcome.NOT_AVAILABLE,
                reason.infrastructureOutcome(),
                // Nothing ran, so there is nothing for a quality gate to evaluate.
                QualityGateStatus.NOT_EVALUATED,
                reason,
                reason.phase(),
                snapshotDigest,
                queueStartedAt,
                queueDeadlineAt,
                cancelling ? requestedAt : null,
                cancelling ? at : null,
                at,
                createdBy,
                createdAt,
                at);
    }
}
