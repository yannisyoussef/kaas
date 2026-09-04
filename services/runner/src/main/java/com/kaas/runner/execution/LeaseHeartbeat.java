package com.kaas.runner.execution;

import com.kaas.runner.client.ControlPlaneClient;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps this assignment's lease alive for as long as the execution runs.
 *
 * <p>The lease is deliberately short — it is the mechanism by which the platform reclaims work from a worker
 * that has died, and a long lease means a dead worker holds capacity for a long time. The cost of a short lease
 * is that a LIVING worker has to keep saying so, continuously, for the whole of a run that may last far longer
 * than one lease period.
 *
 * <p>Without this the arithmetic simply does not work: a thirty-second lease cannot span a thirty-minute
 * execution budget, so every run longer than the lease was refused mid-flight and then recorded as having timed
 * out during execution. Both halves of that diagnosis were false — the workload had finished, and nothing had
 * timed out except the lease nobody was renewing.
 *
 * <p><strong>A failed heartbeat does not stop the execution.</strong> It cannot: the sandbox is already running,
 * and killing it on one missed renewal would turn a transient network blip into a lost run. The control plane
 * is the authority on whether this worker still owns the assignment, and it re-decides that on the next phase
 * advance. This thread's job is to give a healthy worker every chance to keep its lease, not to adjudicate.
 */
public final class LeaseHeartbeat implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private LeaseHeartbeat(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Starts renewing immediately and keeps going until closed.
     *
     * @param interval how often to renew. Must be comfortably shorter than the lease — a renewal that lands as
     *     the lease expires has no margin for a slow request, and the lease the platform grants is not
     *     something this process can see.
     */
    public static LeaseHeartbeat start(
            ControlPlaneClient controlPlane,
            UUID runId,
            UUID attemptId,
            int assignmentEpoch,
            String body,
            java.time.Duration interval) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            // A daemon thread, so a runner that is shutting down is never held open by a heartbeat timer.
            Thread thread = new Thread(runnable, "kaas-lease-heartbeat-" + runId);
            thread.setDaemon(true);
            return thread;
        });
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(scheduler);
        scheduler.scheduleAtFixedRate(
                () -> {
                    if (!heartbeat.running.get()) {
                        return;
                    }
                    try {
                        controlPlane.heartbeat(runId, attemptId, body);
                    } catch (RuntimeException | com.kaas.runner.client.ControlPlaneUnavailable ignored) {
                        // Deliberately swallowed. Every outcome this can produce — unreachable, refused,
                        // superseded — is re-decided authoritatively by the next phase advance, and a heartbeat
                        // thread that threw would kill the timer and guarantee the lease lapses. Failing quietly
                        // and trying again is strictly better than failing loudly and stopping.
                    }
                },
                0,
                Math.max(1, interval.toMillis()),
                TimeUnit.MILLISECONDS);
        return heartbeat;
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
