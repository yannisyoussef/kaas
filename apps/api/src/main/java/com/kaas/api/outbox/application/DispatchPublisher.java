package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.PublishOutcome;

/** Transport edge. The broker is not the source of truth; it only carries an already-durable message. */
public interface DispatchPublisher {

    /**
     * Publishes and waits for a positive broker confirmation. Returning normally without a confirmation must
     * never be reported as {@code CONFIRMED}.
     */
    PublishOutcome publish(OutboxMessage message);
}
