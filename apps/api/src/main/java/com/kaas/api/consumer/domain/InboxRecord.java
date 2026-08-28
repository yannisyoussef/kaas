package com.kaas.api.consumer.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One consumer's durable decision about one message.
 *
 * <p>Keyed by application message identity, never by delivery tag: a delivery tag is channel-local transport
 * metadata that changes on every redelivery and means nothing across connections. The broker's {@code
 * redelivered} flag is a hint too, and is likewise not trusted as deduplication truth.
 */
public record InboxRecord(
        UUID inboxId,
        String consumer,
        UUID messageId,
        String payloadDigest,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        InboxDisposition disposition,
        String reason,
        Instant firstReceivedAt,
        Instant lastReceivedAt,
        Instant decidedAt,
        int deliveryCount) {

    /**
     * Whether a newly delivered copy is the same message this decision was made about.
     *
     * <p>Both sides carry a domain tag and are compared whole, so a semantic digest can never be mistaken for a
     * raw-body hash of the same length. Comparing untagged values across those domains would make an unparseable
     * message published first under a chosen identity permanently poison the genuine one behind it.
     */
    public boolean matches(String candidateDigest) {
        return payloadDigest.equals(candidateDigest);
    }
}
