package com.kaas.api.execution.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the values behind SecretReferences.
 *
 * <p>KaaS stores secret <em>metadata</em> — a name, a project, an identity — and has never stored a secret
 * value. There is no Vault, no cloud secret manager, no encrypted store, and no provider SDK anywhere in the
 * dependency graph. This interface exists so the capability envelope around secrets can be designed and proven
 * now, while the thing it protects is still absent.
 *
 * <p>The production implementation refuses. That is not a placeholder to be replaced by an optimistic default
 * later: a run that binds secrets and cannot get them must fail closed at authorization, because issuing a
 * command that promises secrets nothing can deliver would mean a worker discovering the problem at execution
 * time, with a sandbox already running and a user watching a run fail for a reason nobody recorded.
 */
public interface SecretValueProvider {

    /** A name an operator would recognise in a refusal. Never a path, a URI, or a credential. */
    String providerName();

    /** Whether this provider can resolve anything at all. Checked before authorization, not at redemption. */
    boolean available();

    /**
     * Resolves exactly the references asked for.
     *
     * <p>Never a wildcard and never a prefix: the caller supplies the identities a capability authorized, and a
     * reference the caller did not ask for is not returned even if the provider could resolve it. A reference
     * this provider cannot resolve is simply absent from the result rather than raising, so a partial failure
     * does not become an oracle for which references exist.
     */
    Map<UUID, String> resolve(java.util.Set<UUID> secretReferenceIds);

    /** Why this provider is unavailable, when it is. Present exactly when {@link #available()} is false. */
    Optional<String> unavailableReason();
}
