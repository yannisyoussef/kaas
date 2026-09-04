package com.kaas.api.controlplane.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One infrastructure attempt at a run, and the assignment that owns it.
 *
 * <p>An attempt is distinct from a test-level scenario retry and from the run itself: a future infrastructure
 * retry creates a new attempt with a new identity, while a reassignment of the same attempt would raise the
 * assignment epoch. Only the first attempt exists today.
 */
public record ExecutionAttempt(
        UUID attemptId,
        UUID runId,
        int attemptNumber,
        ExecutionAttemptState state,
        Instant createdAt,
        WorkerAssignment assignment) {

    public ExecutionAttempt {
        if (attemptId == null || runId == null || attemptNumber != 1 || createdAt == null || state == null) {
            throw new IllegalArgumentException("An attempt is the first attempt at a known run.");
        }
        // The state and the assignment are two views of one fact, so they may never disagree.
        boolean assigned = assignment != null;
        if (assigned != (state != ExecutionAttemptState.WAITING_FOR_CLAIM)) {
            throw new IllegalArgumentException("An assigned attempt has an assignment and an unassigned one does not.");
        }
        if (assigned && assignment.fenced() != (state == ExecutionAttemptState.FENCED)) {
            throw new IllegalArgumentException("A fenced attempt carries a fenced assignment.");
        }
    }

    public static ExecutionAttempt waitingForClaim(UUID attemptId, UUID runId, Instant createdAt) {
        return new ExecutionAttempt(
                attemptId, runId, 1, ExecutionAttemptState.WAITING_FOR_CLAIM, createdAt, null);
    }

    public ExecutionAttempt claimedBy(String workerId, Instant at, Duration leaseDuration) {
        if (state != ExecutionAttemptState.WAITING_FOR_CLAIM) {
            throw new IllegalStateException("Only an unassigned attempt can be claimed.");
        }
        return new ExecutionAttempt(
                attemptId, runId, attemptNumber, ExecutionAttemptState.CLAIMED, createdAt,
                WorkerAssignment.claim(workerId, at, leaseDuration));
    }

    /**
     * Binds this attempt's assignment to the worker that is actually going to run it.
     *
     * <p>The attempt state does not change: it was CLAIMED and it stays CLAIMED. Acquisition answers who holds
     * the assignment, not whether one exists.
     */
    public ExecutionAttempt acquiredBy(String workerId, Instant at) {
        requireAssigned();
        return new ExecutionAttempt(
                attemptId, runId, attemptNumber, ExecutionAttemptState.CLAIMED, createdAt,
                assignment.acquiredBy(workerId, at));
    }

    public ExecutionAttempt heartbeat(Instant at, Duration leaseDuration) {
        requireAssigned();
        return new ExecutionAttempt(
                attemptId, runId, attemptNumber, ExecutionAttemptState.CLAIMED, createdAt,
                assignment.renewed(at, leaseDuration));
    }

    public ExecutionAttempt fenced(Instant at) {
        requireAssigned();
        return new ExecutionAttempt(
                attemptId, runId, attemptNumber, ExecutionAttemptState.FENCED, createdAt, assignment.fenced(at));
    }

    private void requireAssigned() {
        if (state != ExecutionAttemptState.CLAIMED) {
            throw new IllegalStateException("This attempt holds no live assignment.");
        }
    }
}
