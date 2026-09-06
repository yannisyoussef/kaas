package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceStaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the sandbox actually sees when inert tenant source is mounted.
 *
 * <h2>Observed, not requested</h2>
 *
 * <p>Every claim here is read from inside the sandbox by the platform's verifier: the mount options the
 * kernel reports, whether a write is refused, whether any setuid file exists, and whether the bytes hash to
 * what the manifest says. Requesting a mount option and getting one are different things, and this slice
 * found out the hard way that they differ by runtime.
 *
 * <p><strong>The mount-flag findings are runtime-specific and are not uniform.</strong> This suite records
 * what each runtime does rather than asserting a single expectation; the mediated runtime's behaviour is
 * asserted in the strong-runtime gate, where that runtime exists.
 */
@DisplayName("Source mount")
class SourceMountTests {

    private final String generation = "source-mount-" + UUID.randomUUID();
    private Path stagingRoot;

    @AfterEach
    void nothingSurvives() throws Exception {
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of("kaas.launcher.generation", generation))
                        .exec())
                .isEmpty();
        if (stagingRoot != null) {
            try (var paths = Files.walk(stagingRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // Test cleanup only.
                    }
                });
            }
        }
    }

    /** Stages a bundle and runs the platform verifier over it, returning what the sandbox observed. */
    private Map<String, String> verify(Map<String, byte[]> sources) throws Exception {
        stagingRoot = Files.createTempDirectory("kaas-source-mount-");
        var expected = sources.entrySet().stream()
                .map(entry -> new SourceBundle.ExpectedEntry(entry.getKey(), SourceBundle.sha256(entry.getValue())))
                .toList();
        SourceBundle bundle = SourceBundle.verified(
                archiveOf(sources), expected, SourceBundle.bundleDigest(expected));

        try (SourceStaging staging = SourceStaging.materialise(stagingRoot, bundle)) {
            var profile = SandboxSecurityProfile.withSource(SandboxTestSupport.profile(), staging.root());
            SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                    .run(new SandboxLaunchRequest(
                            SyntheticProbe.WORKLOAD_SOURCE_VERIFY, profile.version(), UUID.randomUUID()));
            assertThat(outcome.failure()).as("%s", outcome).isEmpty();
            return outcome.observations();
        }
    }

    private static byte[] archiveOf(Map<String, byte[]> entries) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setMethod(java.util.zip.ZipOutputStream.STORED);
            for (var entry : entries.entrySet()) {
                var zipEntry = new java.util.zip.ZipEntry(entry.getKey());
                zipEntry.setMethod(java.util.zip.ZipEntry.STORED);
                zipEntry.setSize(entry.getValue().length);
                zipEntry.setCompressedSize(entry.getValue().length);
                var crc = new java.util.zip.CRC32();
                crc.update(entry.getValue());
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @Timeout(300)
    @DisplayName("the mounted bytes hash to what the manifest says, from inside the sandbox")
    void theMountedBytesAreTheStagedBytes() throws Exception {
        // The authoritative check. The runner verified these bytes on the host before staging them, and that
        // verification describes a moment that has passed; this is the view the workload actually has.
        byte[] awkward = ("CRLF\r\nLF\ntab\there \"quotes\" \\backslash\\ "
                        + "emoji 🙂 combining é non-BMP 𝄞\n")
                .getBytes(StandardCharsets.UTF_8);
        var observations = verify(new java.util.LinkedHashMap<>(Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/nested/b.feature", awkward)));

        assertThat(observations).containsEntry("workload_outcome", "PASSED");
        assertThat(observations).containsEntry("source_entry_mismatches", "0");
        assertThat(observations).containsEntry("source_entries_verified", "2");
        assertThat(observations).containsEntry("source_entries_present", "2");
        assertThat(observations).containsEntry("source_format", SourceBundleContract.FORMAT_VERSION);
    }

    @Test
    @Timeout(300)
    @DisplayName("the source mount is read-only, and a write is actually refused")
    void theMountIsReadOnly() throws Exception {
        var observations = verify(new java.util.LinkedHashMap<>(
                Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8))));

        // BOTH: what the mount says about itself, and what happens when the sandbox tries. A mount that
        // reports ro and accepts a write is a mount that reports.
        assertThat(observations).containsEntry("source_mount_ro", "true");
        assertThat(observations)
                .as("mount options were: %s", observations.get("source_mount_options"))
                .containsEntry("source_write_refused", "true");
    }

    @Test
    @Timeout(300)
    @DisplayName("no setuid material and no irregular filesystem entry can reach the mount")
    void theMountCarriesOnlyRegularFiles() throws Exception {
        // KAAS-17 made NO_SETUID_BINARIES one of two controls compensating for the NoNewPrivs observation
        // gVisor cannot provide. A bundle that could introduce setuid material would undermine that argument,
        // so this asserts it of the real mounted bundle rather than of the bare image.
        //
        // The bundle format carries a path and bytes; it cannot express a mode, a device or a link, and the
        // materialiser writes regular files at a fixed non-executable mode. This is that claim, observed.
        var observations = verify(new java.util.LinkedHashMap<>(Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/b.feature", "Feature: b\n".getBytes(StandardCharsets.UTF_8))));

        assertThat(observations).containsEntry("source_setuid_files", "0");
        assertThat(observations).containsEntry("source_irregular_entries", "0");
    }

    @Test
    @Timeout(300)
    @DisplayName("source that looks like a script is read and hashed, never executed")
    void hostileSourceIsOnlyRead() throws Exception {
        // The bytes say to run things, in several syntaxes. The verifier runs sha256sum.
        String hostile = "#!/bin/sh\ntouch /tmp/kaas-owned\n$(touch /tmp/kaas-owned)\n"
                + "`touch /tmp/kaas-owned`\nRuntime.getRuntime().exec(\"id\")\n"
                + "* def x = read('classpath:evil.js')\n";
        var observations = verify(new java.util.LinkedHashMap<>(
                Map.of("features/hostile.feature", hostile.getBytes(StandardCharsets.UTF_8))));

        assertThat(observations).containsEntry("workload_outcome", "PASSED");
        assertThat(observations).containsEntry("source_entry_mismatches", "0");
        // And no observation carries any of it. The verifier reports counts and flags; source text appearing
        // in an observation would be tenant content in a log.
        assertThat(String.join(" ", observations.values()))
                .as("no source text may appear in what the sandbox reported")
                .doesNotContain("touch", "Runtime.getRuntime", "classpath:");
    }

    @Test
    @Timeout(300)
    @DisplayName("a bundle whose mounted bytes were altered after staging fails inside the sandbox")
    void alteredMountedBytesFail() throws Exception {
        // THE SECOND AXIS. The runner's host-side check passed; this changes the files afterwards, which is
        // the window a host-side hash alone cannot cover. The in-sandbox verifier must catch it, because it
        // recomputes from the mounted view rather than trusting what was verified earlier.
        stagingRoot = Files.createTempDirectory("kaas-source-mount-");
        byte[] original = "Feature: original\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(original)));
        SourceBundle bundle = SourceBundle.verified(
                archiveOf(new java.util.LinkedHashMap<>(Map.of("features/a.feature", original))),
                expected,
                SourceBundle.bundleDigest(expected));

        try (SourceStaging staging = SourceStaging.materialise(stagingRoot, bundle)) {
            Path file = staging.root().resolve(SourceBundleContract.FILES_DIRECTORY).resolve("features/a.feature");
            // A test-only mutation between host verification and launch. Nothing in production can do this:
            // the staging directory is created, written and mounted within one try-with-resources.
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.write(file, "Feature: substituted\n".getBytes(StandardCharsets.UTF_8));

            var profile = SandboxSecurityProfile.withSource(SandboxTestSupport.profile(), staging.root());
            SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                    .run(new SandboxLaunchRequest(
                            SyntheticProbe.WORKLOAD_SOURCE_VERIFY, profile.version(), UUID.randomUUID()));

            assertThat(outcome.observations()).containsEntry("workload_outcome", "FAILED");
            assertThat(outcome.observations()).containsEntry("source_entry_mismatches", "1");
        }
    }
}
