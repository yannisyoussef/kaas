package com.kaas.runner.authority;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.authority.ExecutionAuthorityMonitor.Renewal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * How long a workload may keep running after its authority stops being renewed.
 *
 * <p>Elapsed time is driven rather than waited for. A budget test that slept through its own budget would take
 * as long as the budget to run and would be timing-flaky besides; here the monotonic clock is a value the test
 * sets, so "thirty seconds passed" is an assignment.
 */
@DisplayName("Execution authority monitor")
class ExecutionAuthorityMonitorTest {

    /** A monotonic clock the test moves by hand. */
    private static final class FakeClock implements MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }
    }

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MARGIN = Duration.ofSeconds(5);
    private static final Duration INTERVAL = Duration.ofMillis(20);

    @Test
    @Timeout(30)
    @DisplayName("a renewed lease refreshes the budget and execution continues")
    void renewalsKeepExecutionAlive() throws Exception {
        FakeClock clock = new FakeClock();
        var renewals = new AtomicLong();
        try (var monitor = start(clock, () -> {
            renewals.incrementAndGet();
            return new Renewal(AuthorityDecision.RENEWED, LEASE);
        })) {
            waitUntil(() -> renewals.get() >= 3);

            assertThat(monitor.lost()).as("a healthy worker is never stopped by its own monitor").isFalse();
            // The budget is the lease MINUS the safety margin: the worker stops before authority can have
            // ended, not after.
            assertThat(monitor.remainingBudget()).isEqualTo(LEASE.minus(MARGIN));
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("one transient failure does not stop a healthy run")
    void oneTransientFailureIsSurvivable() throws Exception {
        FakeClock clock = new FakeClock();
        var attempts = new AtomicLong();
        try (var monitor = start(clock, () -> attempts.incrementAndGet() == 2
                ? Renewal.unavailable()
                : new Renewal(AuthorityDecision.RENEWED, LEASE))) {
            waitUntil(() -> attempts.get() >= 4);

            // THE PROPERTY THE WHOLE DESIGN TURNS ON. Killing a run on one missed renewal would convert
            // ordinary network latency into lost work, which is why the previous implementation swallowed
            // failures entirely -- and swallowing them is what let a fenced worker keep running.
            assertThat(monitor.lost()).isFalse();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("repeated transient failures consume the budget and then stop execution")
    void aProlongedOutageExhaustsTheBudget() throws Exception {
        FakeClock clock = new FakeClock();
        try (var monitor = start(clock, Renewal::unavailable)) {
            waitUntil(() -> monitor.remainingBudget().compareTo(LEASE.minus(MARGIN)) <= 0);
            assertThat(monitor.lost()).as("still inside the budget").isFalse();

            // The control plane never comes back. Time does.
            clock.advance(LEASE);
            waitUntil(monitor::lost);

            // Fail-closed, and named as an expired lease rather than as a network problem: what ended is the
            // authority, and the network is only why it could not be renewed.
            assertThat(monitor.lostReason()).isEqualTo(AuthorityDecision.LEASE_EXPIRED);
            assertThat(monitor.remainingBudget()).isEqualTo(Duration.ZERO);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("an explicit refusal stops execution immediately, without waiting for the budget")
    void definitiveLossDoesNotWaitForTheBudget() throws Exception {
        FakeClock clock = new FakeClock();
        try (var monitor = start(clock, () -> new Renewal(AuthorityDecision.RUN_NOT_OWNED, null))) {
            waitUntil(monitor::lost);

            assertThat(monitor.lostReason()).isEqualTo(AuthorityDecision.RUN_NOT_OWNED);
            // The clock never moved. Waiting out the lease after being told the assignment is gone would let a
            // cancelled workload run for another half-minute for no reason at all.
            assertThat(clock.nanoTime()).isZero();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a clock that did not advance is transient, not a loss of authority")
    void aClockThatDidNotAdvanceIsSurvivable() throws Exception {
        FakeClock clock = new FakeClock();
        var attempts = new AtomicLong();
        try (var monitor = start(clock, () -> {
            attempts.incrementAndGet();
            return new Renewal(AuthorityDecision.CLOCK_NOT_ADVANCED, LEASE);
        })) {
            waitUntil(() -> attempts.get() >= 3);

            // The refusal that is not about ownership. Its causes are a backwards NTP step or a standby
            // failover, the lease is untouched, and treating it as fencing would end healthy runs.
            assertThat(monitor.lost()).isFalse();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a refusal this build cannot interpret stops execution")
    void anUnrecognisedDecisionFailsClosed() throws Exception {
        FakeClock clock = new FakeClock();
        try (var monitor = start(clock, () -> new Renewal(AuthorityDecision.fromReason("SOMETHING_NEW"), null))) {
            waitUntil(monitor::lost);
            assertThat(monitor.lostReason()).isEqualTo(AuthorityDecision.UNRECOGNIZED);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("at most one renewal is ever in flight")
    void renewalsNeverOverlap() throws Exception {
        FakeClock clock = new FakeClock();
        var inFlight = new AtomicLong();
        var overlaps = new AtomicLong();
        var completed = new AtomicLong();
        try (var monitor = start(clock, () -> {
            if (inFlight.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            try {
                // A slow control plane. At a fixed RATE this is what produces a pile of concurrent renewals
                // from a worker whose only problem is that the server is busy.
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            completed.incrementAndGet();
            return new Renewal(AuthorityDecision.RENEWED, LEASE);
        })) {
            waitUntil(() -> completed.get() >= 3);
            assertThat(overlaps.get()).isZero();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("closing stops the monitor without inventing an authority loss")
    void closingIsNotRevocation() throws Exception {
        FakeClock clock = new FakeClock();
        List<String> calls = new CopyOnWriteArrayList<>();
        var monitor = start(clock, () -> {
            calls.add("renew");
            return new Renewal(AuthorityDecision.RENEWED, LEASE);
        });
        waitUntil(() -> !calls.isEmpty());
        monitor.close();

        // Ordinary completion is not revocation. If closing set a reason, every successful execution would
        // finish by reporting that its authority had been taken away.
        assertThat(monitor.lostReason()).isNull();

        int after = calls.size();
        Thread.sleep(100);
        assertThat(calls.size()).as("no renewal continues after ownership ended").isEqualTo(after);
    }

    @Test
    @DisplayName("a lease shorter than the safety margin grants no budget at all")
    void aLeaseInsideTheMarginGrantsNothing() throws Exception {
        FakeClock clock = new FakeClock();
        var reason = new AtomicReference<AuthorityDecision>();
        try (var monitor = start(clock, () -> new Renewal(AuthorityDecision.RENEWED, Duration.ofSeconds(1)))) {
            waitUntil(monitor::lost);
            reason.set(monitor.lostReason());
        }
        // Not an error and not an extension. A lease with less time left than the margin cannot safely carry
        // any execution, so the honest budget is zero rather than a negative one that reads as enormous.
        assertThat(reason.get()).isEqualTo(AuthorityDecision.LEASE_EXPIRED);
    }

    private ExecutionAuthorityMonitor start(
            MonotonicClock clock, ExecutionAuthorityMonitor.RenewalSource source) {
        return ExecutionAuthorityMonitor.start(source, clock, INTERVAL, MARGIN, LEASE, "test-authority-monitor");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true within 10s");
            }
            Thread.sleep(2);
        }
    }
}
