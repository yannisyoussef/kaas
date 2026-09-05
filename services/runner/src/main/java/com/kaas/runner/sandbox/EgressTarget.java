package com.kaas.runner.sandbox;

import java.util.Locale;

/**
 * One destination an execution's policy names, as the runner received it.
 *
 * <h2>What this is for, and what it is not</h2>
 *
 * <p>It is <em>aiming material for the workload</em>: the platform's synthetic workload has to send its
 * request somewhere, and this says where. It is not authority and is never treated as any. The proxy resolves
 * the policy from authoritative state on every single request, so a destination that arrived here altered
 * would simply be refused by the proxy — the enforcement point is unaffected by anything in this record.
 *
 * <p>That is also why it does not travel inside the immutable command document. A second copy of the policy in
 * an artifact that nothing enforces from is a field the runtime ignores, and a policy field the runtime
 * ignores is a claim with no evidence behind it. The command binds the policy's revision id and canonical
 * digest, which is the part the runner independently verifies.
 *
 * <h2>Why it is a runner-local type</h2>
 *
 * <p>Deliberately not the control plane's {@code EgressDestination}. This module's build fails if it acquires
 * the control plane, and that guard is what lets it hold a Docker client at all. The shapes agree because they
 * both implement the written canonicalization contract, not because they share a class.
 */
public record EgressTarget(String host, int port, String scheme) {

    /** The two transport classes v1 supports. Anything else is refused rather than guessed at. */
    private static final String HTTP = "HTTP";

    private static final String HTTPS = "HTTPS";

    public EgressTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A destination names a host.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("A destination names a port in 1-65535: " + port);
        }
        scheme = scheme == null ? "" : scheme.toUpperCase(Locale.ROOT);
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
            throw new IllegalArgumentException("A destination is HTTP or HTTPS: " + scheme);
        }
        requireSafeHost(host);
    }

    /**
     * Refuses any host this runner could not safely place into an environment variable and a request line.
     *
     * <p><strong>Deliberately not a third implementation of the canonicalization contract.</strong> The
     * grammar has two implementations — the control plane's and the proxy's — which agree by both
     * implementing one written rule, and a third would be a third thing to drift. What this checks is
     * narrower and is about what the <em>runner</em> does with the value: it goes into a container's
     * environment and into an HTTP request line the probe writes, so whitespace, a control character, a CR
     * or LF, userinfo, a bracket, or a percent escape is a refusal regardless of what any policy says.
     *
     * <p>The character set it permits is exactly the contract's, so this can never reject a destination the
     * control plane accepted — a check that could would turn a legitimate policy into an execution failure.
     * And it refuses rather than repairs: quietly lower-casing or trimming would produce a runner that
     * disagrees with both other implementations about what it was told, silently.
     */
    private static void requireSafeHost(String host) {
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            // Locale.ROOT, because in a Turkish locale the default lower-casing maps 'I' to a dotless 'i' and
            // the same host would be judged differently depending on the JVM's locale.
            throw new IllegalArgumentException("A destination host arrives canonical, in lower case: " + host);
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '.';
            if (!permitted) {
                throw new IllegalArgumentException(
                        "A destination host carries only lower-case letters, digits, hyphens, and dots.");
            }
        }
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            // A trailing dot and an empty label are each a second spelling of the same name, and two
            // spellings are two things a policy comparison can disagree about.
            throw new IllegalArgumentException("A destination host has no empty label and no trailing dot.");
        }
        if (host.startsWith("-") || host.endsWith("-")) {
            throw new IllegalArgumentException("A destination host label does not begin or end with a hyphen.");
        }
        if (!host.contains(".")) {
            // Single-label names resolve through search domains, which differ per host — so the same entry
            // would mean different destinations on different machines. It also removes `localhost`.
            throw new IllegalArgumentException("A destination host is fully qualified: " + host);
        }
    }

    public boolean isTunnelled() {
        return HTTPS.equals(scheme);
    }
}
