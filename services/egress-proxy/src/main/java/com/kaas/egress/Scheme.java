package com.kaas.egress;

/**
 * The transport classes this proxy will carry. Nothing else exists: no raw TCP, no UDP, no SOCKS.
 *
 * <p>The scheme is part of a destination rather than inferred from its port, because {@code 443/HTTP} and
 * {@code 443/HTTPS} are different propositions about what the proxy will do with the connection.
 */
public enum Scheme {
    /** A forward-proxied request the proxy reads and relays. */
    HTTP,

    /** A CONNECT tunnel the proxy opens and then relays opaquely, leaving TLS end to end. */
    HTTPS
}
