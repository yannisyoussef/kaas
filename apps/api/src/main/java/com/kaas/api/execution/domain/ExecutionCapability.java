package com.kaas.api.execution.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short-lived bearer authority to fetch exactly one kind of thing, for exactly one assignment.
 *
 * <p>The plaintext token is not here and never was. This is the record of a capability's existence and shape;
 * the token itself lived only in the response that issued it.
 *
 * <p>{@code secretReferenceIds} is populated only for {@link CapabilityType#SECRET} and enumerates the exact
 * references the capability may resolve. An enumeration rather than a scope expression, because a scope
 * expression is where a wildcard eventually appears, and a wildcard is how a capability for one run reads
 * another's secrets.
 */
public record ExecutionCapability(
        UUID capabilityId,
        UUID authorizationId,
        CapabilityType capabilityType,
        String tokenSha256,
        Instant issuedAt,
        Instant expiresAt,
        int redemptionCount,
        Instant lastRedeemedAt,
        Instant revokedAt,
        List<SecretScope> secretReferenceIds) {

    /**
     * How many times one capability may be redeemed before it is spent.
     *
     * <p>Not one. A worker legitimately retries a download after a connection reset, and a capability that
     * self-destructs on the first attempt would turn an ordinary network hiccup into a failed run — which
     * operators would work around by requesting fresh authorizations in a loop, producing more live tokens
     * rather than fewer. The security comes from the short window, the assignment fencing, and the fact that
     * every redemption revalidates live state. The ceiling is here to bound amplification, not to be the control.
     */
    public static final int MAX_REDEMPTIONS = 64;

    public ExecutionCapability {
        secretReferenceIds = List.copyOf(secretReferenceIds);
        if (capabilityType == CapabilityType.SOURCE && !secretReferenceIds.isEmpty()) {
            throw new IllegalArgumentException("A source capability has no secret scope.");
        }
    }

    public boolean withinWindow(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && redemptionCount < MAX_REDEMPTIONS;
    }

    /** One SecretReference a secret capability may resolve, and the snapshot key it was bound under. */
    public record SecretScope(UUID secretReferenceId, String bindingKey) {}
}
