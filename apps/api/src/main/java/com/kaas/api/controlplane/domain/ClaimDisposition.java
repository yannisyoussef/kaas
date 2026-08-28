package com.kaas.api.controlplane.domain;

/**
 * What happened when a consumer tried to take ownership of a run.
 *
 * <p>Only the first is a success, and the rest are all ordinary. A dispatch that arrives for a run somebody else
 * already claimed, or that a cancellation or a queue deadline ended first, is the distributed system working:
 * publication is at least once and the broker has no idea what the control plane decided after it delivered.
 */
public enum ClaimDisposition {
    /** This call took ownership. Exactly one caller ever sees this for a given assignment. */
    CLAIMED,
    /** Somebody already owns it. The message is a duplicate of one that was already acted on. */
    ALREADY_CLAIMED,
    /** The run moved on — cancelled, expired, or already finished — so there is nothing left to claim. */
    STALE,
    /** The message does not describe the run the control plane holds. Never claimed, always recorded. */
    NOT_CLAIMABLE
}
