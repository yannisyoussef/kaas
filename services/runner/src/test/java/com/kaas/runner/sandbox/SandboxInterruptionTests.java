package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.authority.AuthorityDecision;
import com.kaas.runner.authority.ExecutionAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A running sandbox is stopped when its execution authority ends.
 *
 * <h2>What this proves that nothing else does</h2>
 *
 * <p>Database fencing already stops a stale worker committing anything, and there are tests for it. Those
 * tests say nothing about whether the workload is still running: a fenced worker whose writes are all rejected
 * can still be burning CPU inside a sandbox, and for a workload this repository wrote that is merely wasteful.
 * For hostile code it is the whole problem.
 *
 * <p>So these assert termination, not rejection. The probe used sleeps far longer than any of them wait, so a
 * sandbox that is still alive when the assertion runs will fail it rather than finish quietly in the
 * background.
 */
@DisplayName("Sandbox interruption")
class SandboxInterruptionTests {

    private final String generation = "interruption-" + UUID.randomUUID();

    @AfterEach
    void nothingSurvives() {
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(SandboxLabels.GENERATION, generation))
                        .exec())
                .as("a terminated sandbox is removed, not merely stopped")
                .isEmpty();
    }

    /** An authority the test revokes by hand. */
    private static final class Revocable implements ExecutionAuthority {
        private final AtomicReference<AuthorityDecision> lost = new AtomicReference<>();

        void revoke(AuthorityDecision reason) {
            lost.set(reason);
        }

        @Override
        public AuthorityDecision lostReason() {
            return lost.get();
        }

        @Override
        public Duration remainingBudget() {
            return lost.get() == null ? Duration.ofMinutes(1) : Duration.ZERO;
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("a long-running sandbox stops when authority is revoked mid-execution")
    void revocationStopsARunningSandbox() throws Exception {
        Revocable authority = new Revocable();
        var launcher = SandboxTestSupport.launcher(SandboxTestSupport.profile(), generation);

        // The probe sleeps for an hour. Nothing about this test waits that long, so if the outcome arrives it
        // is because the sandbox was stopped rather than because the workload chose to end.
        var request = new SandboxLaunchRequest(
                SyntheticProbe.SLEEP, launcher.profile().version(), UUID.randomUUID());

        var outcome = new AtomicReference<SandboxOutcome>();
        Thread execution = new Thread(() -> outcome.set(launcher.run(request, authority)));
        execution.start();

        // Let it genuinely start. Revoking before the container exists would exercise the provisioning path
        // instead, which is a different property with its own test.
        waitUntil(() -> !SandboxTestSupport.docker()
                .listContainersCmd()
                .withLabelFilter(Map.of(SandboxLabels.GENERATION, generation))
                .exec()
                .isEmpty());

        Instant revokedAt = Instant.now();
        authority.revoke(AuthorityDecision.RUN_NOT_OWNED);
        execution.join(Duration.ofSeconds(90).toMillis());
        Duration took = Duration.between(revokedAt, Instant.now());

        assertThat(execution.isAlive()).as("execution must return, not hang").isFalse();
        assertThat(outcome.get()).isNotNull();
        // BOUNDED, and the bound is the platform's rather than the workload's: the sleep would have run for an
        // hour, and the graceful window before the forced kill is five seconds.
        assertThat(took)
                .as("termination took %s", took)
                .isLessThan(Duration.ofSeconds(60));
        // And it is not reported as a timeout or a broken host. What ended was the authority.
        assertThat(outcome.get().failure()).contains(SandboxFailure.SANDBOX_AUTHORITY_LOST);
    }

    @Test
    @Timeout(120)
    @DisplayName("authority lost before the workload starts leaves nothing running")
    void revocationDuringProvisioningStartsNothing() {
        Revocable authority = new Revocable();
        authority.revoke(AuthorityDecision.STALE_ASSIGNMENT);
        var launcher = SandboxTestSupport.launcher(SandboxTestSupport.profile(), generation);

        var outcome = launcher.run(
                new SandboxLaunchRequest(
                        SyntheticProbe.SLEEP, launcher.profile().version(), UUID.randomUUID()),
                authority);

        // The container is created before the authority is re-read -- creation is what gives the runtime
        // check something to inspect -- so the property is that nothing was ever STARTED, and that the
        // creation is undone. The @AfterEach covers the second half.
        assertThat(outcome.failure()).contains(SandboxFailure.SANDBOX_AUTHORITY_LOST);
        assertThat(outcome.observations()).as("nothing ran, so nothing was observed").isEmpty();
    }

    @Test
    @Timeout(180)
    @DisplayName("an authority that is never lost does not disturb an ordinary execution")
    void aRetainedAuthorityChangesNothing() {
        // The other axis. Every assertion above is satisfied by a launcher that terminates everything always,
        // which would be a boundary that works by never running anything.
        var launcher = SandboxTestSupport.launcher(SandboxTestSupport.profile(), generation);
        var outcome = launcher.run(new SandboxLaunchRequest(
                SyntheticProbe.INSPECT, launcher.profile().version(), UUID.randomUUID()));

        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.observations()).isNotEmpty();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true");
            }
            Thread.sleep(50);
        }
    }
}
