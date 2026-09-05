package com.kaas.api.execution.domain;

/**
 * The protocol classes an allowlist entry may name.
 *
 * <p>Part of the entry rather than inferred from the port, because {@code 443/HTTP} and {@code 443/HTTPS} are
 * different propositions about what the proxy will do — one forwards a request it can read, the other opens a
 * tunnel it cannot. A tenant authorizing one has not authorized the other.
 */
public enum EgressScheme {
    /** A forward-proxied request. The proxy sees the request line and can enforce against it. */
    HTTP,
    /** A CONNECT tunnel. The proxy sees the authority and then opaque bytes. */
    HTTPS
}
