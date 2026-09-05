package com.kaas.egress;

/**
 * Why a request was not carried. Stable, low-cardinality, and safe to publish.
 *
 * <p>These are the only values that reach metrics and cross-tenant operational logs. In particular a denial
 * never carries the resolved address: an address can describe a tenant's internal topology, and an operational
 * log is read by people who are not that tenant. {@link #ADDRESS_NOT_GLOBAL} plus the {@link AddressClass} is
 * as specific as a shared surface gets.
 */
public enum DenialReason {
    /** The destination is not in the policy bound to this execution. */
    DESTINATION_NOT_ALLOWED,

    /** The destination resolved to something outside global unicast. */
    ADDRESS_NOT_GLOBAL,

    /** The assignment behind the capability is no longer the live one. */
    ASSIGNMENT_FENCED,

    /** The capability's own window has closed. Expiry, not revocation. */
    CAPABILITY_EXPIRED,

    /** The presented credential does not identify a live capability at all. */
    CAPABILITY_INVALID,

    /** The run is no longer in a state that may produce traffic. */
    RUN_NOT_EXECUTING,

    /** The control plane could not be asked. Refused rather than assumed. */
    AUTHORIZATION_UNAVAILABLE,

    /** The name did not resolve, or resolved to nothing usable. */
    DNS_FAILED,

    /** The request was not well formed enough to have one meaning. */
    MALFORMED_REQUEST,

    /** The proxy could not reach an authorized destination. Not a policy decision. */
    TARGET_UNREACHABLE
}
