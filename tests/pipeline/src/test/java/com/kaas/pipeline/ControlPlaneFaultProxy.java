package com.kaas.pipeline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A control plane that can be made unreachable for renewals only.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Two of the properties this slice claims are about what happens when the control plane cannot be reached:
 * a worker must survive a transient outage inside its lease budget, and must stop itself once that budget is
 * gone. Neither can be tested by stopping the real server — the worker needs it for authorization and for the
 * phase transitions that prove what happened afterwards.
 *
 * <p>So this forwards everything to the real control plane and can be told to fail <em>only</em> the heartbeat
 * path. Everything else stays genuinely live, which means a test can watch a run terminate for lack of
 * renewals and then ask the real database what became of it.
 *
 * <p>It fails renewals with a transport-level reset rather than a status code, because that is the shape of a
 * real outage and it is the shape the client turns into "unavailable". A 503 would be a different test.
 */
final class ControlPlaneFaultProxy implements AutoCloseable {

    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicBoolean renewalsFail = new AtomicBoolean();
    private final AtomicInteger renewalsSeen = new AtomicInteger();
    private final URI upstream;

    ControlPlaneFaultProxy(int upstreamPort) throws IOException {
        this.upstream = URI.create("http://127.0.0.1:" + upstreamPort);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        this.server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Stops answering renewals. Everything else keeps working. */
    void failRenewals() {
        renewalsFail.set(true);
    }

    /** Answers renewals again. */
    void restoreRenewals() {
        renewalsFail.set(false);
    }

    int renewalsSeen() {
        return renewalsSeen.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean renewal = exchange.getRequestURI().getPath().endsWith("/heartbeat");
        if (renewal) {
            renewalsSeen.incrementAndGet();
            if (renewalsFail.get()) {
                // Closed without a response. The client sees a transport failure, which is what an outage
                // looks like from a worker's side.
                exchange.close();
                return;
            }
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        HttpRequest.Builder forward = HttpRequest.newBuilder(upstream.resolve(exchange.getRequestURI()))
                .timeout(Duration.ofSeconds(30))
                .method(
                        exchange.getRequestMethod(),
                        body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            // The hop-by-hop and length headers are the client's to set; copying them produces a request the
            // JDK client refuses to send.
            if (!name.equalsIgnoreCase("Host")
                    && !name.equalsIgnoreCase("Content-Length")
                    && !name.equalsIgnoreCase("Connection")
                    && !name.equalsIgnoreCase("Upgrade")
                    && !name.equalsIgnoreCase("Expect")) {
                values.forEach(value -> forward.header(name, value));
            }
        });
        try {
            HttpResponse<byte[]> response =
                    client.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] payload = response.body();
            exchange.sendResponseHeaders(response.statusCode(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(502, -1);
        } catch (IOException upstreamFailure) {
            exchange.sendResponseHeaders(502, -1);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
