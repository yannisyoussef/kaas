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
        StopReason stopReason,
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
                stopReason, snapshotDigest, startedAt, deadlineAt, cancellationRequestedAt,
                cancellationAcknowledgedAt, completedAt, createdBy, createdAt, startedAt);
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

    /**
     * Hands the run to the worker instance that won the claim. This is the first state a run reaches that
     * somebody owns, which is why everything after it needs fencing to take back.
     *
     * <p>Claiming after the queue deadline is refused here as well as by the database. The reaper is entitled to
     * end a run whose deadline has passed, and a claim that slipped past it would leave two components each
     * believing they hold the run.
     */
    public TestRun claimed(Instant at) {
        if (lifecycleState != RunLifecycle.QUEUED || !lifecycleState.canTransitionTo(RunLifecycle.CLAIMED)) {
            throw new IllegalStateException("Only a QUEUED run can be claimed.");
        }
        if (cancellationStatus != CancellationStatus.NOT_REQUESTED) {
            throw new IllegalStateException("A run that has been asked to stop cannot be claimed.");
        }
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("A claim cannot precede the run's own last update.");
        }
        if (at.isAfter(queueDeadlineAt)) {
            throw new IllegalStateException("A run cannot be claimed after its queue deadline.");
        }
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), RunLifecycle.CLAIMED, cancellationStatus,
                testOutcome, infrastructureOutcome, qualityGateStatus, terminationReason, terminationPhase,
                stopReason, snapshotDigest, queueStartedAt, queueDeadlineAt, cancellationRequestedAt,
                cancellationAcknowledgedAt, completedAt, createdBy, createdAt, at);
    }

    /**
     * Begins ending a run somebody owns. Unlike unowned work this cannot finish in one step: an assignment
     * exists, and it has to be fenced before the run can be called over. No outcome is written here — the run has
     * not finished, and recording one now would let a crash leave a run claiming a result it never reached.
     *
     * @param requestedAt when a tenant asked, for a cancellation; null when the lease was simply lost
     */
    public TestRun stopping(StopReason reason, Instant requestedAt, Instant at) {
        if (lifecycleState != RunLifecycle.CLAIMED || !lifecycleState.canTransitionTo(RunLifecycle.STOPPING)) {
            throw new IllegalStateException("Only a run somebody owns can begin stopping.");
        }
        if (reason == null || at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("A stop needs a reason and cannot precede the run's last update.");
        }
        boolean cancelling = reason == StopReason.USER_REQUESTED;
        if (cancelling == (requestedAt == null)) {
            throw new IllegalArgumentException("A cancellation records when it was asked for; a lease loss does not.");
        }
        if (cancelling && cancellationStatus != CancellationStatus.NOT_REQUESTED) {
            throw new IllegalStateException("This run has already been asked to stop.");
        }
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), RunLifecycle.STOPPING,
                cancelling ? CancellationStatus.REQUESTED : cancellationStatus,
                testOutcome, infrastructureOutcome, qualityGateStatus, terminationReason, terminationPhase,
                reason, snapshotDigest, queueStartedAt, queueDeadlineAt,
                cancelling ? requestedAt : cancellationRequestedAt, cancellationAcknowledgedAt, completedAt,
                createdBy, createdAt, at);
    }

    /**
     * Settles a stopping run. The outcome was decided when it entered STOPPING and is not revisited here, so a
     * reconciler cannot turn a lost lease into a cancellation or the reverse.
     */
    public TestRun settled(Instant at) {
        if (lifecycleState != RunLifecycle.STOPPING) {
            throw new IllegalStateException("Only a stopping run can be settled.");
        }
        TerminationReason reason = stopReason.terminationReason();
        boolean cancelling = reason == TerminationReason.USER_REQUESTED;
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("A run cannot end before its own last update.");
        }
        return new TestRun(
                runId,
                projectId,
                Math.addExact(runVersion, 1),
                RunLifecycle.COMPLETED,
                cancelling ? CancellationStatus.ACKNOWLEDGED : cancellationStatus,
                TestOutcome.NOT_AVAILABLE,
                reason.infrastructureOutcome(),
                QualityGateStatus.NOT_EVALUATED,
                reason,
                reason.phase(),
                stopReason,
                snapshotDigest,
                queueStartedAt,
                queueDeadlineAt,
                cancellationRequestedAt,
                cancelling ? at : cancellationAcknowledgedAt,
                at,
                createdBy,
                createdAt,
                at);
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
                stopReason,
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
