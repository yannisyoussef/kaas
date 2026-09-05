package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.gate.HostileExecutionSecurityGate;
import com.kaas.runner.gate.SecurityCheck;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The security gate, run for real under the mediating runtime.
 *
 * <h2>Why this is a separate task and not part of `check`</h2>
 *
 * <p>These need a daemon with {@code runsc} registered. Docker Desktop provides no supported way to install a
 * runtime into its embedded VM, so on the primary development platform this cannot run at all — and wiring it
 * into {@code check} would mean the ordinary local build fails on every Mac. The evidence lives in its own
 * mandatory CI job instead, where it cannot be skipped.
 *
 * <p>The important consequence, stated so nobody has to infer it: <strong>a green local build proves nothing
 * about the stronger runtime.</strong> Only the gate does.
 *
 * <h2>What these prove that configuration cannot</h2>
 *
 * <p>The same probe, the same mandatory control contract, a different runtime. That comparison is the point —
 * a separate probe with separate control definitions would make the two boundaries incomparable, and the
 * question this slice exists to answer is precisely how they differ.
 */
@DisplayName("Strong runtime boundary")
class StrongRuntimeBoundaryTests {

    private static final String GENERATION = "strong-runtime-" + UUID.randomUUID();

    @BeforeAll
    static void requireTheRuntime() {
        // FAIL, never skip. A gate that quietly passes when the runtime is absent is indistinguishable from
        // one that proved a workload was confined by it — which is the entire failure this suite exists to
        // prevent, and the reason its CI job has no `if:` and no `continue-on-error`.
        var registered = SandboxTestSupport.docker().infoCmd().exec().getRuntimes();
        assertThat(registered)
                .as("this suite is the evidence that the mediating runtime works; without it there is no "
                        + "evidence, and an absent runtime must fail rather than skip")
                .containsKey(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    @Test
    @Timeout(900)
    @DisplayName("every mandatory control still holds under the mediating runtime")
    void everyMandatoryControlHoldsUnderTheMediatingRuntime() {
        var assessment = new HostileExecutionSecurityGate(launcher(), "docker").assess();

        // The SAME contract the baseline gate is held to. Not a relaxed set, and not a different set: if a
        // control cannot be demonstrated under this runtime that is a finding, not a reason to drop it.
        assertThat(assessment.blockers())
                .as("blocking controls under the mediating runtime: %s",
                        assessment.blockers().stream().map(SecurityCheck::evidence).toList())
                .isEmpty();
        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.profileVersion())
                .as("evidence is bound to the runtime's own profile version, so it cannot vouch for the other")
                .isEqualTo(ExecutionRuntimeType.GVISOR.profileVersion());
    }

    @Test
    @Timeout(600)
    @DisplayName("the sandbox reports the mediating runtime's kernel, not the host's")
    void theSandboxReportsTheMediatingRuntimesOwnKernel() {
        var assessment = new HostileExecutionSecurityGate(launcher(), "docker").assess();

        var mediation = assessment.checks().stream()
                .filter(check -> HostileExecutionSecurityGate.RUNTIME_MEDIATION_CONTROL.equals(check.control()))
                .findFirst()
                .orElseThrow();

        // THE ANTI-MASQUERADE PROPERTY. The daemon reporting `runsc` says what was requested; this says what
        // is actually underneath the workload. A runc container cannot produce this marker unless the host
        // kernel genuinely is gVisor's fixed synthetic version, and a container cannot choose what the kernel
        // says about itself.
        assertThat(mediation.verdict()).isEqualTo(SecurityCheck.Verdict.PASS);
        assertThat(mediation.enforcement()).isEqualTo(SecurityCheck.Enforcement.MANDATORY);

        // And it is genuinely different from what this host runs — otherwise the marker would be satisfied by
        // coincidence rather than by mediation.
        SandboxOutcome baseline = SandboxTestSupport
                .launcher(SandboxTestSupport.profile(), GENERATION)
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.INSPECT,
                        SandboxTestSupport.profile().version(),
                        UUID.randomUUID()));
        assertThat(baseline.observation("runtime_kernel_release"))
                .as("the baseline runtime must report the host kernel, or this comparison proves nothing")
                .isPresent();
        assertThat(ExecutionRuntimeType.GVISOR.servesKernelRelease(
                        baseline.observation("runtime_kernel_release").orElseThrow()))
                .as("the baseline runtime reported %s, which the mediating runtime claims to serve — the "
                        + "marker is then satisfied by coincidence and proves nothing",
                        baseline.observation("runtime_kernel_release").orElseThrow())
                .isFalse();
    }

    @Test
    @Timeout(600)
    @DisplayName("DENY_ALL under the mediating runtime reaches nothing and resolves nothing")
    void denyAllHoldsUnderTheMediatingRuntime() {
        SandboxOutcome outcome = launcher().run(new SandboxLaunchRequest(
                SyntheticProbe.NETWORK, ExecutionRuntimeType.GVISOR.profileVersion(), UUID.randomUUID()));

        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        // Re-proven rather than assumed to carry over. gVisor has its own network stack, so "no network" is
        // enforced by a different mechanism here than under runc and has to be demonstrated again.
        assertThat(outcome.observations())
                .containsEntry("net_dns", "unresolvable")
                .containsEntry("net_public", "unreachable")
                .containsEntry("net_private", "unreachable")
                .containsEntry("net_metadata", "unreachable")
                .containsEntry("net_docker_host", "unreachable");
    }

    @Test
    @Timeout(600)
    @DisplayName("a sandbox under the mediating runtime joins a per-execution internal network")
    void theMediatingRuntimeJoinsAnInternalNetwork() {
        // THE OPEN QUESTION FROM THE EVALUATION. gVisor has its own netstack, and whether a sandbox under it
        // can join a Docker bridge network could not be answered locally — the nested attempt failed for a
        // reason belonging to nested Docker rather than to gVisor. Until this passes on a real host, no claim
        // is made anywhere about ALLOWLIST under this runtime.
        try (ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), GENERATION, UUID.randomUUID())) {
            SandboxSecurityProfile networked = SandboxSecurityProfile.version1OnNetwork(
                    SandboxTestSupport.probeImage(), network.name(), Map.of(),
                    ExecutionRuntimeType.GVISOR);

            SandboxOutcome outcome = SandboxTestSupport.launcher(networked, GENERATION)
                    .run(new SandboxLaunchRequest(
                            SyntheticProbe.NETWORK, networked.version(), UUID.randomUUID()));

            assertThat(outcome.failure())
                    .as("a sandbox that cannot join an internal network cannot use the egress proxy: %s",
                            outcome)
                    .isEmpty();
            // On the network, and still unable to reach anything through it — which is the whole shape of the
            // allowlist topology before a proxy is added.
            assertThat(outcome.observations()).containsEntry("net_interfaces_up", "1");
            assertThat(outcome.observations())
                    .containsEntry("net_public", "unreachable")
                    .containsEntry("net_metadata", "unreachable");
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("the two runtimes produce different evidence for the same probe")
    void theTwoRuntimesProduceDifferentEvidence() {
        // If a baseline assessment and a mediated one were interchangeable, the whole runtime binding would be
        // decoration. They differ in the profile version they are bound to and in the kernel observed, and
        // both differences are inside what an attestation signs.
        var mediated = new HostileExecutionSecurityGate(launcher(), "docker").assess();
        var baseline = new HostileExecutionSecurityGate(
                        SandboxTestSupport.launcher(SandboxTestSupport.profile(), GENERATION), "docker")
                .assess();

        assertThat(mediated.profileVersion()).isNotEqualTo(baseline.profileVersion());

        List<String> mediatedControls = mediated.checks().stream().map(SecurityCheck::control).sorted().toList();
        List<String> baselineControls = baseline.checks().stream().map(SecurityCheck::control).sorted().toList();
        assertThat(mediatedControls)
                .as("the same contract, so the same control names — only the verdicts and the boundary differ")
                .isEqualTo(baselineControls);
    }

    private static DockerSandboxLauncher launcher() {
        return SandboxTestSupport.launcher(
                SandboxSecurityProfile.version1(
                        SandboxTestSupport.probeImage(), ExecutionRuntimeType.GVISOR),
                GENERATION);
    }
}
