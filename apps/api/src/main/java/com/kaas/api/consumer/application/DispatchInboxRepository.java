package com.kaas.api.consumer.application;

import com.kaas.api.consumer.domain.InboxRecord;
import java.util.Optional;
import java.util.UUID;

public interface DispatchInboxRepository {
    /**
     * Serialises every delivery of one message against every other, so two copies arriving at once cannot both
     * decide it. Taken before anything is read, exactly as run creation locks its idempotency key.
     */
    void lockMessage(String consumer, UUID messageId);

    Optional<InboxRecord> find(String consumer, UUID messageId);

    /** Records that the broker offered a message that already has a decision. The decision itself is untouched. */
    void countRedelivery(String consumer, UUID messageId);

    void record(InboxRecord decision);
}
