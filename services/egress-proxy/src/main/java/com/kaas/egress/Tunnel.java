package com.kaas.egress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An established CONNECT tunnel, and the thing that keeps asking whether it should still exist.
 *
 * <h2>Why a tunnel needs its own control</h2>
 *
 * <p>Authorizing at CONNECT time and then relaying is the obvious implementation and it is wrong. Once the
 * tunnel is up, no further HTTP request crosses it — the bytes are TLS records — so nothing would ever cause
 * the authority to be checked again. An assignment fenced one second after CONNECT would leave a working
 * channel open for as long as the workload cared to hold it, which is exactly the authority model failing
 * silently rather than loudly.
 *
 * <p>So the tunnel revalidates on a timer, and closes both sockets the moment the answer stops being yes —
 * including when the answer cannot be obtained at all. Availability of the control plane is not a reason to
 * keep carrying traffic on an authority nobody can confirm.
 *
 * <p>The timer is monotonic. It measures an interval, not a deadline, so it is immune to the host clock being
 * adjusted underneath it; the absolute question of whether the capability has expired belongs to the control
 * plane, which owns the authoritative clock and answers it on every revalidation.
 */
final class Tunnel {

    private final Socket client;

    private final Socket target;

    private final String capabilityToken;

    private final CanonicalDestination destination;

    private final EgressAuthorizer authorizer;

    private final ProxyMetrics metrics;

    private final CountDownLatch finished = new CountDownLatch(2);

    private volatile DenialReason revokedBecause;

    Tunnel(
            Socket client,
            Socket target,
            String capabilityToken,
            CanonicalDestination destination,
            EgressAuthorizer authorizer,
            ProxyMetrics metrics) {
        this.client = client;
        this.target = target;
        this.capabilityToken = capabilityToken;
        this.destination = destination;
        this.authorizer = authorizer;
        this.metrics = metrics;
    }

    /**
     * Relays until one side closes or authority is withdrawn.
     *
     * @param relays where the two directions run; virtual threads, one blocked read each
     * @param supervisor where the revalidation timer runs
     */
    void relay(java.util.concurrent.ExecutorService relays, ScheduledExecutorService supervisor, ProxyConfiguration configuration)
            throws InterruptedException {
        long interval = configuration.revalidationInterval().toMillis();
        ScheduledFuture<?> revalidation = supervisor.scheduleWithFixedDelay(
                this::revalidate, interval, interval, TimeUnit.MILLISECONDS);
        try {
            relays.execute(() -> pump(client, target));
            relays.execute(() -> pump(target, client));
            finished.await();
        } finally {
            revalidation.cancel(true);
            closeQuietly(client);
            closeQuietly(target);
        }
    }

    /** The reason the tunnel was torn down by policy, or null if it ended for an ordinary reason. */
    DenialReason revokedBecause() {
        return revokedBecause;
    }

    private void revalidate() {
        AuthorizationDecision decision;
        try {
            metrics.authorizationRequested();
            decision = authorizer.authorize(capabilityToken, destination);
        } catch (RuntimeException unreachable) {
            // An authorizer that throws rather than returning a denial is a bug in that authorizer, but the
            // safe reading of "I could not tell you" is the same either way.
            decision = AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        }
        if (decision.authorized()) {
            return;
        }
        revokedBecause = decision.reason();
        metrics.denied(decision.reason());
        metrics.tunnelRevoked();
        // Closing the sockets is what stops the traffic. The relay threads are blocked in read; closing from
        // another thread makes those reads throw, which is the only way to interrupt them.
        closeQuietly(client);
        closeQuietly(target);
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException expected) {
            // Either the peer closed or the supervisor closed underneath us. Both end the tunnel.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            finished.countDown();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already-closed socket is not a failure worth propagating.
        }
    }
}
