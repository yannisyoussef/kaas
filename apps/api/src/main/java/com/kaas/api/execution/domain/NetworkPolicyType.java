package com.kaas.api.execution.domain;

/**
 * What an execution's egress policy permits.
 *
 * <p>The two members exist at different levels of reality, and keeping that distinction visible is the reason
 * this is an enum rather than a boolean. {@link #DENY_ALL} is enforceable: the hardened sandbox gives a
 * container no network at all, and the security gate demonstrates it from inside. {@link #ALLOWLIST} is
 * defined so the schema and the policy model can be built toward it, and is refused at authorization time
 * because no launcher can currently prove it.
 *
 * <p>Refusing rather than degrading matters. A policy that silently fell back to something weaker when its
 * intended enforcement was unavailable would be worse than having no policy, because the run would appear to
 * have egress control that nothing was applying.
 */
public enum NetworkPolicyType {
    /** No network whatsoever. Enforced by the sandbox and demonstrated by the probe. */
    DENY_ALL(true),
    /**
     * A destination allowlist, enforced by the trusted egress proxy.
     *
     * <p>Enforceable in the sense this flag means — the platform has a mechanism for it — but that is
     * necessary and not sufficient. Whether a particular <em>deployment</em> can enforce it is a separate
     * question with a separate answer: the authorization path also requires the sandbox security assessment
     * to carry passing egress controls, which are properties of the host that would run the execution rather
     * than of this source tree. A build that has the mechanism running on a host that cannot demonstrate it
     * still refuses.
     */
    ALLOWLIST(true);

    private final boolean enforceable;

    NetworkPolicyType(boolean enforceable) {
        this.enforceable = enforceable;
    }

    /** Whether the current execution runtime can actually apply this policy and prove that it did. */
    public boolean enforceable() {
        return enforceable;
    }
}
