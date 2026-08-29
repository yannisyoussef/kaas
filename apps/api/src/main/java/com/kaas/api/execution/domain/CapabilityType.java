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
    SECRET("kaas_sec_");

    private final String tokenPrefix;

    CapabilityType(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public String tokenPrefix() {
        return tokenPrefix;
    }
}
