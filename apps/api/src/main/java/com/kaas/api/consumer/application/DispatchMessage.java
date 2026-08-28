package com.kaas.api.consumer.application;

import java.util.UUID;

/**
 * One delivered message as the transport handed it over: raw bytes, plus the identity the transport claims.
 *
 * <p>The claimed identity is kept only so a message that fails to parse still has something to record and to
 * correlate. It is never authority — the durable dispatch body and the control plane's own state own every
 * identity that matters, and a header that disagrees with the body is a reason to reject, not to believe the
 * header.
 */
public record DispatchMessage(UUID transportMessageId, String messageType, String schemaVersion, byte[] body) {

    public DispatchMessage {
        if (body == null) {
            throw new IllegalArgumentException("A delivered message has a body, even an unparseable one.");
        }
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public int size() {
        return body.length;
    }
}
