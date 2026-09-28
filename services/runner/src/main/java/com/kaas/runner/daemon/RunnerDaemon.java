package com.kaas.runner.daemon;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import com.kaas.runner.execution.ExecutionLoop;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The long-lived production runner (KAAS-DEPLOY-001).
 *
 * <pre>
 *   startup
 *     configuration validated        (before this object exists)
 *     Docker reachable
 *     configured runtime registered, measured; images present
 *     attestation produced            (the real gates, this host, now)
 *     startup orphan reconciliation
 *     service identity obtained
 *     control plane reached           (the attestation is submitted and accepted)
 *   READY
 *     wait for work  -> claim  -> ExecutionLoop -> result -> wait again
 *   SIGTERM
 *     NOT READY, intake stops, no new claim; running assignments drain (bounded); maintenance stops;
 *     this process's own sandbox resources are removed; clients close; exit
 * </pre>
 *
 * <h2>Work intake is the internal API, never the broker</h2>
 *
 * <p>The runner asks the control plane for work over HTTP, in its own authenticated name, when it has a free
 * slot. It holds no RabbitMQ client, address or credential, and the execution host needs no route to a broker
 * (ADR-035). Waiting ({@code awaitWork}) and claiming ({@code claimAssignment}) are separate requests: a wait
 * creates no ownership, so shutdown may abandon one at any instant; a claim is short, is only sent while the
 * runner is accepting work, and is never interrupted -- whatever it returns is an assignment this runner owns
 * and drains like any other.
 *
 * <h2>One composition</h2>
 *
 * <p>{@link RunnerComposition} builds the production graph and hands its parts to this class; tests that need a
 * fake control plane or a fake assessor hand in theirs. The lifecycle below is the same object either way.
 */
public final class RunnerDaemon {

    /** Where an assignment is executed. Production: {@link ExecutionLoop#execute}. */
    @FunctionalInterface
    public interface AssignmentExecutor {
        ExecutionLoop.ExecutionReport execute(UUID runId, UUID attemptId, int assignmentEpoch)
                throws ControlPlaneUnavailable;
    }

    /** Host checks; returns the runtime implementation digest measured now, when the runtime passed. */
    @FunctionalInterface
    interface Preflight {
        Optional<String> check();
    }

    /** Removes abandoned KaaS-owned resources and says how many. */
    @FunctionalInterface
    interface Reconciler {
        int reconcile();
    }

    /** Everything the lifecycle drives. Built by {@link RunnerComposition} in production. */
    record Parts(
            RunnerConfiguration configuration,
            Readiness readiness,
            RunnerMetrics metrics,
            ControlPlaneClient controlPlane,
            ServiceIdentity identity,
            Preflight preflight,
            AttestationRefresher attestation,
            Reconciler reconciler,
            Runnable finalCleanup,
            AssignmentExecutor executor,
            Runnable closeClients,
            Clock clock,
            Duration maintenanceTick) {}

    /** How often the maintenance tick re-checks the host, the credential and the evidence, in production. */
    static final Duration TICK = Duration.ofSeconds(15);

    /** After the drain deadline, how long interrupted executions are given to clean up their sandboxes. */
    static final Duration CLEANUP_GRACE = Duration.ofSeconds(30);

    private final Parts parts;
    private final Readiness readiness;
    private final RunnerMetrics metrics;
    private final Semaphore slots;
    private final ExecutorService executions;
    private final ScheduledExecutorService maintenance;
    private final ScheduledExecutorService refreshes;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object intakeLock = new Object();
    private final Thread intake;
    private final Thread startup;
    private volatile Instant lastApiContact = Instant.EPOCH;

    /** Set under {@link #intakeLock} while a claim request is in flight; shutdown never interrupts one. */
    private boolean claiming;

    RunnerDaemon(Parts parts) {
        this.parts = Objects.requireNonNull(parts);
        this.readiness = parts.readiness();
        this.metrics = parts.metrics();
        int concurrency = parts.configuration().maxConcurrency();
        this.slots = new Semaphore(concurrency);
        AtomicInteger executionThreads = new AtomicInteger();
        this.executions = Executors.newFixedThreadPool(concurrency,
                task -> thread(task, "kaas-runner-execution-" + executionThreads.incrementAndGet()));
        this.maintenance = Executors.newSingleThreadScheduledExecutor(task -> thread(task, "kaas-runner-maintenance"));
        this.refreshes = Executors.newSingleThreadScheduledExecutor(task -> thread(task, "kaas-runner-attestation"));
        this.intake = thread(this::intakeLoop, "kaas-runner-intake");
        this.startup = thread(this::startupSequence, "kaas-runner-startup");
        metrics.gauge("kaas_runner_ready", "1 when this runner may accept a new assignment.",
                () -> readiness.ready() ? 1 : 0);
        metrics.gauge("kaas_runner_live", "1 while the runner process is healthy.", () -> live() ? 1 : 0);
        metrics.gauge("kaas_runner_active_assignments", "Assignments currently executing.", active::get);
        metrics.gauge("kaas_runner_max_concurrency", "Configured assignment slots.", () -> concurrency);
        metrics.gauge("kaas_runner_claim_api_available",
                "1 when the internal claim API answered this runner recently.",
                () -> readiness.isHeld(Readiness.Condition.CONTROL_PLANE) ? 1 : 0);
        metrics.gauge("kaas_runner_runtime_digest_match",
                "1 when the runtime measured now is the one the accepted evidence describes.",
                () -> readiness.isHeld(Readiness.Condition.RUNTIME_DIGEST_MATCH) ? 1 : 0);
        metrics.gauge("kaas_runner_service_credential_valid_seconds",
                "Seconds the held service credential remains valid.",
                () -> Math.max(0, Duration.between(parts.clock().instant(), parts.identity().expiresAt()).toSeconds()));
    }

    // ---------------------------------------------------------------- lifecycle

    /** Begins startup in the background. Liveness answers at once; readiness only after startup completes. */
    public void start() {
        startup.start();
    }

    /** Whether the process is healthy: not stopped, and its intake has not died underneath it. */
    public boolean live() {
        if (stopped.getCount() == 0) {
            return false;
        }
        // An intake thread that has started and then ended without shutdown is a runner that can no longer take
        // work and will never recover on its own: that is exactly what a restart is for.
        return !(intake.getState() == Thread.State.TERMINATED && !stopping.get());
    }

    public Readiness readiness() {
        return readiness;
    }

    public RunnerMetrics metrics() {
        return metrics;
    }

    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Graceful shutdown, in the order the contract states. Blocks until done; safe to call more than once.
     *
     * <ol>
     *   <li>NOT READY -- at once, before anything else, so the health endpoint and every later check agree;
     *   <li>intake stops: a wait in progress is abandoned (it owns nothing); a claim in progress is allowed to
     *       finish, and what it returns is drained -- it is never abandoned mid-flight;
     *   <li>running assignments drain within the shutdown timeout; then they are interrupted, which the
     *       execution loop treats as shutdown and cleans up after;
     *   <li>maintenance and refresh stop;
     *   <li>this process's own remaining sandbox resources are removed;
     *   <li>clients close.
     * </ol>
     */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            awaitQuietly();
            return;
        }
        readiness.drain();
        synchronized (intakeLock) {
            if (!claiming) {
                intake.interrupt();
            }
        }
        startup.interrupt();
        Duration timeout = parts.configuration().shutdownTimeout();
        joinQuietly(intake, parts.configuration().requestTimeout().plusSeconds(5));
        executions.shutdown();
        try {
            if (!executions.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executions.shutdownNow();
                executions.awaitTermination(CLEANUP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executions.shutdownNow();
            Thread.currentThread().interrupt();
        }
        maintenance.shutdownNow();
        refreshes.shutdownNow();
        try {
            parts.finalCleanup().run();
        } catch (RuntimeException cleanupFailed) {
            metrics.reconcileFailed();
        }
        try {
            parts.closeClients().run();
        } finally {
            stopped.countDown();
        }
    }

    // ---------------------------------------------------------------- startup

    private void startupSequence() {
        try {
            Duration backoff = Duration.ofSeconds(1);
            Optional<String> measured;
            // Host first: nothing else is worth attempting on a host that cannot run a sandbox safely.
            while (true) {
                measured = parts.preflight().check();
                if (measured.isPresent() && readiness.isHeld(Readiness.Condition.IMAGES)) {
                    break;
                }
                backoff = pause(backoff);
            }
            // Evidence, from the real gates, before anything is cleaned or anyone is asked.
            while (!parts.attestation().assess()) {
                backoff = pause(backoff);
            }
            // Startup reconciliation, completed -- not scheduled, not assumed. READY cannot precede it.
            while (!reconcile()) {
                backoff = pause(backoff);
            }
            readiness.holds(Readiness.Condition.STARTUP_RECONCILIATION);
            // Identity, then the control plane: the accepted submission is the proof of both.
            while (!parts.identity().available()) {
                readiness.set(Readiness.Condition.SERVICE_IDENTITY, false, parts.identity().lastFailure());
                backoff = pause(backoff);
            }
            readiness.holds(Readiness.Condition.SERVICE_IDENTITY);
            while (!parts.attestation().submit()) {
                if (!parts.attestation().assess()) {
                    backoff = pause(backoff);
                    continue;
                }
                backoff = pause(backoff);
            }
            contacted();
            parts.attestation().runtimeMatches(measured.orElseThrow());
            scheduleMaintenance();
            intake.start();
        } catch (InterruptedException interrupted) {
            // Shutdown during startup. Nothing was claimed; stop() does the rest.
        }
    }

    private Duration pause(Duration backoff) throws InterruptedException {
        if (stopping.get()) {
            throw new InterruptedException();
        }
        Thread.sleep(jittered(backoff).toMillis());
        Duration next = backoff.multipliedBy(2);
        return next.compareTo(parts.configuration().claimBackoffMax()) > 0
                ? parts.configuration().claimBackoffMax() : next;
    }

    private void scheduleMaintenance() {
        long tick = parts.maintenanceTick().toMillis();
        maintenance.scheduleWithFixedDelay(this::tick, tick, tick, TimeUnit.MILLISECONDS);
        long reconcile = parts.configuration().reconcileInterval().toMillis();
        maintenance.scheduleWithFixedDelay(this::reconcile, reconcile, reconcile, TimeUnit.MILLISECONDS);
        scheduleRefresh(parts.configuration().attestationRefreshInterval());
    }

    private void scheduleRefresh(Duration after) {
        if (stopping.get()) {
            return;
        }
        refreshes.schedule(() -> {
            boolean accepted = parts.attestation().refresh();
            if (accepted) {
                contacted();
            }
            scheduleRefresh(accepted ? parts.configuration().attestationRefreshInterval()
                    : parts.configuration().attestationRetryInterval());
        }, after.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Re-establishes every condition that can change underneath a running process. */
    private void tick() {
        try {
            Optional<String> measured = parts.preflight().check();
            if (measured.isPresent() && !parts.attestation().runtimeMatches(measured.orElseThrow())) {
                // The runtime changed under accepted evidence. NOT READY now, and re-measure now rather than at
                // the next scheduled refresh; the control plane decides whether the new binary is acceptable.
                refreshes.execute(() -> {
                    if (parts.attestation().refresh()) {
                        contacted();
                    }
                });
            }
            parts.attestation().recheck();
            readiness.set(Readiness.Condition.SERVICE_IDENTITY, parts.identity().available(),
                    parts.identity().lastFailure());
            // Deliberately no control-plane probe here. Reconnection belongs to the intake loop alone, which
            // backs off exponentially; a second prober on a fixed tick would turn an API outage into a steady
            // request stream from every runner at once -- exactly what a recovering control plane cannot take.
        } catch (RuntimeException unexpected) {
            // A tick that throws must not end the schedule: the next one re-checks everything from scratch.
        }
    }

    private boolean reconcile() {
        try {
            metrics.reconciled(parts.reconciler().reconcile());
            return true;
        } catch (RuntimeException failed) {
            metrics.reconcileFailed();
            return false;
        }
    }

    // ---------------------------------------------------------------- intake

    private void intakeLoop() {
        Duration backoff = parts.configuration().claimBackoffInitial();
        while (!stopping.get()) {
            try {
                if (!readiness.ready()) {
                    if (!readiness.isHeld(Readiness.Condition.CONTROL_PLANE) && onlyControlPlaneMissing()) {
                        backoff = probeOrBackOff(backoff);
                    } else {
                        readiness.awaitChange(1_000);
                    }
                    continue;
                }
                slots.acquire();
                boolean handedToExecution = false;
                try {
                    synchronized (intakeLock) {
                        if (stopping.get()) {
                            break;
                        }
                        claiming = true;
                    }
                    Optional<ControlPlaneClient.Claimed> claimed;
                    try {
                        metrics.claimAttempted();
                        claimed = parts.controlPlane().claimAssignment();
                    } finally {
                        synchronized (intakeLock) {
                            claiming = false;
                        }
                    }
                    contacted();
                    backoff = parts.configuration().claimBackoffInitial();
                    if (claimed.isPresent()) {
                        metrics.claimSucceeded();
                        // Owned now, whatever else is happening -- including a shutdown that began while the
                        // claim was in flight. It is executed and drained, never dropped.
                        dispatch(claimed.orElseThrow());
                        handedToExecution = true;
                        continue;
                    }
                    metrics.claimEmpty();
                } finally {
                    if (!handedToExecution) {
                        slots.release();
                    }
                }
                if (stopping.get()) {
                    break;
                }
                metrics.claimPolled();
                parts.controlPlane().awaitWork(parts.configuration().claimWait());
                contacted();
            } catch (InterruptedException interrupted) {
                // Shutdown abandoned a wait or a backoff. Neither owns anything.
                break;
            } catch (ControlPlaneUnavailable unavailable) {
                metrics.claimFailed();
                readiness.set(Readiness.Condition.CONTROL_PLANE, false, "CONTROL_PLANE_UNAVAILABLE");
                try {
                    backoff = sleepBackoff(backoff);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
    }

    private void dispatch(ControlPlaneClient.Claimed claimed) {
        active.incrementAndGet();
        metrics.executionStarted();
        executions.execute(() -> {
            String status = "CRASHED";
            try {
                status = parts.executor()
                        .execute(claimed.runId(), claimed.attemptId(), claimed.assignmentEpoch())
                        .status();
            } catch (ControlPlaneUnavailable | RuntimeException failed) {
                // The run's lease will lapse and the control plane will fence it: the platform learns of this
                // failure the way it learns of any worker that stopped reporting.
            } finally {
                metrics.executionCompleted(status);
                active.decrementAndGet();
                slots.release();
            }
        });
    }

    private boolean onlyControlPlaneMissing() {
        for (Readiness.Condition condition : Readiness.Condition.values()) {
            if (condition != Readiness.Condition.CONTROL_PLANE && !readiness.isHeld(condition)) {
                return false;
            }
        }
        return true;
    }

    private Duration probeOrBackOff(Duration backoff) throws InterruptedException {
        if (probeControlPlane()) {
            return parts.configuration().claimBackoffInitial();
        }
        return sleepBackoff(backoff);
    }

    /** A zero-length wait: authenticated, claims nothing, and proves the API answers this runner. */
    private boolean probeControlPlane() {
        try {
            parts.controlPlane().awaitWork(Duration.ZERO);
            contacted();
            return true;
        } catch (ControlPlaneUnavailable unavailable) {
            readiness.set(Readiness.Condition.CONTROL_PLANE, false, "CONTROL_PLANE_UNAVAILABLE");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Duration sleepBackoff(Duration backoff) throws InterruptedException {
        Thread.sleep(jittered(backoff).toMillis());
        Duration next = backoff.multipliedBy(2);
        return next.compareTo(parts.configuration().claimBackoffMax()) > 0
                ? parts.configuration().claimBackoffMax() : next;
    }

    private void contacted() {
        lastApiContact = parts.clock().instant();
        readiness.holds(Readiness.Condition.CONTROL_PLANE);
    }

    /** Between half and all of the nominal delay, so a fleet recovering from one outage does not reconverge. */
    private static Duration jittered(Duration nominal) {
        long millis = Math.max(1, nominal.toMillis());
        return Duration.ofMillis(millis / 2 + ThreadLocalRandom.current().nextLong(millis / 2 + 1));
    }

    Instant lastApiContact() {
        return lastApiContact;
    }

    private void awaitQuietly() {
        try {
            stopped.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread thread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(false);
        return thread;
    }
}
