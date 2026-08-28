package com.kaas.api.outbox.domain;

/**
 * Why a message will never be published. This is the relay-side dead-letter record and is authoritative for
 * publication failure; a future consumer dead-letter queue is a separate, unrelated concept.
 */
public enum TerminalDisposition {
    RETRIES_EXHAUSTED,
    PERMANENT_FAILURE
}
