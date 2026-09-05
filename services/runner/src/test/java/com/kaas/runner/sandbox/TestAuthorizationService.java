package com.kaas.runner.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real HTTP authorization service, speaking the wire format the proxy actually sends.
 *
 * <p>Not a mock of {@code EgressAuthorizer}: the proxy under test runs in a container and reaches this over a
 * socket, so nothing in this process could substitute an interface for it even if that were desirable. What
 * that buys is that the request shape, the JSON encoding, the status handling, and the fail-closed behaviour
 * when this service stops answering are all exercised rather than assumed.
 *
 * <p>The verdict is a single mutable reference so a test can fence an assignment <em>while a tunnel is
 * open</em> and measure how long the tunnel survives it.
 */
final class TestAuthorizationService implements AutoCloseable {

    record Request(String token, String destination) {}

    private final HttpServer server;

    private final List<Request> received = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> verdict = new AtomicReference<>("AUTHORIZED");

    private final AtomicReference<String> allowedDestination = new AtomicReference<>(null);

    private volatile boolean answering = true;

    TestAuthorizationService() throws IOException {
        // Bound wide, not to loopback: the proxy reaches this from inside a container, arriving at the host's
        // gateway address. A loopback-bound service is simply invisible from there.
        this.server = HttpServer.create(new InetSocketAddress(0), 8);
        this.server.createContext("/internal/v1/egress/authorizations", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String token = extract(body, "capabilityToken");
            String destination = extract(body, "host") + ":" + extract(body, "port") + "/" + extract(body, "scheme");
            received.add(new Request(token, destination));

            if (!answering) {
                // A control plane that is up but broken. The proxy must treat this as "I could not ask",
                // which is a denial, rather than retrying or falling back to a previous answer.
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String decided = verdict.get();
            String allowed = allowedDestination.get();
            if ("AUTHORIZED".equals(decided) && allowed != null && !allowed.equals(destination)) {
                decided = "DENIED:DESTINATION_NOT_ALLOWED";
            }
            String json = decided.startsWith("DENIED:")
                    ? "{\"decision\":\"DENIED\",\"reason\":\"" + decided.substring("DENIED:".length()) + "\"}"
                    : "{\"decision\":\"AUTHORIZED\"}";
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        this.server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<Request> received() {
        return List.copyOf(received);
    }

    /** Only this canonical destination is authorized; everything else is refused by policy. */
    void allowOnly(String canonicalDestination) {
        allowedDestination.set(canonicalDestination);
        verdict.set("AUTHORIZED");
    }

    /** Fences everything, as a cancellation or a lost lease would. Takes effect on the next revalidation. */
    void fence() {
        verdict.set("DENIED:ASSIGNMENT_FENCED");
    }

    void denyWith(String reason) {
        verdict.set("DENIED:" + reason);
    }

    /** Stops giving answers without stopping listening, which is the harder failure to handle correctly. */
    void stopAnswering() {
        answering = false;
    }

    /**
     * Pulls one field out of the request body.
     *
     * <p>Deliberately crude. Bringing a JSON parser in here would mean the test agreed with the proxy because
     * both used the same library, and what is being checked is that the proxy sends the fields this service
     * expects to find — a claim about the wire, not about a parser.
     */
    private static String extract(String body, String field) {
        String key = "\"" + field + "\":";
        int at = body.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = at + key.length();
        if (body.charAt(start) == '"') {
            return body.substring(start + 1, body.indexOf('"', start + 1));
        }
        int end = start;
        while (end < body.length() && "0123456789".indexOf(body.charAt(end)) >= 0) {
            end++;
        }
        return body.substring(start, end);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
