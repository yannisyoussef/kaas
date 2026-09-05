package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.authority.AuthorityDecision;
import com.kaas.runner.authority.ExecutionAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Revocation under the mediating runtime, on a host that actually has it.
 *
 * <h2>Why the baseline test is not enough</h2>
 *
 * <p>Under {@code runc} a container is a process tree the daemon signals directly, and a container that
 * disappears has taken its workload with it. Under gVisor there is a second thing: the sentry, an ordinary
 * host process that <em>is</em> the kernel the workload runs against. A Docker container vanishing from
 * {@code docker ps} is not evidence that the sentry is gone, and a sentry that outlives its container is a
 * host process still holding the workload's memory and file descriptors.
 *
 * <p>So this asserts the runtime process is gone as well, which is a claim no baseline test can make and no
 * amount of local testing on macOS can check.
 */
@DisplayName("Strong runtime authority revocation")
class StrongRuntimeAuthorityRevocationTests {

    private static final String GENERATION = "strong-revocation-" + UUID.randomUUID();

    @BeforeAll
    static void requireTheRuntime() {
        // FAIL, never skip. A revocation suite that skipped itself where the runtime is absent would report
        // the same green as one that terminated a mediated sandbox.
        assertThat(SandboxTestSupport.docker().infoCmd().exec().getRuntimes())
                .as("this suite is the evidence that revocation works under the mediating runtime")
                .containsKey(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    /** An authority the test revokes by hand. */
    private static final class Revocable implements ExecutionAuthority {
        private final AtomicReference<AuthorityDecision> lost = new AtomicReference<>();

        void revoke() {
            lost.set(AuthorityDecision.RUN_NOT_OWNED);
        }

        @Override
        public AuthorityDecision lostReason() {
            return lost.get();
        }

        @Override
        public Duration remainingBudget() {
            return lost.get() == null ? Duration.ofMinutes(5) : Duration.ZERO;
        }
    }

    @Test
    @Timeout(600)
    @DisplayName("revoking authority terminates a mediated sandbox and leaves no runtime process behind")
    void revocationTerminatesTheMediatedSandbox() throws Exception {
        Revocable authority = new Revocable();
        SandboxSecurityProfile mediated = SandboxSecurityProfile.version1(
                SandboxTestSupport.probeImage(), ExecutionRuntimeType.GVISOR);
        var launcher = SandboxTestSupport.launcher(mediated, GENERATION);

        // An hour-long workload. Nothing here waits that long, so an outcome means it was stopped.
        var request = new SandboxLaunchRequest(SyntheticProbe.SLEEP, mediated.version(), UUID.randomUUID());
        var outcome = new AtomicReference<SandboxOutcome>();
        Thread execution = new Thread(() -> outcome.set(launcher.run(request, authority)));
        execution.start();

        waitUntil(() -> !managed().isEmpty());
        // And it is genuinely running under the mediating runtime, not merely labelled for it.
        String containerId = managed().get(0).getId();
        assertThat(SandboxTestSupport.docker()
                        .inspectContainerCmd(containerId)
                        .exec()
                        .getHostConfig()
                        .getRuntime())
                .as("the sandbox must actually be mediated, or this proves nothing about the strong runtime")
                .isEqualTo(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
        long sentriesWhileRunning = runscProcesses();
        assertThat(sentriesWhileRunning)
                .as("a mediated sandbox runs behind a sentry; if none exists the rest of this test is vacuous")
                .isPositive();

        Instant revokedAt = Instant.now();
        authority.revoke();
        execution.join(Duration.ofSeconds(120).toMillis());
        Duration took = Duration.between(revokedAt, Instant.now());

        assertThat(execution.isAlive()).isFalse();
        assertThat(outcome.get().failure()).contains(SandboxFailure.SANDBOX_AUTHORITY_LOST);
        assertThat(took).as("terminated in %s", took).isLessThan(Duration.ofSeconds(60));

        // THE CONTAINER IS GONE.
        assertThat(managed()).as("no managed container survives").isEmpty();
        // AND SO IS THE RUNTIME PROCESS. This is the half a baseline test cannot assert.
        waitUntil(() -> runscProcesses() == 0);
        assertThat(runscProcesses())
                .as("a sentry outliving its container is a host process still holding the workload")
                .isZero();
    }

    private static List<com.github.dockerjava.api.model.Container> managed() {
        return SandboxTestSupport.docker()
                .listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(SandboxLabels.GENERATION, GENERATION))
                .exec();
    }

    /**
     * How many {@code runsc} processes exist on this host.
     *
     * <p>Counted from the host rather than asked of the daemon. The daemon's view is exactly the view that is
     * insufficient here: it knows about containers, and the question is about the process that served one.
     */
    private static long runscProcesses() {
        try {
            Process ps = new ProcessBuilder("sh", "-c", "pgrep -c runsc || true")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(ps.getInputStream().readAllBytes()).trim();
            ps.waitFor();
            return output.isEmpty() ? 0 : Long.parseLong(output.lines().findFirst().orElse("0").trim());
        } catch (Exception unavailable) {
            throw new AssertionError("the host's process table must be readable for this suite", unavailable);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true");
            }
            Thread.sleep(100);
        }
    }
}
