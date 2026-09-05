package com.kaas.runner.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * The runner's side of the internal control-plane API.
 *
 * <p>Built on the JDK's own HTTP client rather than a framework's. This module has no Spring and no business
 * acquiring one: it holds container-runtime access, and every dependency it takes on is another thing running
 * inside the process that launches sandboxes. The JDK client is sufficient for four POSTs.
 *
 * <p><strong>Refusals are not retried.</strong> A 409 means the control plane has looked at live state and
 * decided this assignment may not proceed — retrying asks the same question of the same state and gets the same
 * answer, while burning the deadline the run is being measured against. Only transport failures and 5xx are
 * retried, because only those are claims about the control plane's availability rather than about this run.
 */
public final class ControlPlaneClient {

    /** Bounded, and small. The phase budgets are minutes; a retry policy that could outlast one is not a policy. */
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final URI baseUri;
    private final String authorization;
    private final Duration requestTimeout;
    private final Sleeper sleeper;

    public ControlPlaneClient(
            HttpClient http, URI baseUri, String authorization, Duration requestTimeout, Sleeper sleeper) {
        this.http = http;
        this.baseUri = baseUri;
        this.authorization = authorization;
        this.requestTimeout = requestTimeout;
        this.sleeper = sleeper;
    }

    /** Advances the run into a phase. */
    public Response advancePhase(UUID runId, UUID attemptId, String body) throws ControlPlaneUnavailable {
        return post("/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/phases", body);
    }

    /** Submits the result and completes the run. */
    public Response submitResult(UUID runId, UUID attemptId, String body) throws ControlPlaneUnavailable {
        return post("/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/results", body);
    }

    /** Reports that this assignment's infrastructure failed, stopping the run. */
    public Response reportInfrastructureFailure(UUID runId, UUID attemptId, String body)
            throws ControlPlaneUnavailable {
        return post("/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/infrastructure-failures", body);
    }

    /**
     * Renews the lease on this assignment.
     *
     * <p>Called on a timer for the whole of execution, not once. A lease is short on purpose — it is what lets
     * the platform reclaim work from a worker that has died — and the price of that is that a living worker has
     * to keep saying so. Without this the lease expires mid-run and the control plane refuses the next phase
     * advance, which looks exactly like a worker that lost its assignment.
     */
    public Response heartbeat(UUID runId, UUID attemptId, String body) throws ControlPlaneUnavailable {
        return post("/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/heartbeat", body);
    }

    /**
     * Renews this assignment's lease with exactly one attempt, bounded by {@code timeout}.
     *
     * <h2>Why not {@link #heartbeat}</h2>
     *
     * <p>The ordinary path retries three times with exponential backoff, so a single call can take three
     * request timeouts plus the backoff between them — around ninety seconds against a thirty-second request
     * timeout. A renewal that can block for longer than the lease it is renewing is not a renewal mechanism:
     * by the time it returns, the answer is about a lease that has already expired.
     *
     * <p>So this attempts once and reports failure immediately. The retrying happens in the authority
     * monitor's own loop instead, where each attempt is bounded and the remaining budget is what decides
     * whether there is time for another — rather than in a client that knows nothing about the lease.
     */
    public Response renewLease(UUID runId, UUID attemptId, String body, Duration timeout)
            throws ControlPlaneUnavailable {
        String path = "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/heartbeat";
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500) {
                // A server fault decides nothing about this assignment. Reported as unavailable so it
                // consumes the lease budget rather than being read as a refusal.
                throw new ControlPlaneUnavailable(
                        "The control plane returned " + response.statusCode() + " for a renewal.", null);
            }
            return new Response(response.statusCode(), response.body());
        } catch (IOException transport) {
            throw new ControlPlaneUnavailable("The control plane could not be reached for a renewal.", transport);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneUnavailable("Interrupted while renewing.", interrupted);
        }
    }

    /** Revalidates this assignment's authority and returns a fresh command. */
    public Response authorize(UUID runId, UUID attemptId, String body) throws ControlPlaneUnavailable {
        return post(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/execution-authorizations", body);
    }

    private Response post(String path, String body) throws ControlPlaneUnavailable {
        IOException lastTransportFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 500) {
                    // Includes 409. A refusal is an answer, and the caller decides what it means.
                    return new Response(response.statusCode(), response.body());
                }
                lastTransportFailure = new IOException("Control plane returned " + response.statusCode());
            } catch (IOException transport) {
                lastTransportFailure = transport;
            } catch (InterruptedException interrupted) {
                // Restore the flag rather than swallowing it. A runner whose thread was interrupted is being
                // shut down, and continuing to drive a sandbox after that is how orphans are created.
                Thread.currentThread().interrupt();
                throw new ControlPlaneUnavailable("Interrupted while calling the control plane.", interrupted);
            }
            if (attempt < MAX_ATTEMPTS) {
                // Exponential, and interruptible. A fixed delay across a fleet reconverges after an outage into
                // a synchronised retry wave, which is how a recovering control plane is knocked over again.
                try {
                    sleeper.sleep(Duration.ofMillis(200L << (attempt - 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ControlPlaneUnavailable("Interrupted while backing off.", interrupted);
                }
            }
        }
        throw new ControlPlaneUnavailable(
                "The control plane did not answer after " + MAX_ATTEMPTS + " attempts.", lastTransportFailure);
    }

    /** A status and a body. Deliberately not parsed here: this type knows about HTTP, not about commands. */
    public record Response(int status, String body) {

        public boolean ok() {
            return status == 200;
        }
    }

    /** Injected so a test can drive backoff without spending the wall-clock time it describes. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper real() {
            return duration -> Thread.sleep(duration.toMillis());
        }
    }
}
