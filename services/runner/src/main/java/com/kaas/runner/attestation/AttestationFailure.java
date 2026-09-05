package com.kaas.runner.attestation;

/**
 * Why an attestation could not be produced.
 *
 * <p>Categories rather than free text, so the producer's failures are countable and greppable without a metric
 * or a log line ever carrying a key path, a key, or the contents of an operator's file.
 */
public enum AttestationFailure {
    /** Missing, unreadable, malformed, or not an Ed25519 key. Never "so generate one". */
    SIGNING_KEY_UNUSABLE,

    /** The key was fine and the signature operation itself failed. */
    SIGNING_FAILED,

    /** A security gate did not run, or produced no evidence to sign. */
    ASSESSMENT_UNAVAILABLE,

    /** The gate ran but its output does not cover the controls the contract requires. */
    ASSESSMENT_INCOMPLETE,

    /** The runtime being assessed could not be identified. */
    RUNTIME_UNIDENTIFIED,

    /** The signed artifact could not be written durably. */
    OUTPUT_FAILED
}
