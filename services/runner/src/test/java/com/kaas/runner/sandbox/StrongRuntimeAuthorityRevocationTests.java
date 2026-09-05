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

    /** The runtime's own executables: the launcher, the sentry and the gofer. */
    private static final java.util.regex.Pattern RUNTIME_EXECUTABLE =
            java.util.regex.Pattern.compile(".*(^|/)runsc(-sandbox|-gofer)?$");

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

        // WAIT FOR IT TO BE RUNNING, not merely to exist.
        //
        // `managed()` lists created-but-not-started containers too, so waiting on that returned as soon as the
        // container was created -- before the runtime had spawned anything. The sentry count then read zero
        // and the process table printed beside it, evaluated microseconds later, showed the runtime starting
        // up. The test was racing the thing it was measuring.
        waitUntil(() -> managed().stream()
                .anyMatch(container -> "running".equalsIgnoreCase(container.getState())));
        // And a runtime process actually exists. Waited for rather than asserted cold, for the same reason:
        // the sentry appears a moment after the container does. A runtime that never spawns one still fails
        // here, as a wait that times out.
        waitUntil(() -> runscProcesses() > 0);

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
                .as("a mediated sandbox runs behind a sentry; if none exists the rest of this test is "
                        + "vacuous. Process table:%n%s", runtimeProcesses())
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
                .as("a sentry outliving its container is a host process still holding the workload. "
                        + "Process table:%n%s", runtimeProcesses())
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
     *
     * <h2>Matched on the command line, not the process name</h2>
     *
     * <p>{@code pgrep -c runsc} finds nothing at all while a mediated sandbox is running — measured in CI.
     * gVisor re-executes itself for the sentry, so the process's {@code comm} is not {@code runsc} and a
     * name match never sees it. A leak check written that way reports zero survivors whether or not any
     * exist, which is the most dangerous shape a safety check can have: it passes for the same reason
     * whether the system is healthy or broken.
     *
     * <p>{@code -f} matches the full command line, which does carry the runtime's path.
     */
    private static long runscProcesses() {
        return runtimeProcessLines().size();
    }

    /**
     * Every process whose command line names the mediating runtime.
     *
     * <p>Read straight from {@code ps} and filtered here, so the count and the evidence come from one source.
     * They did not before: the count used {@code pgrep} and the diagnostic used {@code ps}, and in CI they
     * disagreed outright — {@code pgrep} reported none while {@code ps} listed a {@code runsc-sandbox} and a
     * {@code runsc-gofer} for a running container. A safety check that disagrees with the evidence printed
     * beside it is worse than no check, because it is read as agreement.
     *
     * <p>Filtering in Java rather than piping through {@code grep} also removes the classic self-match, where
     * the search command's own command line contains the pattern and the count is never zero.
     */
    private static java.util.List<String> runtimeProcessLines() {
        try {
            Process ps = new ProcessBuilder("ps", "-eo", "args=").redirectErrorStream(true).start();
            String output = new String(ps.getInputStream().readAllBytes());
            ps.waitFor();
            return output.lines()
                    .map(String::trim)
                    // The FIRST token, which is the executable. Matching anywhere in the argument list makes
                    // the count depend on what else happens to mention the runtime -- this test class's own
                    // name, a gradle command line, the check itself -- rather than on what is running.
                    .filter(line -> RUNTIME_EXECUTABLE
                            .matcher(line.split("\\s+", 2)[0])
                            .matches())
                    .toList();
        } catch (Exception unavailable) {
            throw new AssertionError("the host's process table must be readable for this suite", unavailable);
        }
    }

    /** What the process table actually holds, for when a count disagrees with expectation. */
    private static String runtimeProcesses() {
        java.util.List<String> lines = runtimeProcessLines();
        return lines.isEmpty() ? "(no runsc processes)" : String.join(System.lineSeparator(), lines);
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
