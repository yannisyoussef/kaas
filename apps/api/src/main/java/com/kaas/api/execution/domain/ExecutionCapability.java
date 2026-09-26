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

    /**
     * How many times a SECRET capability may be redeemed: the delivery, and one retry for a response lost in
     * transit.
     *
     * <p>Not the source ceiling. A source bundle is tenant-authored test content, and re-serving it costs
     * bandwidth; a secret bundle is plaintext, and every redemption is another copy of it in flight. Two is the
     * smallest number that survives an ordinary network ambiguity -- the control plane decrypted and sent, the
     * worker never received -- without letting a stolen token be replayed at leisure. The database enforces the
     * same number with a CHECK, so a writer that forgot this constant still cannot exceed it.
     */
    public static final int MAX_SECRET_REDEMPTIONS = 2;

    public ExecutionCapability {
        secretReferenceIds = List.copyOf(secretReferenceIds);
        if (capabilityType != CapabilityType.SECRET && !secretReferenceIds.isEmpty()) {
            throw new IllegalArgumentException("Only a secret capability has a secret scope.");
        }
        // A secret capability with nothing in scope authorizes nothing, and a run with no secrets receives NO
        // secret capability rather than an empty one. An empty one would be a live bearer token whose only
        // effect is to exist -- and a closed-set model whose set can be empty is one refactor away from a
        // model where "empty" is read as "unrestricted".
        //
        // Enforced on construction only when the scope is being CREATED (a capability read back from the
        // database is reconstructed without its scope, which lives in a separate table and is loaded on its
        // own), so the check lives where issuance builds one: see ExecutionAuthorizationService.
    }

    /** A secret capability's scope at issuance: never empty. */
    public static ExecutionCapability secret(
            java.util.UUID capabilityId,
            java.util.UUID authorizationId,
            String tokenSha256,
            java.time.Instant issuedAt,
            java.time.Instant expiresAt,
            List<SecretScope> scope) {
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("A secret capability must name at least one secret.");
        }
        return new ExecutionCapability(
                capabilityId, authorizationId, CapabilityType.SECRET, tokenSha256, issuedAt, expiresAt, 0, null,
                null, scope);
    }

    /** The redemption ceiling for this capability's type. */
    public int maximumRedemptions() {
        return capabilityType == CapabilityType.SECRET ? MAX_SECRET_REDEMPTIONS : MAX_REDEMPTIONS;
    }

    public boolean withinWindow(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && redemptionCount < maximumRedemptions();
    }

    /** One secret a capability may resolve: the reference, the key it is bound to, and the exact version. */
    public record SecretScope(UUID secretReferenceId, String bindingKey, int version) {}
}
