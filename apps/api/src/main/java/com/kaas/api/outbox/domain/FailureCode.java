package com.kaas.api.outbox.domain;

/**
 * Bounded, low-cardinality publication failure reasons. These are persisted and used as metric dimensions, so they
 * must never carry exception text, broker topology detail, or credentials.
 */
public final class FailureCode {
    public static final String BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE";
    public static final String PUBLISH_NACKED = "PUBLISH_NACKED";
    public static final String CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT";
    public static final String UNROUTABLE = "UNROUTABLE";
    public static final String INTEGRITY_DIGEST_MISMATCH = "INTEGRITY_DIGEST_MISMATCH";
    public static final String UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION";
    public static final String UNSUPPORTED_MESSAGE_TYPE = "UNSUPPORTED_MESSAGE_TYPE";
    public static final String MALFORMED_PAYLOAD = "MALFORMED_PAYLOAD";

    private FailureCode() {}
}
