package com.kaas.api.outbox.domain;

/**
 * The controlled set of durable facts the outbox may carry. This is deliberately an enum rather than free-form
 * text: a generalized outbox is not a generic dumping ground.
 */
public enum MessageType {
    /** Queue-time transport intent for one unassigned attempt. The only type published today. */
    EXECUTION_DISPATCH,
    /** Declared so the schema demonstrably generalizes. No producer and no publisher exist yet. */
    RUN_STATE_CHANGED;

    public static boolean isPublishable(String value) {
        return EXECUTION_DISPATCH.name().equals(value);
    }
}
