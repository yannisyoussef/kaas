package com.kaas.api.execution.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The production secret provider: one that cannot resolve anything, and says so.
 *
 * <p>This is the honest implementation of the current state rather than a stub awaiting completion. Every
 * alternative shape considered here was worse. Returning empty values would let a run execute with blank
 * credentials and fail confusingly. Returning the reference name would leak metadata into a variable a test
 * would then send somewhere. Throwing at redemption would move the failure past the point where a sandbox had
 * already been started. Refusing at authorization means a secret-bearing run stops before anything is issued,
 * with a reason that names the actual problem.
 *
 * <p>Replacing this means implementing a real provider deliberately, in its own slice, with its own review. It
 * does not mean flipping a flag.
 */
public final class UnavailableSecretValueProvider implements SecretValueProvider {

    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Map<UUID, String> resolve(Set<UUID> secretReferenceIds) {
        // Unreachable through the authorization path, which refuses secret-bearing runs before redemption is
        // possible. It throws rather than returning empty so that a future caller which skipped that check finds
        // out immediately instead of executing with silently absent secrets.
        throw new IllegalStateException("No secret provider is configured; secret-bearing runs cannot execute.");
    }

    @Override
    public Optional<String> unavailableReason() {
        return Optional.of("no secret provider is configured for this deployment");
    }
}
