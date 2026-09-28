package com.kaas.runner.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * The runner's health and metrics endpoint (KAAS-DEPLOY-001).
 *
 * <pre>
 *   GET /health/liveness    200 {"status":"UP"}         503 {"status":"DOWN"}
 *   GET /health/readiness   200 {"status":"UP",...}     503 {"status":"NOT_READY",...}
 *   GET /metrics            Prometheus text exposition
 * </pre>
 *
 * <p>Bound to the configured host and port -- loopback by default, so exposing it is a decision Operations makes,
 * not a default it has to undo. It serves status words, closed reason codes and aggregate counters; never a
 * host path, an address, an image, a worker id, a run or a tenant. It accepts GET and nothing else, and reads no
 * request body.
 *
 * <p>The JDK's own HTTP server, for the same reason the client is the JDK's: the process that holds a Docker
 * client should not also hold a web framework.
 */
final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService handlers;

    HealthServer(String host, int port, BooleanSupplier live, Readiness readiness, RunnerMetrics metrics)
            throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(host, port), 16);
        this.handlers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "kaas-runner-health");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(handlers);
        server.createContext("/health/liveness", exchange -> {
            boolean up = live.getAsBoolean();
            respond(exchange, up ? 200 : 503, "application/json", "{\"status\":\"" + (up ? "UP" : "DOWN") + "\"}");
        });
        server.createContext("/health/readiness", exchange -> {
            boolean ready = readiness.ready();
            respond(exchange, ready ? 200 : 503, "application/json",
                    "{\"status\":\"" + (ready ? "UP" : "NOT_READY") + "\",\"conditions\":" + json(readiness.report())
                            + "}");
        });
        server.createContext("/metrics", exchange ->
                respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.render()));
        server.createContext("/", exchange -> respond(exchange, 404, "application/json", "{\"status\":\"NOT_FOUND\"}"));
    }

    void start() {
        server.start();
    }

    /** The bound port; differs from the configured one only when the configuration asked for port 0. */
    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** Keys and values are condition names and closed reason codes; escaping is still applied. */
    private static String json(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
