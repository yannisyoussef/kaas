package com.kaas.api.execution.domain;

import java.util.Locale;

/**
 * One destination an allowlist permits: a canonical host, an explicit port, and a scheme.
 *
 * <p>Canonicalization is specified in {@code packages/api-contracts/egress-allowlist-canonicalization.md} and
 * implemented independently here and in the trusted egress proxy. The proxy must not depend on control-plane
 * code — it is trusted infrastructure and a shared library would make a change here silently a change there —
 * so the two agree by both implementing one written rule, and a contract test proves they still do.
 *
 * <p><strong>Nothing is silently rewritten.</strong> A host that is not already canonical is refused with a
 * reason rather than normalized into something else. Accepting {@code EXAMPLE.com.} and storing
 * {@code example.com} would mean the destination a tenant wrote and the destination the platform enforces are
 * two different strings, and the tenant would have no way to see the difference.
 */
public record EgressDestination(String host, int port, EgressScheme scheme) {

    /** RFC 1035 label and name limits, which also bound what the proxy has to parse. */
    private static final int MAX_LABEL = 63;

    private static final int MAX_HOST = 253;

    public EgressDestination {
        if (scheme == null) {
            throw new IllegalArgumentException("An egress destination names a scheme.");
        }
        if (port < 1 || port > 65535) {
            // No default and no inference from the scheme. A destination the tenant did not write down is a
            // destination the tenant did not authorize.
            throw new IllegalArgumentException("An egress destination names an explicit port between 1 and 65535.");
        }
        requireCanonicalHost(host);
    }

    /**
     * Refuses any host that is not already in canonical form.
     *
     * <p>Each rule here removes a second spelling of the same destination, or a spelling that two parsers would
     * read differently. The wildcard forms are refused at parse time rather than accepted and never matched,
     * because an entry that can never match is one a tenant believes is working.
     */
    private static void requireCanonicalHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("An egress destination names a host.");
        }
        if (host.length() > MAX_HOST) {
            throw new IllegalArgumentException("An egress host is at most " + MAX_HOST + " characters.");
        }
        if (host.endsWith(".")) {
            throw new IllegalArgumentException("An egress host carries no trailing dot.");
        }
        // ASCII lower-case only, checked with Locale.ROOT. In a Turkish locale the default lower-casing maps
        // 'I' to a dotless 'i', so the same entry would canonicalize differently depending on the JVM's locale.
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("An egress host is lower-case.");
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '.';
            if (!allowed) {
                // Covers Unicode, percent-encoding, userinfo, brackets, whitespace, and the wildcard forms in
                // one rule. Decoding any of them is where two implementations disagree.
                throw new IllegalArgumentException(
                        "An egress host uses only lower-case ASCII letters, digits, hyphens, and dots.");
            }
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()) {
                throw new IllegalArgumentException("An egress host has no empty labels.");
            }
            if (label.length() > MAX_LABEL) {
                throw new IllegalArgumentException("An egress host label is at most " + MAX_LABEL + " characters.");
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("An egress host label does not begin or end with a hyphen.");
            }
        }
        if (looksLikeIpLiteral(host)) {
            // Refused in v1. An entry names a hostname the proxy resolves, and the address classifier exists to
            // inspect what that resolution returned. A literal skips the step the classifier guards. Lifting
            // this requires running the classifier on the literal itself.
            throw new IllegalArgumentException("An egress host is a hostname, not an IP literal.");
        }
        if (!host.contains(".")) {
            // A single label is a local name, not a destination on a network the proxy can reach on the
            // tenant's behalf. Refusing it also removes 'localhost'.
            throw new IllegalArgumentException("An egress host is a fully qualified name.");
        }
    }

    /**
     * Whether this is a dotted-quad. IPv6 literals and bracketed forms are already refused by the character
     * rule above, so only the IPv4 shape needs recognising here.
     */
    private static boolean looksLikeIpLiteral(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                if (part.charAt(index) < '0' || part.charAt(index) > '9') {
                    return false;
                }
            }
        }
        return true;
    }

    /** The stored and compared form. Byte equality on this string is the whole of matching. */
    public String canonical() {
        return host + ":" + port + "/" + scheme.name();
    }
}
