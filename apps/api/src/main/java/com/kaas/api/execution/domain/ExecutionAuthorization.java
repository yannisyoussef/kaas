package com.kaas.api.execution.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The decision that one specific assignment may execute.
 *
 * <p>Claiming an attempt established ownership. This establishes permission, and the two are deliberately
 * separate decisions taken at different moments against different evidence: a worker can own an attempt for
 * minutes before anything is allowed to run on it, and the conditions that make execution safe — a live lease, a
 * sealed snapshot, an enforceable egress policy, a sandbox that demonstrably confines what it runs — are all
 * things that can stop being true while ownership continues.
 *
 * <p>Every field that is not an identifier is evidence of something established at issuance. They are recorded
 * so an audit can answer "on what basis was this allowed", and so a later check can notice the basis has moved.
 *
 * <p><strong>This record is not itself authority.</strong> Holding it, or holding a capability issued under it,
 * proves nothing about the present. Every redemption revalidates the live assignment, and an authorization whose
 * run was cancelled a millisecond after issuance is worthless despite an unexpired TTL. Expiry bounds the damage
 * from a leaked token; it is not what makes fencing work.
 */
public record ExecutionAuthorization(
        UUID authorizationId,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        long runVersion,
        UUID attemptId,
        int attemptNumber,
        int assignmentEpoch,
        String workerId,
        String runSnapshotSha256,
        String securityProfileVersion,
        String securityAssessmentDigest,
        String probeImageDigest,
        UUID networkPolicyRevisionId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedReason) {

    public ExecutionAuthorization {
        if (authorizationId == null || runId == null || attemptId == null || workerId == null) {
            throw new IllegalArgumentException("An authorization names its assignment.");
        }
        if (assignmentEpoch < 1) {
            throw new IllegalArgumentException("An authorization carries the epoch it was issued under.");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("An authorization must expire after it is issued.");
        }
    }

    /** Whether the token window is still open. Necessary for use, and nowhere near sufficient. */
    public boolean withinWindow(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /**
     * Whether this authorization describes the assignment currently holding the attempt.
     *
     * <p>Attempt, epoch, and worker together. Any two of them would leave a hole: attempt and epoch alone would
     * let a different worker act as the current owner, and attempt and worker alone would let a worker act under
     * an epoch it has already been replaced in.
     */
    public boolean describes(UUID candidateAttemptId, int candidateEpoch, String candidateWorkerId) {
        return attemptId.equals(candidateAttemptId)
                && assignmentEpoch == candidateEpoch
                && workerId.equals(candidateWorkerId);
    }
}
