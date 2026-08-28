package com.kaas.api.outbox.domain;

/**
 * Why a message will never be published. This is the relay-side record and is authoritative for the fate of a
 * durable message; a future consumer dead-letter queue is a separate, unrelated concept.
 *
 * <p>Two of these are delivery failures and two are withdrawals, and the distinction is not cosmetic: a dead
 * letter means the broker path is broken and an operator has to look, whereas a suppressed message means the run
 * it would have dispatched ended before anyone sent it, which is the system working. Only the relay writes the
 * failures; only a terminal lifecycle transition writes the suppressions.
 */
public enum TerminalDisposition {
    RETRIES_EXHAUSTED(true),
    PERMANENT_FAILURE(true),
    /** The run was cancelled before this dispatch was claimed or published. */
    SUPPRESSED_CANCELLED(false),
    /** The run's queue deadline passed before this dispatch was claimed or published. */
    SUPPRESSED_QUEUE_TIMEOUT(false);

    private final boolean deliveryFailure;

    TerminalDisposition(boolean deliveryFailure) {
        this.deliveryFailure = deliveryFailure;
    }

    /** Whether this disposition means delivery went wrong, as opposed to the message no longer being wanted. */
    public boolean deliveryFailure() {
        return deliveryFailure;
    }
}
