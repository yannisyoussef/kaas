package com.kaas.api.outbox.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One durable fact awaiting transport. The outbox owns its own immutable payload, so the relay reads a single
 * table and never joins per message type. {@code dispatchId} is an optional domain reference, present only for
 * message types that have one.
 */
public record OutboxMessage(
        UUID outboxId,
        UUID messageId,
        String messageType,
        String schemaVersion,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        UUID dispatchId,
        String payload,
        String payloadDigest,
        Instant occurredAt,
        int publishAttempts) {

    public OutboxMessage {
        if (outboxId == null || messageId == null || messageType == null || schemaVersion == null
                || organizationId == null || projectId == null || runId == null || payload == null
                || payloadDigest == null || occurredAt == null || publishAttempts < 0) {
            throw new IllegalArgumentException("An outbox message must carry complete durable identity.");
        }
    }

    public Optional<UUID> dispatch() {
        return Optional.ofNullable(dispatchId);
    }
}
