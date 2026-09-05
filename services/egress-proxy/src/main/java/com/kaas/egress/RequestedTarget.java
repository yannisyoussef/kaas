package com.kaas.egress;

import java.util.Locale;

/**
 * The destination a request is asking for, and the path it wants at that destination.
 *
 * <p>A request can state its destination in two places — the request line and the {@code Host} header — and
 * the entire point of this class is that they must agree. Authorizing the one and connecting with the other
 * is a complete bypass: a policy check against {@code allowed.example.com} followed by a connection driven by
 * {@code Host: internal.example}, or the reverse, depending on which one the code downstream happens to read.
 * Here they are reconciled once, before anything is authorized, and a disagreement is refused rather than
 * resolved in favour of either.
 *
 * <p>Only the destination is examined. Method, path, query, and body are carried through untouched: this is
 * egress policy, not a web application firewall. A tenant with an authorized destination may send whatever it
 * likes to it.
 */
public record RequestedTarget(CanonicalDestination destination, String originFormTarget) {

    /**
     * Reads the destination out of a CONNECT request.
     *
     * <p>The request line carries authority-form — {@code host:port} and nothing else. An absolute URI here is
     * refused rather than parsed, because a CONNECT whose target has a scheme and a path is a request whose
     * author expected something other than a tunnel.
     */
    public static RequestedTarget ofConnect(ProxyRequest request) {
        String authority = request.target();
        if (authority.contains("/") || authority.contains("://")) {
            throw new MalformedRequest("A CONNECT names an authority, not a URL.");
        }
        // HTTPS is the scheme class for a tunnel. A CONNECT to a port the tenant allowlisted as HTTP is
        // therefore denied by policy: an opaque tunnel to port 80 is not the thing "80/HTTP" authorized, and
        // treating it as such would be a way to obtain an uninspected byte channel to an HTTP destination.
        CanonicalDestination destination = CanonicalDestination.parseAuthority(authority, Scheme.HTTPS);
        requireAgreeingHost(request, destination);
        return new RequestedTarget(destination, null);
    }

    /**
     * Reads the destination out of an ordinary forward-proxied request, whose target is an absolute URI.
     *
     * <p>Origin-form ({@code GET /path}) is refused: it names no destination, so a proxy that accepted it
     * would have to take the destination from the Host header alone, which is the single-source-of-truth this
     * class exists to avoid relying on.
     */
    public static RequestedTarget ofAbsoluteForm(ProxyRequest request) {
        String target = request.target();
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            // The proxy does not terminate TLS. Doing so would mean presenting a certificate the tenant's
            // client had to be configured to trust, and inspecting plaintext the platform has no business
            // seeing. HTTPS goes through CONNECT, end to end.
            throw new MalformedRequest("An https destination is reached through CONNECT, not through the proxy.");
        }
        if (!lower.startsWith("http://")) {
            throw new MalformedRequest("A proxied request names an absolute http URL.");
        }
        String rest = target.substring("http://".length());
        int cut = rest.length();
        for (int index = 0; index < rest.length(); index++) {
            char character = rest.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                cut = index;
                break;
            }
        }
        String authority = rest.substring(0, cut);
        String path = cut == rest.length() ? "/" : rest.substring(cut);
        if (path.startsWith("?") || path.startsWith("#")) {
            path = "/" + path;
        }

        CanonicalDestination destination = parseWithDefaultPort(authority);
        requireAgreeingHost(request, destination);
        return new RequestedTarget(destination, path);
    }

    /**
     * Parses an authority that may omit the port, supplying HTTP's registered default.
     *
     * <p>This is the one place a default port is applied, and it applies to a <em>request</em>, never to a
     * policy entry. The distinction is deliberate. An entry with no port would be a tenant authorizing a
     * destination they did not name, so the control plane refuses it. A request with no port is an ordinary
     * HTTP client doing what the specification tells it to, and 80 is what {@code http://} means — there is no
     * second reading for an attacker to exploit. The port that results is then authorized explicitly, so the
     * comparison against policy is still between two written-down numbers.
     */
    private static CanonicalDestination parseWithDefaultPort(String authority) {
        if (authority.indexOf(':') < 0) {
            return CanonicalDestination.parseAuthority(authority + ":80", Scheme.HTTP);
        }
        return CanonicalDestination.parseAuthority(authority, Scheme.HTTP);
    }

    /**
     * Refuses a request whose two statements of its own destination differ.
     *
     * <p>Compared on the canonical form, so {@code Host: API.example.com} does not agree with
     * {@code api.example.com} by being lower-cased into agreement — it is simply not canonical and is refused.
     * A Host that is absent from an HTTP/1.1 request is also refused: HTTP/1.1 requires it, and its absence is
     * a way to leave only one of the two statements standing.
     *
     * <p>A Host that omits the port is taken to agree on the port. That is not a hole: the destination that
     * gets authorized and connected always comes from the request line, and the Host header is never consulted
     * for it. The header is checked because it is forwarded to the target and because a disagreement is
     * evidence of an attempt, not because anything downstream would otherwise trust it.
     */
    private static void requireAgreeingHost(ProxyRequest request, CanonicalDestination destination) {
        String host = request.singleHeader("host");
        if (host == null) {
            if ("HTTP/1.0".equals(request.version())) {
                // HTTP/1.0 predates the header. The request line still named the destination, which is the
                // statement being authorized, so there is nothing to disagree with.
                return;
            }
            throw new MalformedRequest("A request states its destination in a Host header.");
        }
        CanonicalDestination stated = host.indexOf(':') < 0
                ? CanonicalDestination.parseAuthority(host + ":" + destination.port(), destination.scheme())
                : CanonicalDestination.parseAuthority(host, destination.scheme());
        if (!stated.canonical().equals(destination.canonical())) {
            throw new MalformedRequest("A request states one destination in its target and another in its Host.");
        }
    }
}
