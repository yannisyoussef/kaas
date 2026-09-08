package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner is on the other side of the engine's blast radius, and this is what says so.
 *
 * <p>The Gradle guard already refuses a Karate coordinate on the runner's classpaths. This asserts the same
 * thing from inside the JVM that actually runs, which is a different claim: a build-time check inspects a
 * dependency graph, and this inspects what a classloader can reach. They fail on different mistakes — a jar
 * vendored into a resources directory would pass the first and fail this one.
 */
@DisplayName("The runner/engine trust boundary")
class EngineTrustBoundaryTest {

    @Test
    @DisplayName("no Karate class is reachable from the runner, under either set of coordinates")
    void karateIsNotReachableFromTheRunner() {
        for (String engineClass : List.of(
                "io.karatelabs.core.Runner",
                "io.karatelabs.Main",
                "com.intuit.karate.Runner",
                // The platform's own adapter, too. It is not privileged, but it belongs to the process that
                // runs tenant code, and its presence here would mean the two modules had been merged.
                "com.kaas.karate.KaasKarateAdapter")) {
            assertThatThrownBy(() -> Class.forName(engineClass))
                    .as("%s must not be reachable from the trusted runner", engineClass)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("the absence above is an absence, not a classloader that finds nothing")
    void theRunnersOwnClasspathIsIntact() throws ClassNotFoundException {
        // Without this the test above passes in a JVM with no classpath at all, which is the shape every
        // "assert it is missing" test eventually fails into.
        assertThat(Class.forName("com.github.dockerjava.api.DockerClient")).isNotNull();
        assertThat(Class.forName("com.kaas.runner.sandbox.DockerSandboxLauncher")).isNotNull();
    }

    // ------------------------------------------------------------------ the image's engine version

    @Test
    @DisplayName("an image context shipping the authorized engine is accepted")
    void theAuthorizedEngineIsAccepted(@TempDir Path context) throws Exception {
        Files.createDirectories(context.resolve("lib"));
        Files.createFile(context.resolve("lib/karate-core-2.1.2.jar"));
        Files.createFile(context.resolve("lib/netty-handler-4.2.17.Final.jar"));

        KarateEngineImage.verifyEngineVersion(context, "2.1.2");
    }

    @Test
    @DisplayName("an image context shipping a different engine version is refused")
    void anUnauthorizedEngineVersionIsRefused(@TempDir Path context) throws Exception {
        // The failure this exists for: the Gradle pin is bumped, the image is rebuilt, and every command is
        // still authorized against a version string that no longer describes what runs.
        Files.createDirectories(context.resolve("lib"));
        Files.createFile(context.resolve("lib/karate-core-2.2.0.jar"));

        assertThatThrownBy(() -> KarateEngineImage.verifyEngineVersion(context, "2.1.2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("karate-core-2.1.2.jar");
    }

    @Test
    @DisplayName("an image context shipping two engines is refused, because directory order would pick one")
    void twoEnginesAreRefused(@TempDir Path context) throws Exception {
        // The quiet one. Both jars on a wildcard classpath means the running engine is decided by whatever
        // order the JVM expanded the glob in, which is not a decision the platform made.
        Files.createDirectories(context.resolve("lib"));
        Files.createFile(context.resolve("lib/karate-core-2.1.2.jar"));
        Files.createFile(context.resolve("lib/karate-core-2.2.0.jar"));

        assertThatThrownBy(() -> KarateEngineImage.verifyEngineVersion(context, "2.1.2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an image context shipping no engine at all is refused")
    void noEngineIsRefused(@TempDir Path context) throws Exception {
        Files.createDirectories(context.resolve("lib"));

        assertThatThrownBy(() -> KarateEngineImage.verifyEngineVersion(context, "2.1.2"))
                .isInstanceOf(IllegalStateException.class);
    }
}
