package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The synthetic workload, run through the real hardened launcher.
 *
 * <p>These are not security tests — the sandbox's confinement is established elsewhere. They establish
 * something this slice needs and nothing else proves: that there is a workload the platform can actually
 * execute end to end, that it reports both terminal outcomes, and that it never claims to be an engine it is
 * not.
 */
class SyntheticWorkloadTests {

    @Test
    @DisplayName("the synthetic workload runs under the hardened profile and reports a passing outcome")
    void theSyntheticWorkloadPasses() {
        SandboxOutcome outcome = SandboxTestSupport.launcher(SandboxTestSupport.profile(), "workload-pass")
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.WORKLOAD_PASS, SandboxTestSupport.profile().version(), UUID.randomUUID()));

        assertThat(outcome.failure()).isEmpty();
        // Zero, and it must stay zero even when the workload's own assertions fail. That is checked below.
        assertThat(outcome.exitCode()).contains(0);
        assertThat(outcome.observations())
                .containsEntry("workload_identity", "KAAS_SYNTHETIC_V1")
                .containsEntry("workload_outcome", "PASSED")
                .containsEntry("workload_failed", "0")
                .containsEntry("scenario_arithmetic", "PASSED")
                .containsEntry("scenario_string", "PASSED")
                .containsEntry("scenario_ordering", "PASSED");
        // The applets its evidence depends on were actually present. Without this, a base-image change that
        // removed awk would make every scenario report a mismatch and the workload would "fail" for a reason
        // having nothing to do with what it is asserting.
        assertThat(outcome.observations()).containsEntry("probe_tooling", "present");
    }

    @Test
    @DisplayName("a failing workload is a failing TEST, not a failing execution")
    void aFailingWorkloadStillExitsZero() {
        SandboxOutcome outcome = SandboxTestSupport.launcher(SandboxTestSupport.profile(), "workload-fail")
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.WORKLOAD_FAIL, SandboxTestSupport.profile().version(), UUID.randomUUID()));

        assertThat(outcome.failure()).isEmpty();
        // THE POINT OF THIS TEST. The two outcomes are orthogonal: the infrastructure succeeded — it ran the
        // workload and collected its result — while the test failed. If the exit code tracked the test outcome,
        // every failing test would be indistinguishable from a broken sandbox, and the platform would report
        // infrastructure failures for ordinary red tests.
        assertThat(outcome.exitCode()).contains(0);
        assertThat(outcome.observations())
                .containsEntry("workload_identity", "KAAS_SYNTHETIC_V1")
                .containsEntry("workload_outcome", "FAILED")
                .containsEntry("scenario_expected_failure", "FAILED");
        // The genuine scenarios still passed. A failing run is partial, not total, and the result document has
        // to be able to say so.
        assertThat(outcome.observations())
                .containsEntry("scenario_arithmetic", "PASSED")
                .containsEntry("workload_passed", "3")
                .containsEntry("workload_failed", "1");
    }

    @Test
    @DisplayName("the workload is not Karate and never says it is")
    void theWorkloadNeverClaimsToBeAnEngineItIsNot() {
        SandboxOutcome outcome = SandboxTestSupport.launcher(SandboxTestSupport.profile(), "workload-identity")
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.WORKLOAD_PASS, SandboxTestSupport.profile().version(), UUID.randomUUID()));

        // Reporting KARATE while running three shell assertions would be the most misleading thing this slice
        // could do: every dashboard, every report, and every operator would believe a real engine had run.
        assertThat(outcome.observations().values())
                .noneSatisfy(value -> assertThat(value.toUpperCase(java.util.Locale.ROOT)).contains("KARATE"));
        assertThat(outcome.observations()).containsEntry("workload_identity", "KAAS_SYNTHETIC_V1");
    }
}
