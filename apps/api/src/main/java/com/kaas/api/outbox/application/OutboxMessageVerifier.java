package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.OutboxMessage;
import java.util.Optional;

/**
 * Fails closed on integrity or contract mismatch before anything reaches the broker. A mismatch is a permanent
 * security failure, never something to retry.
 */
public interface OutboxMessageVerifier {

    /** Returns a bounded failure code when the message must not be published, or empty when it is publishable. */
    Optional<String> verify(OutboxMessage message);
}
