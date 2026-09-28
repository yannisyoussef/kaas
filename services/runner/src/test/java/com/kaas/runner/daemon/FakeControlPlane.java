package com.kaas.runner.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An internal API that answers the way the real one does on the three paths the daemon itself calls, and
 * records every request it received, when, and with which credential.
 *
 * <p>Deliberately not the real control plane. These tests are about the runner's lifecycle -- ordering,
 * shutdown, backoff, readiness -- and the real control plane's claim semantics are proved against a real one in
 * {@code WorkerClaimEndpointTests} and the deployment pipeline suite.
 */
final class FakeControlPlane implements AutoCloseable {
    record Request(String path, String authorization, long atNanos, String body) {}

    private final HttpServer server;
    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedQueue<String> assignments = new ConcurrentLinkedQueue<>();
    final AtomicBoolean down = new AtomicBoolean();
    final AtomicBoolean attestationAccepted = new AtomicBoolean(true);
    final AtomicLong claimDelayMillis = new AtomicLong();
    final AtomicLong waitHoldMillis = new AtomicLong(-1);
    final AtomicInteger inFlightWaits = new AtomicInteger();
    volatile String attestationRefusalCode = "RUNTIME_IMPLEMENTATION_MISMATCH";
    volatile Instant usableUntil = Instant.now().plusSeconds(24 * 3600);
    volatile java.util.function.Consumer<String> onRequest = path -> { };

    FakeControlPlane() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "fake-control-plane");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/internal/v1/assignments/waits", this::await);
        server.createContext("/internal/v1/assignments", this::claim);
        server.createContext("/internal/v1/sandbox-attestations", this::attestation);
        // Everything an execution loop calls about a run. Recorded and refused: these tests are about which parts
        // exist and in what order they act, not about executing.
        server.createContext("/internal/v1/runs/", exchange -> {
            record(exchange);
            respond(exchange, 409, "{\"code\":\"ASSIGNMENT_NOT_CURRENT\"}");
        });
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<Request> requestsTo(String path) {
        synchronized (requests) {
            return requests.stream().filter(request -> request.path().equals(path)).toList();
        }
    }

    /** A run the next claim hands out. */
    String assign(java.util.UUID runId) {
        String body = "{\"runId\":\"" + runId + "\",\"attemptId\":\"" + java.util.UUID.randomUUID()
                + "\",\"assignmentEpoch\":1}";
        assignments.add(body);
        return body;
    }

    private void claim(HttpExchange exchange) throws IOException {
        String body = record(exchange);
        if (down.get()) {
            respond(exchange, 503, "{}");
            return;
        }
        sleep(claimDelayMillis.get());
        String assignment = assignments.poll();
        if (assignment == null) {
            respond(exchange, 204, null);
        } else {
            respond(exchange, 200, assignment);
        }
    }

    private void await(HttpExchange exchange) throws IOException {
        String body = record(exchange);
        if (down.get()) {
            respond(exchange, 503, "{}");
            return;
        }
        inFlightWaits.incrementAndGet();
        try {
            long requested = Long.parseLong(body.replaceAll("\\D", "").isEmpty() ? "0" : body.replaceAll("\\D", ""));
            long hold = waitHoldMillis.get() >= 0 ? waitHoldMillis.get() : requested;
            long deadline = System.nanoTime() + hold * 1_000_000;
            while (System.nanoTime() < deadline && assignments.isEmpty()) {
                sleep(20);
            }
            respond(exchange, assignments.isEmpty() ? 204 : 200, assignments.isEmpty() ? null : "{\"available\":true}");
        } finally {
            inFlightWaits.decrementAndGet();
        }
    }

    private void attestation(HttpExchange exchange) throws IOException {
        record(exchange);
        if (down.get()) {
            respond(exchange, 503, "{}");
            return;
        }
        if (attestationAccepted.get()) {
            respond(exchange, 201, "{\"code\":\"ACCEPTED\",\"attestationId\":\"a\",\"assessedAt\":\""
                    + Instant.now() + "\",\"usableUntil\":\"" + usableUntil + "\"}");
        } else {
            respond(exchange, 422, "{\"code\":\"" + attestationRefusalCode + "\"}");
        }
    }

    private String record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        onRequest.accept(exchange.getRequestURI().getPath());
        requests.add(new Request(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), System.nanoTime(), body));
        return body;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException clientWentAway) {
            // The runner abandoned a wait. That is the point of some of these tests.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** An unsigned JWT-shaped token: the runner reads sub and exp and never verifies, by design. */
    static String token(String subject, Instant expiresAt) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"" + subject + "\",\"exp\":" + expiresAt.getEpochSecond()
                + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".c2lnbmF0dXJl";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
