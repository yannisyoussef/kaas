package com.kaas.api.execution.domain;

/**
 * Why an attestation was or was not accepted.
 *
 * <h2>Coarse on purpose, before authenticity is established</h2>
 *
 * <p>The first five outcomes describe a document nobody has authenticated yet. It is operator-supplied
 * configuration that an attacker may have influenced, so a diagnostic that quoted its control contents would
 * be a diagnostic that repeated attacker-chosen text into a log. These say which stage refused and nothing
 * about what the document claimed.
 *
 * <p>The semantic outcomes below them are only reachable after the signature verified, so by then the contents
 * are known to have come from a holder of a pinned key — and an operator can safely be told which control
 * failed, because a trusted producer said so.
 */
public enum AttestationVerification {
    /** Accepted: authentic, and every semantic condition satisfied. */
    VALID,

    /** No attestation is configured at all. Absent evidence is a refusal, never a pass. */
    ABSENT,

    /** Not parseable, not the right shape, or carrying a property this schema does not define. */
    MALFORMED,

    /** Parsed, but not schema v3. A v2 document lands here — refused, not downgraded to an unsigned path. */
    UNSUPPORTED_SCHEMA,

    /** The key id selects nothing in the pinned trust map. A key id is not authority. */
    UNKNOWN_KEY,

    /** The document's own digest does not describe the payload reconstructed from its fields. */
    DIGEST_MISMATCH,

    /** The signature does not verify over the reconstructed payload with the resolved key. */
    INVALID_SIGNATURE,

    /** Authentic, but no verification key is configured at all, so nothing could have been checked. */
    TRUST_ROOT_UNAVAILABLE,

    /** Authentic, and describes a runtime this control plane was not told to accept evidence for. */
    WRONG_SUBJECT,

    /** Authentic, and older than the configured maximum age, or dated implausibly far in the future. */
    STALE,

    /** Authentic, and taken under a different sandbox security profile than the one execution would use. */
    PROFILE_MISMATCH,

    /** Authentic, and a required control is missing, extra, or did not pass. */
    CONTROL_FAILED;

    public boolean accepted() {
        return this == VALID;
    }
}
