package com.kaas.api.consumer.domain;

/**
 * What the consumer decided about one message, recorded before the broker is acknowledged.
 *
 * <p>Redelivery is deliberately not one of these. A message's fate does not change when the broker offers it
 * again — only the number of times it has been offered — so a redelivery increments a counter on the decision
 * that already exists rather than producing a second, different decision.
 */
public enum InboxDisposition {
    /** The message was valid, the run was claimable, and a claim committed. */
    CLAIMED(true),
    /**
     * The message was valid but the work it describes is over or has moved on. Expected distributed-system
     * behaviour, not poison: a cancellation or a queue timeout that raced a delivery produces exactly this.
     */
    STALE(true),
    /**
     * The message cannot be understood or trusted — malformed, unsupported schema, unknown field, or an identity
     * the control plane cannot corroborate. Retrying it would produce the same answer forever.
     */
    REJECTED(false),
    /**
     * A message arrived under an identity that is already known, carrying different bytes. That is not a
     * duplicate; it is two different messages claiming to be the same one, and it is recorded as a security
     * event rather than resolved by picking a winner.
     */
    CONFLICT(false);

    private final boolean expected;

    InboxDisposition(boolean expected) {
        this.expected = expected;
    }

    /**
     * Whether this outcome is ordinary. Ordinary messages are acknowledged and forgotten; the others are
     * acknowledged too — requeueing them would loop forever — but routed where an operator will see them.
     */
    public boolean expected() {
        return expected;
    }
}
