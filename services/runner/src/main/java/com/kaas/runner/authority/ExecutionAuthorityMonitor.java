package com.kaas.runner.authority;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The worker's continuously maintained evidence that it still owns the assignment it is executing.
 *
 * <h2>What this replaces, and why</h2>
 *
 * <p>The previous heartbeat renewed a lease and swallowed every outcome, on the stated grounds that authority
 * is re-decided at the next phase transition. That is true of <em>state</em>: database fencing already stops a
 * stale worker committing anything. It is not true of <em>execution</em>. A workload already inside a sandbox
 * kept running after cancellation, fencing, or lease expiry until it finished on its own or hit the sandbox
 * timeout — which is acceptable for a workload this repository wrote, and not acceptable for hostile code.
 *
 * <h2>The budget, and why it is monotonic</h2>
 *
 * <p>Each successful renewal returns the lease window as two instants <em>from the database's clock</em>. The
 * monitor takes their difference — a duration, computed inside one clock domain — and converts it into a
 * deadline on its own monotonic clock. From then on nothing compares a runner wall clock against a database
 * instant, so neither host's NTP corrections can lengthen the budget.
 *
 * <p>A safety margin is subtracted, so the worker stops <em>before</em> its authority can have expired rather
 * than after. Stopping late is the failure that matters: it means code ran with no authority behind it.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a task scheduler and not a retry framework. One thread, one assignment, at most one renewal in flight,
 * and a single terminal reason published once.
 */
public final class ExecutionAuthorityMonitor implements ExecutionAuthority, AutoCloseable {

    /**
     * One renewal attempt, already classified.
     *
     * @param decision what the control plane decided, or {@link AuthorityDecision#UNAVAILABLE} if it could not
     *     be asked
     * @param leaseWindow how long the lease has left according to the control plane's own clock, or null when
     *     the answer carried no window
     */
    public record Renewal(AuthorityDecision decision, Duration leaseWindow) {

        public static Renewal unavailable() {
            return new Renewal(AuthorityDecision.UNAVAILABLE, null);
        }
    }

    /** Asks the control plane once. Implementations must not throw; an unreachable server is a Renewal. */
    @FunctionalInterface
    public interface RenewalSource {
        Renewal renew();
    }

    private final RenewalSource source;
    private final MonotonicClock clock;
    private final Duration interval;
    private final Duration safetyMargin;
    private final Thread thread;

    /** The terminal reason. Written once by the monitor thread, read by the execution thread. */
    private final AtomicReference<AuthorityDecision> lost = new AtomicReference<>();

    /** The monotonic instant, in nanos, after which execution may no longer continue unrenewed. */
    private final AtomicLong deadlineNanos = new AtomicLong();

    private volatile boolean stopping;

    private ExecutionAuthorityMonitor(
            RenewalSource source,
            MonotonicClock clock,
            Duration interval,
            Duration safetyMargin,
            Duration initialBudget,
            String name) {
        this.source = source;
        this.clock = clock;
        this.interval = interval;
        this.safetyMargin = safetyMargin;
        this.deadlineNanos.set(clock.nanoTime() + budgetNanos(initialBudget));
        this.thread = new Thread(this::loop, name);
        this.thread.setDaemon(true);
    }

    /**
     * Starts monitoring.
     *
     * @param initialBudget how long execution may continue before the first successful renewal. The lease the
     *     platform granted at claim time is already running when this starts, so beginning with an unbounded
     *     budget would leave a window in which nothing bounds execution at all.
     */
    public static ExecutionAuthorityMonitor start(
            RenewalSource source,
            MonotonicClock clock,
            Duration interval,
            Duration safetyMargin,
            Duration initialBudget,
            String name) {
        ExecutionAuthorityMonitor monitor =
                new ExecutionAuthorityMonitor(source, clock, interval, safetyMargin, initialBudget, name);
        monitor.thread.start();
        return monitor;
    }

    private void loop() {
        while (!stopping && lost.get() == null) {
            // AT MOST ONE RENEWAL IN FLIGHT, ALWAYS.
            //
            // The renewal is called on this thread and the wait happens after it returns, which is the
            // fixed-DELAY shape rather than fixed-RATE. At a fixed rate a slow request causes the next tick to
            // fire immediately behind it, and a control plane that is merely slow gets a growing pile of
            // concurrent renewals from a worker that is trying to prove it is healthy.
            classify(source.renew());
            if (stopping || lost.get() != null) {
                break;
            }
            // Never sleep past the deadline. Waking after authority could already have expired would make the
            // budget an interval-rounding artifact rather than a bound.
            long remaining = deadlineNanos.get() - clock.nanoTime();
            long sleep = Math.min(interval.toNanos(), Math.max(0L, remaining));
            if (remaining <= 0) {
                expire();
                break;
            }
            if (!sleepNanos(sleep)) {
                return;
            }
            if (clock.nanoTime() - deadlineNanos.get() >= 0 && lost.get() == null && !stopping) {
                expire();
            }
        }
    }

    private void classify(Renewal renewal) {
        AuthorityDecision decision = renewal == null ? AuthorityDecision.UNRECOGNIZED : renewal.decision();
        if (decision.definitiveLoss()) {
            // Immediately, without waiting for the budget. There is nothing to wait for: the control plane has
            // already decided, and a later answer cannot restore an assignment that was taken away.
            lost.compareAndSet(null, decision);
            return;
        }
        if (decision.renewed() && renewal.leaseWindow() != null) {
            deadlineNanos.set(clock.nanoTime() + budgetNanos(renewal.leaseWindow()));
        }
        // Everything else — unavailable, a clock that did not advance — leaves the deadline where it is. The
        // budget is consumed by the passage of time rather than extended by the attempt.
    }

    private void expire() {
        // The lease can no longer be assumed valid. Not the same as being told it is gone, and it stops
        // execution just as firmly: continuing here would mean running on "last known good" indefinitely.
        lost.compareAndSet(null, AuthorityDecision.LEASE_EXPIRED);
    }

    /**
     * The remaining budget in nanoseconds, with the safety margin already removed.
     *
     * <p>Clamped at zero rather than allowed to go negative: a lease window that has already passed grants no
     * budget, and a negative deadline would read as an enormous one after the addition.
     */
    private long budgetNanos(Duration leaseWindow) {
        if (leaseWindow == null || leaseWindow.isNegative() || leaseWindow.isZero()) {
            return 0L;
        }
        return Math.max(0L, leaseWindow.minus(safetyMargin).toNanos());
    }

    private boolean sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public AuthorityDecision lostReason() {
        return lost.get();
    }

    @Override
    public Duration remainingBudget() {
        if (lost.get() != null) {
            return Duration.ZERO;
        }
        long remaining = deadlineNanos.get() - clock.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    /**
     * Stops monitoring.
     *
     * <p>Does not set a terminal reason. Closing means execution ended on its own terms, and recording an
     * authority loss here would turn every ordinary completion into a revocation.
     */
    @Override
    public void close() {
        stopping = true;
        thread.interrupt();
        try {
            // Bounded. A monitor thread that will not stop must not hold up the execution thread's cleanup —
            // the sandbox is what has to go, and it is not this thread that removes it.
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
