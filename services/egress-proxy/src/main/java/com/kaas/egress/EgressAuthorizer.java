package com.kaas.egress;

/**
 * Asks the authoritative source whether this credential may reach this destination, right now.
 *
 * <p>An interface with one method, so the proxy can be tested against a real HTTP server speaking the real
 * wire format rather than against a mock of its own expectations. The distinction matters: a mock proves the
 * proxy calls something, and this needs to prove the proxy and the control plane agree about what was said.
 *
 * <p><strong>Every call is a fresh question.</strong> There is no cache and no memo. A cached "yes" is a
 * decision made at a moment that has passed, and the entire point of assignment-scoped authority is that the
 * moment is what changes.
 */
public interface EgressAuthorizer {

    /**
     * @param capabilityToken the opaque bearer credential exactly as presented; never logged, never stored
     * @return the decision; an unreachable authority is a denial with
     *     {@link DenialReason#AUTHORIZATION_UNAVAILABLE}, never an exception the caller might treat as
     *     transient and retry past
     */
    AuthorizationDecision authorize(String capabilityToken, CanonicalDestination destination);
}
