package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural properties of the launcher API, checked as source and shape rather than behaviour.
 *
 * <p>These exist because the strongest form of "a caller cannot request a privileged container" is that there
 * is nowhere to put the request. A behavioural test can only show that one particular dangerous call was
 * rejected; these show the call cannot be expressed.
 */
class LauncherBoundaryTests {

    @Test
    void nothingACallerSuppliesIsAContainerSetting() {
        // Three fields: which probe, which profile, and a correlation id. No image, entrypoint, command,
        // mount, capability, device, network mode, user, or privileged flag — so there is no argument that
        // could weaken the policy, and no validation that could be forgotten.
        assertThat(SandboxLaunchRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("probe", "profileVersion", "correlationId");
        assertThat(SandboxLaunchRequest.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .allSatisfy(type -> assertThat(type)
                        .doesNotContain("dockerjava")
                        .doesNotContain("HostConfig"));
    }

    @Test
    void noDockerTypeCrossesTheLauncherApi() {
        // The container runtime is an implementation detail of one class. If its types appear in the interface,
        // the profile, the request, or the outcome, then whatever calls the launcher can start shaping
        // containers — and the narrow input above stops being the only way in.
        Stream.of(SandboxLauncher.class, SandboxSecurityProfile.class, SandboxOutcome.class,
                        SandboxLaunchRequest.class, SyntheticProbe.class, SandboxFailure.class)
                .forEach(type -> {
                    for (Method method : type.getDeclaredMethods()) {
                        assertThat(method.getReturnType().getName())
                                .as("%s.%s return type", type.getSimpleName(), method.getName())
                                .doesNotContain("dockerjava");
                        for (Class<?> parameter : method.getParameterTypes()) {
                            assertThat(parameter.getName())
                                    .as("%s.%s parameter", type.getSimpleName(), method.getName())
                                    .doesNotContain("dockerjava");
                        }
                    }
                });
    }

    @Test
    void theLauncherNeverConstructsAShellCommandAndNeverGrantsPrivilege() throws Exception {
        String source = Files.readString(launcherSource());

        // A user string becoming shell syntax is the injection surface this design does not have. The probe's
        // arguments are fixed vectors on a server-side enum, and the launcher passes them as a list.
        assertThat(source).doesNotContain("sh -c").doesNotContain("\"-c\"");
        // Privilege is denied explicitly rather than by omission, so a future edit that removes the line is a
        // visible deletion rather than an invisible default.
        assertThat(source).contains(".withPrivileged(false)");
        assertThat(source).doesNotContain(".withPrivileged(true)");
        // Host networking would defeat the entire network control in one word.
        assertThat(source).doesNotContain("\"host\"");
        // No path on the host is ever named. Binds are empty, not filtered.
        assertThat(source).contains(".withBinds(List.of())");
        assertThat(source).doesNotContain("docker.sock");
    }

    @Test
    void theProbeImageIsPinnedByDigestRatherThanByTag() throws Exception {
        String dockerfile = Files.readString(
                SandboxTestSupport.probeContext().resolve("Dockerfile"));

        // A tag is a mutable pointer to executable code. Pinning the base by digest is what stops the content
        // of the one component that has to be trustworthy from changing under a stable name.
        assertThat(dockerfile).containsPattern("FROM \\S+@sha256:[a-f0-9]{64}");
        assertThat(dockerfile).contains("USER 65534:65534");
    }

    @Test
    void everyProbeArgumentVectorIsFixed() {
        // Nothing here is derived from an input, so there is no value a caller could influence.
        for (SyntheticProbe probe : SyntheticProbe.values()) {
            List<String> arguments = probe.arguments();
            assertThat(arguments).isNotEmpty();
            assertThat(arguments).allSatisfy(argument -> assertThat(argument).matches("[a-z0-9]+"));
        }
    }

    private static Path launcherSource() {
        Path fromModule = Path.of("src/main/java/com/kaas/runner/sandbox/DockerSandboxLauncher.java");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("services/runner/src/main/java/com/kaas/runner/sandbox/DockerSandboxLauncher.java");
    }
    @Test
    @DisplayName("a launcher refuses a request naming a profile it does not hold")
    void aForeignProfileVersionIsRefused() {
        // THE GUARD THAT STOPS A COMMAND CHOOSING A SECURITY POSTURE, and until now it had no test.
        //
        // A launcher holds exactly one profile. A request naming a different one is refused rather than run
        // under whatever profile happens to be configured -- otherwise a command authorized against one
        // boundary could be executed behind another, and the evidence would describe a policy that did not
        // produce it. Since the profile version is derived from the runtime, this is also what stops a
        // mediated-runtime command from being served by a baseline launcher.
        var launcher = SandboxTestSupport.launcher(SandboxTestSupport.profile(), "boundary-" + UUID.randomUUID());

        assertThatThrownBy(() -> launcher.run(new SandboxLaunchRequest(
                        SyntheticProbe.INSPECT, "kaas.sandbox.somebody-elses.v1", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown security profile version");
    }

    @Test
    @DisplayName("the mediated runtime's own profile version is refused by a baseline launcher")
    void aBaselineLauncherRefusesMediatedWork() {
        // The same guard in the shape that matters for the boundary: a worker configured for the baseline
        // cannot serve work authorized for the mediating runtime, because the two runtimes produce different
        // profile versions. Asserted here as well as in the execution loop, because this is the last line of
        // defence and it does not depend on the loop having checked anything.
        var baseline = SandboxTestSupport.launcher(SandboxTestSupport.profile(), "boundary-" + UUID.randomUUID());

        assertThatThrownBy(() -> baseline.run(new SandboxLaunchRequest(
                        SyntheticProbe.INSPECT,
                        ExecutionRuntimeType.GVISOR.profileVersion(),
                        UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
