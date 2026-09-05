package com.kaas.api.execution.domain;

/**
 * What a capability grants access to.
 *
 * <p>The printable prefix is not the security — the random body is — but it stops a whole class of routing
 * mistake. A source token presented to the secret endpoint fails on its prefix before anything looks it up, so
 * the two populations cannot be confused by a caller, a proxy, or a copy-paste. The database enforces the same
 * separation from underneath with a single unique index across both types.
 */
public enum CapabilityType {
    /** Fetch the immutable feature sources this run's snapshot names. Nothing else. */
    SOURCE("kaas_src_"),
    /** Resolve the specific SecretReferences this run's snapshot binds. Never a wildcard. */
    SECRET("kaas_sec_"),
    /**
     * Ask the control plane whether this execution may reach one destination, right now.
     *
     * <p>Unlike the two above it is never exchanged for anything: it authorizes a question, not a transfer.
     * That is why an egress validation does not consume a redemption, and why the database refuses to record
     * one against a capability of this type — the redemption ceiling exists to bound how much can be
     * extracted with one token, and there is nothing here to extract.
     *
     * <p>It is also the only capability that is handed to the sandbox itself rather than kept by the worker.
     * Anything inside a sandbox must be assumed readable by whatever runs there, so its protection is not
     * secrecy but narrowness: one execution, one assignment epoch, one policy, briefly, and revalidated
     * against live state on every single use.
     */
    EGRESS("kaas_egr_");

    private final String tokenPrefix;

    CapabilityType(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public String tokenPrefix() {
        return tokenPrefix;
    }
}
