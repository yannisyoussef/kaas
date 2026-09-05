package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What happens when a sandbox is authorized for a runtime this host does not have.
 *
 * <h2>Why this suite runs on the wrong host on purpose</h2>
 *
 * <p>Every other runtime test needs a host with the stronger runtime installed. This one needs the opposite,
 * and it is the more important of the two: a deployment that has not installed {@code runsc} is the ordinary
 * case, and the failure mode it must never have is quietly running the workload under {@code runc} instead.
 *
 * <p>That failure would be invisible. The container would start, the probe would report every mandatory
 * control passing, the result would be submitted, and the run would complete — with the workload confined by
 * exactly the boundary ADR-022 says is not sufficient. Nothing downstream would notice, because everything
 * downstream is looking at controls that genuinely did pass.
 *
 * <p>So there is no {@code catch} that retries, no {@code if (!available)} that substitutes, and no
 * configuration that permits one. The only outcome is a refusal.
 */
@DisplayName("Strong runtime unavailable")
class StrongRuntimeUnavailableTests {

    private final String generation = "strong-runtime-absent-" + UUID.randomUUID();

    @Test
    @Timeout(120)
    @DisplayName("a sandbox authorized for the mediating runtime refuses to start where it is not installed")
    void aStrongRuntimeSandboxRefusesOnAHostWithoutIt() {
        // This host runs a daemon with no runsc registered — which is what makes the test meaningful. If a
        // future development machine installs it, this assertion inverts and the test says so loudly rather
        // than passing for a new reason.
        boolean installed = SandboxTestSupport.docker().infoCmd().exec().getRuntimes().containsKey("runsc");
        org.junit.jupiter.api.Assumptions.assumeFalse(
                installed, "this suite asserts behaviour on a host WITHOUT the mediating runtime");

        SandboxSecurityProfile strong = SandboxSecurityProfile.version1(
                SandboxTestSupport.probeImage(), ExecutionRuntimeType.GVISOR);

        SandboxOutcome outcome = SandboxTestSupport.launcher(strong, generation)
                .run(new SandboxLaunchRequest(SyntheticProbe.INSPECT, strong.version(), UUID.randomUUID()));

        // MEASURED, not assumed: a daemon with no runsc registered refuses the create outright with
        // "unknown or invalid runtime name". The container is never created, so an unavailable runtime cannot
        // become the default one by omission — the fail-closed property is the daemon's, and this asserts it
        // rather than trusting it.
        assertThat(outcome.failure())
                .as("no fallback, no downgrade, no 'best effort' — a refusal")
                .isPresent();
        // AND IT SAYS WHY. A generic create failure would send an operator to look at the image or the daemon
        // when the answer is that this host is missing a runtime, which is a statement about the boundary.
        assertThat(outcome.failure().orElseThrow())
                .isEqualTo(SandboxFailure.SANDBOX_RUNTIME_UNAVAILABLE);
        assertThat(outcome.observations())
                .as("nothing was observed, because nothing ran")
                .isEmpty();

        // AND NOTHING RAN. A refusal that had already started a container under the wrong runtime would be a
        // refusal after the fact, which is not the same thing at all.
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(SandboxLabels.GENERATION, generation))
                        .exec())
                .as("a sandbox that could not be confined as authorized must not exist at all")
                .isEmpty();
    }

    @Test
    @DisplayName("the two runtimes are different security profiles, so evidence cannot transfer between them")
    void theRuntimesAreDifferentProfiles() {
        String baseline = SandboxSecurityProfile.version1(PINNED, ExecutionRuntimeType.DOCKER).version();
        String mediating = SandboxSecurityProfile.version1(PINNED, ExecutionRuntimeType.GVISOR).version();

        // An attestation binds a profile version. If both runtimes shared one, an assessment gathered under
        // the weaker boundary would satisfy an execution authorized for the stronger one — and several
        // mandatory controls read identically under both while meaning different things, so nothing further
        // down would catch it.
        assertThat(baseline).isNotEqualTo(mediating);
        assertThat(ExecutionRuntimeType.DOCKER.daemonRuntimeName())
                .isNotEqualTo(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    @Test
    @DisplayName("only the mediating runtime claims an in-sandbox identity")
    void onlyTheMediatingRuntimeClaimsAnIdentity() {
        // The baseline reports empty rather than something like "not gVisor". Whatever kernel the host runs is
        // not a property this platform gets to assert, and a marker invented for it would be a control that
        // passes for reasons nobody chose.
        assertThat(ExecutionRuntimeType.DOCKER.inSandboxKernelMarker()).isEmpty();
        assertThat(ExecutionRuntimeType.GVISOR.inSandboxKernelMarker()).isPresent();
        assertThat(ExecutionRuntimeType.DOCKER.mediatesHostKernelSyscalls()).isFalse();
        assertThat(ExecutionRuntimeType.GVISOR.mediatesHostKernelSyscalls()).isTrue();
    }

    @Test
    @DisplayName("nothing can name a runtime that is not one of the two the platform owns")
    void theRuntimeSetIsClosed() {
        // A closed enum rather than a string, because a runtime name is the name of a program the daemon will
        // execute. There is deliberately no valueOf-from-request path anywhere; this asserts the set itself
        // has not quietly grown a third member nobody evaluated.
        assertThat(ExecutionRuntimeType.values())
                .containsExactly(ExecutionRuntimeType.DOCKER, ExecutionRuntimeType.GVISOR);
        assertThat(List.of(ExecutionRuntimeType.values()))
                .extracting(ExecutionRuntimeType::daemonRuntimeName)
                .containsExactly("runc", "runsc");
    }

    private static final String PINNED = "sha256:" + "a".repeat(64);
    @Test
    void theMediatingRuntimeGetsALongerDeadlineAndTheBaselineDoesNot() {
        // The deadline is a profile value, so a scaling that quietly applied to both would go unnoticed --
        // and the baseline's 30 seconds is what several of its own controls are measured against.
        var baseline = SandboxSecurityProfile.version1(PINNED, ExecutionRuntimeType.DOCKER);
        var mediated = SandboxSecurityProfile.version1(PINNED, ExecutionRuntimeType.GVISOR);

        assertThat(baseline.wallClockTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(mediated.wallClockTimeout())
                .as("the mediating runtime interposes on every syscall; a deadline calibrated for the "
                        + "baseline killed the memory probe before it could report the ceiling that had "
                        + "already bounded it")
                .isGreaterThan(baseline.wallClockTimeout());
    }

}
