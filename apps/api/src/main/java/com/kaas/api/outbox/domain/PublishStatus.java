package com.kaas.api.outbox.domain;

public enum PublishStatus {
    /** The broker positively confirmed the publication. Nothing weaker counts as published. */
    CONFIRMED,
    /** The broker or the connection failed in a way that may succeed later. Retry with backoff. */
    TRANSIENT_FAILURE,
    /** The message can never be published as it stands. Terminal immediately, without retry. */
    PERMANENT_FAILURE
}
