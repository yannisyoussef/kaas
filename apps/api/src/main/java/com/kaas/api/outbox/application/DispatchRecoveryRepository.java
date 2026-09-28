package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.OutboxMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transport recovery state for published dispatches (KAAS-MSG-001). PostgreSQL decides everything here, on its own
 * clock; nothing reads broker state.
 *
 * <p>A dispatch is ELIGIBLE for recovery exactly when every one of these holds, now:
 *
 * <ul>
 *   <li>its outbox row was published -- the broker confirmed it once -- and is not terminally disposed;
 *   <li>its run is {@code QUEUED}, the dispatch's attempt is the run's current one, and that attempt is still
 *       {@code WAITING_FOR_CLAIM} -- no worker owns it;
 *   <li>the queue deadline is still in the future;
 *   <li>the consumer has recorded NO decision for its message id: no {@code dispatch_inbox} row of any
 *       disposition. A delivered-but-unclaimed run has a row and is never republished -- the broker did its job;
 *   <li>the grace period has passed since the last time it was published, initially or by recovery.
 * </ul>
 */
public interface DispatchRecoveryRepository {

    /**
     * Claims up to {@code batchSize} eligible dispatches, creating their recovery rows if needed. Rows another
     * instance holds under a live claim are skipped; an expired claim is reclaimable.
     *
     * @return the outbox messages to republish, exactly as persisted
     */
    List<Claimed> claimEligible(String consumer, UUID claimId, int batchSize, Duration grace, int maxPublications,
            Duration claimTtl);

    /** Re-checks eligibility for one claimed dispatch immediately before it is published; the reason if not. */
    Optional<String> ineligibility(String consumer, UUID messageId);

    /** Records a confirmed republication, releasing the claim. False when the claim is no longer held. */
    boolean recordPublished(UUID messageId, UUID claimId, Instant nextAttemptAt);

    /** Records a failed attempt, releasing the claim. False when the claim is no longer held. */
    boolean recordFailed(UUID messageId, UUID claimId, String failureCode, Instant nextAttemptAt);

    /** Releases a claim without recording an attempt: the dispatch stopped being eligible before publication. */
    boolean release(UUID messageId, UUID claimId);

    /** How many published dispatches are eligible right now, ignoring claims. For status and metrics. */
    long countEligible(String consumer, Duration grace, int maxPublications);

    Instant currentDatabaseTime();

    /** Waiting dispatches that have used every republication; only the queue deadline acts on them now. */
    long countCapped(String consumer, int maxPublications);

    /**
     * A claimed dispatch, its recovery history as it stood when claimed, and when the database will consider the
     * claim expired -- the lease the caller must stay inside.
     */
    record Claimed(OutboxMessage message, int recoveryAttempts, int recoveryPublications, Instant leaseExpiresAt) {}
}
