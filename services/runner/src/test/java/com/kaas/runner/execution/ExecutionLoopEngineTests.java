package com.kaas.runner.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxFailure;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the loop reads an engine's result, and how it reads the synthetic workload's — driven side by side.
 *
 * <h2>Why this suite exists</h2>
 *
 * <p>The engine branch was written, compiled, covered by component tests of {@link EngineOutcome}, and
 * reachable from nothing. Every one of those component tests passed while no configured loop had the engine
 * name in it, so a branch that ignored its input entirely would have been green. A green test of a mechanism
 * nobody calls is a test of the mechanism's syntax.
 *
 * <p>So these construct the loop the way production does — with an engine name — and ask it the two questions
 * that decide whether a run is a pass. Both engines are driven through the same table, because the point is
 * that they read DIFFERENT things out of the same sandbox output: the synthetic workload announces its own
 * identity and outcome, and the engine does not get to.
 */
@DisplayName("Execution loop engine results")
class ExecutionLoopEngineTests {

    @Test
    @DisplayName("a Karate loop reads its verdict from the protocol, and a synthetic loop does not see one")
    void theEngineVerdictIsWhatTheKarateLoopReads() {
        SandboxOutcome passed = outcome(Map.of(EngineOutcome.PROTOCOL, "PASSED"), Set.of(), 0);

        assertThat(karateLoop().infrastructureFailureDetail(passed)).isNull();
        assertThat(karateLoop().testPassed(passed)).isTrue();

        // The same bytes, read by the synthetic loop, are not a result at all: that loop wants an identity
        // the engine never claims. Without this half, a loop that ignored its engine name would pass above.
        assertThat(syntheticLoop().infrastructureFailureDetail(passed))
                .as("the synthetic path must not accept an engine's protocol line as its own result")
                .isNotNull();
    }

    @Test
    @DisplayName("a failing suite is a completed run, not an infrastructure failure")
    void aFailedSuiteIsStillACompletedRun() {
        SandboxOutcome failed = outcome(Map.of(EngineOutcome.PROTOCOL, "FAILED"), Set.of(), 0);

        assertThat(karateLoop().infrastructureFailureDetail(failed)).isNull();
        assertThat(karateLoop().testPassed(failed)).isFalse();
    }

    @Test
    @DisplayName("a zero exit with no verdict is an infrastructure failure, never a pass")
    void anAbsentVerdictIsNotAPass() {
        // The System.exit(0) shape, at the loop rather than at the sandbox. Nothing else in this file matters
        // more: a container that exited cleanly having run no assertion must not become a passing run.
        SandboxOutcome silent = outcome(Map.of(), Set.of(), 0);

        assertThat(karateLoop().infrastructureFailureDetail(silent))
                .isNotNull()
                .contains("ABSENT");
    }

    @Test
    @DisplayName("a duplicated verdict is an infrastructure failure, whichever verdict came last")
    void aDuplicatedVerdictIsRefused() {
        // The map holds the LAST value seen, so a forger who prints after the adapter leaves PASSED here. The
        // loop must refuse on the duplicate rather than on the value.
        SandboxOutcome forged =
                outcome(Map.of(EngineOutcome.PROTOCOL, "PASSED"), Set.of(EngineOutcome.PROTOCOL), 0);

        assertThat(karateLoop().infrastructureFailureDetail(forged))
                .isNotNull()
                .contains("MALFORMED");
    }

    @Test
    @DisplayName("an engine error is an infrastructure failure, not a failing test")
    void anEngineErrorIsNotATestResult() {
        SandboxOutcome broken = outcome(
                Map.of(EngineOutcome.PROTOCOL, "ENGINE_ERROR", "engine_error", "NO_AUTHORIZED_FEATURES"),
                Set.of(),
                0);

        assertThat(karateLoop().infrastructureFailureDetail(broken))
                .isNotNull()
                .contains("ENGINE_ERROR");
    }

    @Test
    @DisplayName("a sandbox that failed is refused before its output is read at all")
    void aFailedSandboxIsRefusedFirst() {
        // Ordering: a killed sandbox that still managed to print PASSED must not be believed. The failure is
        // checked before the protocol, so the verdict never gets a chance to override it.
        SandboxOutcome killed = new SandboxOutcome(
                Optional.empty(),
                Map.of(EngineOutcome.PROTOCOL, "PASSED"),
                Set.of(),
                false,
                0,
                Duration.ofSeconds(1),
                false,
                Optional.of(SandboxFailure.SANDBOX_TIMEOUT));

        assertThat(karateLoop().infrastructureFailureDetail(killed)).isNotNull();
    }

    // ------------------------------------------------------------------ harness

    private static ExecutionLoop karateLoop() {
        return loop(CommandValidator.KARATE_ENGINE);
    }

    private static ExecutionLoop syntheticLoop() {
        return loop(CommandValidator.SYNTHETIC_ENGINE);
    }

    private static ExecutionLoop loop(String engine) {
        JsonMapper mapper = JsonMapper.builder().build();
        return new ExecutionLoop(
                (ControlPlaneClient) null,
                new CommandValidator(mapper),
                launcher(),
                mapper,
                Clock.systemUTC(),
                SyntheticProbe.KARATE_ENGINE,
                null,
                false,
                engine);
    }

    private static SandboxOutcome outcome(Map<String, String> observations, Set<String> duplicated, int exit) {
        return new SandboxOutcome(
                Optional.of(exit),
                new LinkedHashMap<>(observations),
                duplicated,
                false,
                0,
                Duration.ofSeconds(1),
                false,
                Optional.empty());
    }

    /** A launcher nothing in this suite reaches; the loop only needs its profile to exist. */
    private static SandboxLauncher launcher() {
        return new SandboxLauncher() {
            @Override
            public SandboxSecurityProfile profile() {
                return SandboxSecurityProfile.version1(
                        "busybox@sha256:" + "0".repeat(64), ExecutionRuntimeType.GVISOR);
            }

            @Override
            public SandboxOutcome run(SandboxLaunchRequest request) {
                throw new AssertionError("no case in this suite launches a sandbox");
            }

            @Override
            public SandboxOutcome run(
                    SandboxLaunchRequest request, com.kaas.runner.authority.ExecutionAuthority authority) {
                throw new AssertionError("no case in this suite launches a sandbox");
            }

            @Override
            public SandboxLauncher withSource(SandboxSecurityProfile.SourceDelivery delivery) {
                throw new AssertionError("no case in this suite launches a sandbox");
            }
        };
    }
}
