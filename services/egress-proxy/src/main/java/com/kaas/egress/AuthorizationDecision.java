package com.kaas.egress;

/**
 * The control plane's answer about one destination for one capability.
 *
 * <p>Deliberately narrow. It carries a verdict and a reason and nothing else — no run identifiers, no policy
 * contents, no worker identity, no expiry timestamp. The proxy does not need them to do its job, and anything
 * it does not receive is something a proxy compromise cannot disclose.
 */
public record AuthorizationDecision(boolean authorized, DenialReason reason) {

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true, null);

    /**
     * Named {@code granted} rather than {@code authorized} only because the record's own accessor owns that
     * name. Keeping both would have meant renaming the accessor, and {@code decision.authorized()} is the
     * reading that belongs at every call site.
     */
    public static AuthorizationDecision granted() {
        return GRANTED;
    }

    public static AuthorizationDecision denied(DenialReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("A denial names a reason.");
        }
        return new AuthorizationDecision(false, reason);
    }
}
