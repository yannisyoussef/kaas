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
    /** A destination allowlist. Modelled, not yet enforceable by any launcher this platform has. */
    ALLOWLIST(false);

    private final boolean enforceable;

    NetworkPolicyType(boolean enforceable) {
        this.enforceable = enforceable;
    }

    /** Whether the current execution runtime can actually apply this policy and prove that it did. */
    public boolean enforceable() {
        return enforceable;
    }
}
