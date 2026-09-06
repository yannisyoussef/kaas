package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Properties of the source boundary that hold because of how the code is shaped, not because of what it does.
 *
 * <p>Behavioural tests can show that one dangerous call was refused. These show that it cannot be expressed —
 * which is the stronger claim, and the only one that survives somebody adding a call site nobody thought of.
 *
 * <p>Read from source and from reflection rather than from a dependency rule, because the claims are about
 * the shape of specific types and the contents of two specific files. A package rule would say the launcher
 * does not import a test class; it would say nothing about a mount option assembled from a string.
 */
@DisplayName("Source boundary structure")
class SourceBoundaryStructureTests {

    @Test
    @DisplayName("nothing a tenant supplies can choose the filesystem, its flags, or the program that builds it")
    void theFilesystemIsNotSelectable() throws Exception {
        // The delivery record carries bytes and a size. There is no component for a path, a mount option, a
        // filesystem type, an image or a command, so there is no argument a caller could pass to weaken the
        // boundary and no validation anyone could forget.
        assertThat(SandboxSecurityProfile.SourceDelivery.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("frame", "filesystemBytes");

        String launcher = Files.readString(source("sandbox/DockerSandboxLauncher.java"));
        // The mount options are a literal. Not built from a variable, not read from configuration, not
        // assembled from anything the profile carries beyond a size.
        assertThat(launcher).contains("\"rw,noexec,nosuid,nodev,size=\"");
        // And the source filesystem is a tmpfs the sandbox owns, never a host directory bound in. The mount
        // that KAAS-18 used carried `ro` and nothing else under the mediating runtime.
        assertThat(launcher)
                .as("a source-carrying sandbox must have no host mount of tenant source")
                .doesNotContain("MountType.BIND");
        assertThat(launcher).contains(".withBinds(List.of())");
    }

    @Test
    @DisplayName("the delivery path names the bootstrap and never the test-only fixture planter")
    void theFixturePlanterIsUnreachableFromDelivery() throws Exception {
        // §78's rule, checked rather than intended. The planter writes files with modes the production format
        // cannot express -- an executable one and a setuid one -- so an execution carrying tenant source that
        // could reach it would be an execution whose source filesystem holds a runnable file.
        //
        // It is reachable through exactly one probe constant, and that constant is not the one the delivery
        // path uses.
        String launcher = Files.readString(source("sandbox/DockerSandboxLauncher.java"));
        assertThat(launcher).contains("\"/source-bootstrap\"");
        assertThat(launcher)
                .as("the launcher must not name the fixture planter directly")
                .doesNotContain("\"/source-boundary-fixture\"");

        List<SyntheticProbe> withOverride = Arrays.stream(SyntheticProbe.values())
                .filter(probe -> probe.bootstrapOverride() != null)
                .toList();
        assertThat(withOverride)
                .as("exactly one probe may run something before the bootstrap")
                .containsExactly(SyntheticProbe.SOURCE_BOUNDARY);
        assertThat(SyntheticProbe.WORKLOAD_SOURCE_VERIFY.bootstrapOverride())
                .as("the workload every source-carrying execution runs plants nothing")
                .isNull();

        // And the execution loop chooses that workload from its own field, never from the command.
        String loop = Files.readString(source("execution/ExecutionLoop.java"));
        assertThat(loop)
                .as("no execution path may select the boundary measurement")
                .doesNotContain("SOURCE_BOUNDARY");
    }

    @Test
    @DisplayName("tenant source cannot become an argument, an environment variable or shell text")
    void sourceNeverBecomesACommandLine() throws Exception {
        // The invariant every slice since KAAS-18 has carried, restated where the channel changed. Source now
        // travels on standard input, which is the one place it can go without anything parsing it -- so the
        // thing worth asserting is that the new channel did not open a new way for it to become syntax.
        String launcher = Files.readString(source("sandbox/DockerSandboxLauncher.java"));
        assertThat(launcher).doesNotContain("sh -c").doesNotContain("\"-c\"");
        // The frame reaches the container through an attached stream and nothing else. If the delivery ever
        // appeared in withCmd or withEnv, tenant bytes would be in a process's argument vector.
        assertThat(launcher).contains(".withStdIn(stdin)");
        assertThat(launcher).doesNotContain("withCmd(profile.sourceDelivery()");
        assertThat(launcher).doesNotContain("frame()) +");

        // The bootstrap performs one privileged operation and every argument to it is a compile-time
        // constant. A mount call taking a variable would be a mount the stream could influence.
        String bootstrap = Files.readString(probeSource("source-bootstrap.c"));
        assertThat(bootstrap).contains("mount(\"none\", SOURCE_ROOT, NULL,");
        assertThat(bootstrap)
                .as("the bootstrap must not run a shell or an interpreter over anything it read")
                .doesNotContain("system(")
                .doesNotContain("popen(");
        // It hands over to a fixed interpreter and a fixed script, and the mode word is matched against
        // literals rather than passed through.
        assertThat(bootstrap).contains("execve(SHELL, handover, envp)");
    }

    @Test
    @DisplayName("the bundle format still cannot express a mode, a link or a device")
    void theFormatRemainsModeless() throws Exception {
        // Filesystem enforcement and format enforcement are independent layers, and gaining the first is not
        // a reason to give up the second -- particularly here, where the runtime does not implement nodev and
        // the format's inability to carry a device node is one of the things standing in its place.
        assertThat(SourceBundle.ExpectedEntry.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("logicalPath", "contentDigest");

        String bootstrap = Files.readString(probeSource("source-bootstrap.c"));
        // Every file the bootstrap creates gets one platform-chosen mode, written at creation so the file is
        // never briefly something else.
        assertThat(bootstrap).contains("O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0444");
        assertThat(bootstrap)
                .as("the bootstrap has no path that creates anything but a regular file or a directory")
                .doesNotContain("mknod")
                .doesNotContain("symlink")
                .doesNotContain("link(");
    }

    private static Path source(String relative) {
        Path fromModule = Path.of("src/main/java/com/kaas/runner").resolve(relative);
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("services/runner/src/main/java/com/kaas/runner").resolve(relative);
    }

    private static Path probeSource(String name) {
        Path fromModule = Path.of("src/main/docker/probe").resolve(name);
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("services/runner/src/main/docker/probe").resolve(name);
    }
}
