package com.kaas.egress;

/**
 * A request target reduced to the one form the allowlist is compared against.
 *
 * <p>This is an independent implementation of
 * {@code packages/api-contracts/egress-allowlist-canonicalization.md}. The control plane implements the same
 * written rules in its own code. Neither imports the other: the proxy is trusted infrastructure that must not
 * carry a runtime dependency on control-plane implementation, and a shared library would make a change on one
 * side silently a change on the other. A contract test drives both from the same table of cases.
 *
 * <p>Every rule below refuses a spelling rather than repairing it. That matters more here than in the control
 * plane. The control plane refuses a non-canonical entry to keep stored policy honest; the proxy refuses a
 * non-canonical request because repairing one is how a request for a destination nobody authorized becomes a
 * request for one somebody did. {@code EXAMPLE.com.} normalized to {@code example.com} is a decision the
 * parser made on the caller's behalf, and the caller is hostile.
 */
public record CanonicalDestination(String host, int port, Scheme scheme) {

    private static final int MAX_LABEL = 63;

    private static final int MAX_HOST = 253;

    public CanonicalDestination {
        if (scheme == null) {
            throw new MalformedDestination("A destination names a scheme.");
        }
        if (port < 1 || port > 65535) {
            throw new MalformedDestination("A destination names a port between 1 and 65535.");
        }
        checkHost(host);
    }

    /**
     * Parses an {@code authority} of the form {@code host:port} — the form a CONNECT request line carries, and
     * the form an absolute-form request URI's authority reduces to.
     *
     * <p>Deliberately not {@link java.net.URI}. A general URI parser accepts userinfo, percent-encoding,
     * bracketed IPv6, empty ports, and scheme-relative forms, and its normalization behaviour has changed
     * between JDK versions. What is wanted here is not a lenient parser but a strict recogniser: anything that
     * is not exactly one canonical host, one colon, and one run of digits is not a destination.
     */
    public static CanonicalDestination parseAuthority(String authority, Scheme scheme) {
        if (authority == null || authority.isEmpty()) {
            throw new MalformedDestination("A destination names a host and a port.");
        }
        // A single colon, found from the left. Searching from the right would let "a:1:2" parse as host "a:1",
        // which the host rules would then reject — the same outcome by luck rather than by rule.
        int colon = authority.indexOf(':');
        if (colon < 0) {
            // No inference from the scheme. https does not imply 443: a destination the tenant did not write
            // down is a destination the tenant did not authorize.
            throw new MalformedDestination("A destination names an explicit port.");
        }
        if (authority.indexOf(':', colon + 1) >= 0) {
            throw new MalformedDestination("A destination names exactly one port.");
        }
        String host = authority.substring(0, colon);
        String port = authority.substring(colon + 1);
        if (port.isEmpty() || port.length() > 5) {
            throw new MalformedDestination("A destination names a port between 1 and 65535.");
        }
        for (int index = 0; index < port.length(); index++) {
            char digit = port.charAt(index);
            if (digit < '0' || digit > '9') {
                // Rejects "+80", "80 ", "0x50", and every other form Integer.parseInt would either accept or
                // reject for reasons of its own.
                throw new MalformedDestination("A destination names a port in decimal digits.");
            }
        }
        if (port.length() > 1 && port.charAt(0) == '0') {
            // "080" and "80" would otherwise be two spellings of one destination.
            throw new MalformedDestination("A destination names a port without leading zeroes.");
        }
        return new CanonicalDestination(host, Integer.parseInt(port), scheme);
    }

    /**
     * Refuses any host not already canonical.
     *
     * <p>Written as a single scan with explicit character classes rather than a regular expression or a chain
     * of library calls. A regular expression here would be one more thing whose behaviour on an unusual input —
     * an embedded NUL, a line terminator, a supplementary code point — has to be reasoned about separately from
     * the rule it is supposed to express.
     */
    private static void checkHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new MalformedDestination("A destination names a host.");
        }
        if (host.length() > MAX_HOST) {
            throw new MalformedDestination("A host is at most " + MAX_HOST + " characters.");
        }
        if (host.charAt(host.length() - 1) == '.') {
            throw new MalformedDestination("A host carries no trailing dot.");
        }

        int labelStart = 0;
        int labels = 0;
        boolean everyLabelNumeric = true;
        for (int index = 0; index <= host.length(); index++) {
            char character = index < host.length() ? host.charAt(index) : '.';
            if (character != '.') {
                boolean digit = character >= '0' && character <= '9';
                boolean lower = character >= 'a' && character <= 'z';
                if (!digit && !lower && character != '-') {
                    // One rule covering upper case, Unicode, percent-encoding, userinfo '@', brackets,
                    // whitespace, embedded NUL, CR, LF, and the wildcard forms. None of them is decoded or
                    // stripped: decoding is exactly where two implementations of this document would part
                    // company, and stripping is how a request becomes a different request.
                    throw new MalformedDestination(
                            "A host uses only lower-case ASCII letters, digits, hyphens, and dots.");
                }
                if (!digit) {
                    everyLabelNumeric = false;
                }
                continue;
            }
            int length = index - labelStart;
            if (length == 0) {
                throw new MalformedDestination("A host has no empty labels.");
            }
            if (length > MAX_LABEL) {
                throw new MalformedDestination("A host label is at most " + MAX_LABEL + " characters.");
            }
            if (host.charAt(labelStart) == '-' || host.charAt(index - 1) == '-') {
                throw new MalformedDestination("A host label does not begin or end with a hyphen.");
            }
            labels++;
            labelStart = index + 1;
        }

        if (labels < 2) {
            // A single label is a name on some local search domain, not a destination reachable on the
            // tenant's behalf. This is also what removes "localhost" — as a rule about what a destination is,
            // rather than as an entry on a list of bad names that would need to stay complete forever.
            throw new MalformedDestination("A host is a fully qualified name.");
        }
        if (labels == 4 && everyLabelNumeric) {
            // A dotted quad. Refused in v1: a destination names something the proxy resolves, and the address
            // classifier's entire job is to inspect what the resolution returned. A literal arrives with the
            // resolution already done by the caller.
            //
            // Other numeric spellings of an address — 2130706433, 0x7f.0.0.1, 017700000001 — do not need a
            // rule here. Either they are a single label, which is already refused, or they are a name this
            // proxy will hand to DNS as a name; nothing in this path ever calls a resolver that reinterprets
            // a hostname as an address, and whatever DNS returns is classified before anything connects to it.
            throw new MalformedDestination("A host is a hostname, not an IP literal.");
        }
    }

    /** The compared form. Byte equality on this string is the whole of matching — never a suffix test. */
    public String canonical() {
        return host + ":" + port + "/" + scheme.name();
    }
}
