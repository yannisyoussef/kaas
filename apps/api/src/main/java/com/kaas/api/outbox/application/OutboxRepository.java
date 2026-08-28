package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.TerminalDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivery state is owned by PostgreSQL, never by relay-process memory, so that retry timing survives a restart
 * and is consistent across relay instances.
 *
 * <p>Every {@code record*} method is guarded by the caller's claim identity and returns false when the row no
 * longer belongs to that claim, which is how a stale relay is prevented from overwriting a newer disposition.
 */
public interface OutboxRepository {

    /**
     * Atomically takes ownership of up to {@code batchSize} available messages. Rows already claimed by a live
     * relay are skipped rather than waited on; a claim that has expired is reclaimable.
     */
    List<OutboxMessage> claimPending(UUID relayClaimId, int batchSize, Duration claimTtl);

    /**
     * Hands a claimed message back without consuming an attempt, so a relay that abandons a batch does not strand
     * those rows for the rest of the lease.
     */
    boolean releaseClaim(UUID outboxId, UUID relayClaimId);

    boolean recordPublished(UUID outboxId, UUID relayClaimId, Instant attemptedAt);

    boolean recordRetry(
            UUID outboxId, UUID relayClaimId, Instant attemptedAt, Instant availableAt, String failureCode);

    boolean recordTerminal(
            UUID outboxId,
            UUID relayClaimId,
            Instant attemptedAt,
            TerminalDisposition disposition,
            String failureCode);

    long countPending();

    long countTerminal();

    /** Age of the oldest message still awaiting publication, or zero when the outbox is drained. */
    long oldestPendingAgeSeconds();

    Instant currentDatabaseTime();
}
