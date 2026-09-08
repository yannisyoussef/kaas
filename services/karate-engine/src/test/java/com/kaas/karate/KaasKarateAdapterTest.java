package com.kaas.karate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the adapter will and will not hand to Karate.
 *
 * <p>The manifest is written by the source bootstrap from the platform's own frame, so in production it is
 * trustworthy. These tests drive it with manifests the bootstrap would never write, because "the producer is
 * trusted" is an argument about today's callers and the check below is what survives a change to them.
 */
@DisplayName("The engine adapter's authorized feature set")
class KaasKarateAdapterTest {

    @Test
    @DisplayName("the manifest decides which features run, in the order it lists them")
    void theManifestDecidesWhatRuns(@TempDir Path root) throws IOException {
        Path files = root.resolve("files");
        Files.createDirectories(files.resolve("features"));
        Path manifest = manifest(root, "features/a.feature", "features/b.feature");

        assertThat(KaasKarateAdapter.authorizedFeatures(manifest, files))
                .containsExactly(
                        files.resolve("features/a.feature").toString(),
                        files.resolve("features/b.feature").toString());
    }

    @Test
    @DisplayName("the header line is not a feature")
    void theHeaderIsNotAFeature(@TempDir Path root) throws IOException {
        // The first line names the format and carries the bundle digest and entry count. Running it would ask
        // Karate to execute a path called "kaas.source-bundle.v1", and the engine error that produced would be
        // reported for every run rather than being obviously an off-by-one.
        Path files = root.resolve("files");
        Files.createDirectories(files);
        Path manifest = manifest(root, "features/only.feature");

        assertThat(KaasKarateAdapter.authorizedFeatures(manifest, files)).hasSize(1);
    }

    @Test
    @DisplayName("a manifest entry that escapes the source root is refused, not resolved")
    void anEscapingEntryIsRefused(@TempDir Path root) throws IOException {
        // The third place this is checked: the control plane authorizes the paths, the bootstrap refuses to
        // write outside the root, and this refuses to open outside it. Depth is the point — each of the other
        // two is a different program, and this is the last one before a path becomes a file Karate opens.
        Path files = root.resolve("files");
        Files.createDirectories(files);
        Path manifest = manifest(root, "../../etc/passwd");

        assertThatThrownBy(() -> KaasKarateAdapter.authorizedFeatures(manifest, files))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an absolute manifest entry is refused too")
    void anAbsoluteEntryIsRefused(@TempDir Path root) throws IOException {
        // Path.resolve on an absolute path DISCARDS the root entirely and returns the argument, so an absolute
        // entry does not traverse out of the root — it never enters it. A check written only against "../"
        // would let this through while looking correct.
        Path files = root.resolve("files");
        Files.createDirectories(files);
        Path manifest = manifest(root, "/etc/passwd");

        assertThatThrownBy(() -> KaasKarateAdapter.authorizedFeatures(manifest, files))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a manifest with no entries authorizes nothing, which is not the same as authorizing everything")
    void anEmptyManifestAuthorizesNothing(@TempDir Path root) throws IOException {
        Path files = root.resolve("files");
        Files.createDirectories(files);
        Path manifest = root.resolve("manifest.tsv");
        Files.writeString(manifest, "kaas.source-bundle.v1\tsha256:0\t0\n", StandardCharsets.UTF_8);

        // The caller turns this into ENGINE_ERROR rather than into a directory scan or a passing run. Karate's
        // own default is to scan, which is exactly what an empty list must not become.
        assertThat(KaasKarateAdapter.authorizedFeatures(manifest, files)).isEmpty();
    }

    private static Path manifest(Path root, String... entries) throws IOException {
        StringBuilder text = new StringBuilder("kaas.source-bundle.v1\tsha256:0\t" + entries.length + "\n");
        for (String entry : entries) {
            text.append(entry).append("\tsha256:0\t1\n");
        }
        Path manifest = root.resolve("manifest.tsv");
        Files.writeString(manifest, text.toString(), StandardCharsets.UTF_8);
        return manifest;
    }

    @Test
    @DisplayName("the engine on this module's classpath is the version the platform authorizes")
    void theEngineIsTheAuthorizedVersion() throws IOException {
        // ANTI-VACUITY for every other suite in the repository that asserts an engine version. Those read a
        // line the adapter printed; this reads the jar. If the Gradle pin were bumped, this fails here rather
        // than in a container test whose output someone would have to notice.
        try (var meta = KaasKarateAdapter.class
                .getClassLoader()
                .getResourceAsStream("karate-meta.properties")) {
            assertThat(meta).as("karate-core must be on this module's classpath").isNotNull();
            var properties = new java.util.Properties();
            properties.load(meta);
            assertThat(properties.getProperty("karate.version")).isEqualTo("2.1.2");
        }
    }

    @Test
    @DisplayName("the engine module carries no platform credential-bearing class")
    void theEngineCarriesNothingPrivileged() {
        // Everything on this classpath is reachable from tenant source through Java.type. So the question is
        // not whether the adapter uses these, it is whether they are PRESENT. Enumerated rather than asked
        // after by name where possible, but a class name is what a classloader takes.
        for (String forbidden : List.of(
                "com.kaas.runner.sandbox.DockerSandboxLauncher",
                "com.kaas.runner.attestation.AttestationSigner",
                "com.kaas.api.KaasApiApplication",
                "com.github.dockerjava.api.DockerClient",
                // NOT javax.sql.DataSource: it ships in the JDK's java.sql module and is present in every
                // JVM. Asserting its absence failed here, which is the useful kind of failure — the platform
                // cannot subtract classes from the JDK, and that is precisely why the security argument is
                // CONTAINMENT rather than an absent-class inventory. What this list can meaningfully assert
                // is that no platform-authored or credential-carrying LIBRARY joined the engine's classpath.
                "org.springframework.jdbc.datasource.DriverManagerDataSource")) {
            assertThatThrownBy(() -> Class.forName(forbidden))
                    .as("%s must not be reachable from tenant code", forbidden)
                    .isInstanceOf(ClassNotFoundException.class);
        }
        // The positive half, without which the loop above would pass in a JVM where Class.forName always
        // threw — including one where the classpath was empty.
        assertThat(KaasKarateAdapter.PROTOCOL).isEqualTo("kaas.karate-result.v1");
    }
}
