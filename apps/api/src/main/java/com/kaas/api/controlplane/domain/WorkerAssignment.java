package com.kaas.api.controlplane.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Who owns an execution attempt, under which fencing token, and until when.
 *
 * <p>The epoch is the fencing token. It is what lets the control plane make a worker harmless without being able
 * to reach it: a partitioned worker keeps acting on the epoch it holds, and every operation that matters checks
 * that epoch against the active one. Reassignment must always use a strictly higher epoch, so an old worker's
 * work is rejected by arithmetic rather than by hoping it noticed.
 *
 * <p>The worker identity is audit, not authorization. It records which instance the control plane handed the
 * assignment to; it never decides whether that instance may do anything. Nothing here comes from a broker
 * message — the epoch, the identity, and every timestamp are server-controlled.
 */
public record WorkerAssignment(
        int epoch,
        String workerId,
        Instant leaseStartedAt,
        Instant leaseExpiresAt,
        Instant lastHeartbeatAt,
        Instant fencedAt) {

    /** The first assignment an attempt can have. A later one would need a strictly higher epoch. */
    public static final int FIRST_EPOCH = 1;

    public WorkerAssignment {
        if (epoch < FIRST_EPOCH) {
            throw new IllegalArgumentException("An assignment epoch starts at " + FIRST_EPOCH + ".");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalArgumentException("An assignment names the worker instance that holds it.");
        }
        if (leaseStartedAt == null || leaseExpiresAt == null || !leaseExpiresAt.isAfter(leaseStartedAt)) {
            throw new IllegalArgumentException("A lease must expire after it starts.");
        }
        if (lastHeartbeatAt == null || lastHeartbeatAt.isBefore(leaseStartedAt)) {
            throw new IllegalArgumentException("A heartbeat cannot precede the lease it renews.");
        }
    }

    public static WorkerAssignment claim(String workerId, Instant at, Duration leaseDuration) {
        return new WorkerAssignment(FIRST_EPOCH, workerId, at, at.plus(leaseDuration), at, null);
    }

    public boolean fenced() {
        return fencedAt != null;
    }

    /**
     * Renews the lease this assignment already holds. It never changes the epoch or the worker: a renewal is the
     * same assignment continuing, and anything that would change either of those is a different assignment
     * pretending to be this one.
     */
    public WorkerAssignment renewed(Instant at, Duration leaseDuration) {
        if (fenced()) {
            throw new IllegalStateException("A fenced assignment cannot be renewed.");
        }
        if (at == null || !at.isAfter(lastHeartbeatAt)) {
            throw new IllegalArgumentException("A heartbeat must be newer than the one it replaces.");
        }
        if (!at.isBefore(leaseExpiresAt)) {
            // Renewing an expired lease would undo the reconciler's basis for fencing it, and would let a worker
            // that had already lost ownership take it back by being late rather than by being correct.
            throw new IllegalStateException("An expired lease cannot be renewed, only fenced.");
        }
        return new WorkerAssignment(epoch, workerId, leaseStartedAt, at.plus(leaseDuration), at, null);
    }

    /** Ends the assignment. The epoch is kept, because a later one has to be strictly greater than it. */
    public WorkerAssignment fenced(Instant at) {
        if (fenced()) {
            throw new IllegalStateException("This assignment is already fenced.");
        }
        if (at == null || at.isBefore(lastHeartbeatAt)) {
            throw new IllegalArgumentException("An assignment cannot be fenced before its last heartbeat.");
        }
        return new WorkerAssignment(epoch, workerId, leaseStartedAt, leaseExpiresAt, lastHeartbeatAt, at);
    }

    /**
     * Whether this assignment is the one an operation claims to be acting under. Identity plus epoch, never one
     * or the other: an epoch alone would let any worker act as the current owner, and an identity alone would
     * let a restarted worker act under an assignment it has already lost.
     */
    public boolean isHeldBy(String candidateWorkerId, int candidateEpoch) {
        return !fenced() && epoch == candidateEpoch && workerId.equals(candidateWorkerId);
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(leaseExpiresAt);
    }
}
