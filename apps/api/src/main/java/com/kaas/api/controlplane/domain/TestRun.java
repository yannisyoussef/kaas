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
        Instant updatedAt,
        /**
         * When the phase the run is currently in must be over.
         *
         * <p>One column rather than one per phase: the lifecycle state already says which phase it bounds, and
         * a deadline per phase would be three NULLs and a value at every instant. Non-null exactly in the four
         * execution phases, which is what makes "no state without a bounded exit" checkable rather than
         * remembered.
         */
        Instant phaseDeadlineAt,
        /** When the workload actually started, which a submitted result's own start instant must agree with. */
        Instant executionStartedAt) {

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
                now,
                null,
                null);
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
                cancellationAcknowledgedAt, completedAt, createdBy, createdAt, startedAt, null, null);
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
                cancellationAcknowledgedAt, completedAt, createdBy, createdAt, at, null, executionStartedAt);
    }

    /**
     * The authorized runner has begun preparing a sandbox.
     *
     * <p>PROVISIONING means preparation, not execution. Nothing user-controlled is running, and in this slice
     * nothing user-controlled ever will be — the sandbox holds a platform-owned synthetic workload.
     *
     * <p>The deadline is server-owned and passed in rather than computed here, because the aggregate does not
     * know what the deployment considers a reasonable provisioning window. What it does enforce is that one
     * exists: a phase whose exit depends only on a worker choosing to act is a phase a crashed worker turns
     * into a permanent capacity sink.
     */
    public TestRun provisioning(Instant at, Instant deadlineAt) {
        requireOwnedTransition(RunLifecycle.CLAIMED, RunLifecycle.PROVISIONING, at);
        if (deadlineAt == null || !deadlineAt.isAfter(at)) {
            throw new IllegalArgumentException("Provisioning must be given a deadline that is still ahead.");
        }
        return withPhase(RunLifecycle.PROVISIONING, at, deadlineAt, executionStartedAt);
    }

    /**
     * The sandbox exists and the workload has started.
     *
     * <p>This is the first instant at which anything is executing, so it is where {@code executionStartedAt} is
     * stamped — and a submitted result's own start instant has to agree with it, which is one of the things
     * that makes a result evidence rather than a claim.
     */
    public TestRun running(Instant at, Instant deadlineAt) {
        requireOwnedTransition(RunLifecycle.PROVISIONING, RunLifecycle.RUNNING, at);
        if (deadlineAt == null || !deadlineAt.isAfter(at)) {
            throw new IllegalArgumentException("Execution must be given a deadline that is still ahead.");
        }
        return withPhase(RunLifecycle.RUNNING, at, deadlineAt, at);
    }

    /** The workload has stopped and its evidence is being gathered. */
    public TestRun collectingResults(Instant at, Instant deadlineAt) {
        requireOwnedTransition(RunLifecycle.RUNNING, RunLifecycle.COLLECTING_RESULTS, at);
        if (deadlineAt == null || !deadlineAt.isAfter(at)) {
            throw new IllegalArgumentException("Result collection must be given a deadline that is still ahead.");
        }
        return withPhase(RunLifecycle.COLLECTING_RESULTS, at, deadlineAt, executionStartedAt);
    }

    /** Evidence has arrived and the control plane is deciding what it means. */
    public TestRun processingResults(Instant at, Instant deadlineAt) {
        requireOwnedTransition(RunLifecycle.COLLECTING_RESULTS, RunLifecycle.PROCESSING_RESULTS, at);
        if (deadlineAt == null || !deadlineAt.isAfter(at)) {
            throw new IllegalArgumentException("Result processing must be given a deadline that is still ahead.");
        }
        return withPhase(RunLifecycle.PROCESSING_RESULTS, at, deadlineAt, executionStartedAt);
    }

    /**
     * Ends a run that executed and produced evidence.
     *
     * <p>The only path to COMPLETED that carries a test outcome. Everything else that ends a run — cancellation,
     * a lost lease, any deadline — ends it without one, because nothing ran to completion and inventing PASSED
     * or FAILED there would be fabricating evidence.
     *
     * <p>The two outcomes are orthogonal and both are recorded: the infrastructure succeeded, and separately the
     * tests either passed or failed. A synthetic assertion failing is a successful execution of a failing test,
     * not a failure of the platform.
     */
    public TestRun completedWithResult(TestOutcome outcome, Instant at) {
        if (lifecycleState != RunLifecycle.PROCESSING_RESULTS) {
            throw new IllegalStateException("Only a run whose results were processed can complete with a result.");
        }
        if (outcome != TestOutcome.PASSED && outcome != TestOutcome.FAILED) {
            // NOT_AVAILABLE means nothing ran to completion, which contradicts having reached this transition.
            throw new IllegalArgumentException("A completed execution reports whether its tests passed.");
        }
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("A run cannot end before its own last update.");
        }
        TerminationReason reason = TerminationReason.EXECUTION_COMPLETED;
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), RunLifecycle.COMPLETED, cancellationStatus,
                outcome, reason.infrastructureOutcome(), QualityGateStatus.NOT_EVALUATED, reason, reason.phase(),
                stopReason, snapshotDigest, queueStartedAt, queueDeadlineAt, cancellationRequestedAt,
                cancellationAcknowledgedAt, at, createdBy, createdAt, at, null, executionStartedAt);
    }

    /** Shared precondition for every execution-phase transition a runner drives. */
    private void requireOwnedTransition(RunLifecycle from, RunLifecycle to, Instant at) {
        if (lifecycleState != from || !lifecycleState.canTransitionTo(to)) {
            throw new IllegalStateException("A run in " + lifecycleState + " cannot move to " + to + ".");
        }
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("A transition cannot precede the run's own last update.");
        }
    }

    private TestRun withPhase(RunLifecycle to, Instant at, Instant deadlineAt, Instant startedAt) {
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), to, cancellationStatus, testOutcome,
                infrastructureOutcome, qualityGateStatus, terminationReason, terminationPhase, stopReason,
                snapshotDigest, queueStartedAt, queueDeadlineAt, cancellationRequestedAt,
                cancellationAcknowledgedAt, completedAt, createdBy, createdAt, at, deadlineAt, startedAt);
    }

    /**
     * Begins ending a run somebody owns. Unlike unowned work this cannot finish in one step: an assignment
     * exists, and it has to be fenced before the run can be called over. No outcome is written here — the run has
     * not finished, and recording one now would let a crash leave a run claiming a result it never reached.
     *
     * @param requestedAt when a tenant asked, for a cancellation; null when the lease was simply lost
     */
    public TestRun stopping(StopReason reason, Instant requestedAt, Instant at) {
        // Every state that owns an assignment can stop, not only CLAIMED. Execution added four of them, and a
        // phase that could not be stopped would be a phase a cancellation could not reach.
        if (!lifecycleState.canTransitionTo(RunLifecycle.STOPPING)) {
            throw new IllegalStateException("A run in " + lifecycleState + " cannot begin stopping.");
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
                createdBy, createdAt, at,
                // A stopping run has no phase deadline: the deadline that mattered was the one for the phase it
                // just left, and STOPPING is settled by the reconciler rather than waited on by a worker.
                null, executionStartedAt);
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
                at,
                null,
                executionStartedAt);
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
                at,
                null,
                null);
    }
}
