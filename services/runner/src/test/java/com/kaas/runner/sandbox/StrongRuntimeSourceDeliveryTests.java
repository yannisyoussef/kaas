package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceStaging;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Inert tenant source, delivered into a sandbox confined by the mediating runtime.
 *
 * <h2>Why this cannot be inferred from the baseline suite</h2>
 *
 * <p>{@code SourceMountTests} makes the same observations under the default runtime, and this slice found out
 * that they do not transfer. A {@code local}-driver bind under the baseline arrives as an ext4 mount carrying
 * {@code ro,nosuid,nodev,noexec}; under the mediating runtime the same request arrives over the gofer as a 9p
 * mount carrying {@code ro} and nothing else. Two measurements, two runtimes, one requested configuration.
 *
 * <p>So this suite exists to state which half of the requirement the stronger runtime actually meets, and to
 * fail if that ever changes in either direction. It runs in the mandatory strong-runtime gate, where the
 * runtime is present and a skip is impossible.
 *
 * <h2>What is asserted and what is recorded</h2>
 *
 * <p>Asserted: the mount is read-only and a write is refused; the bytes the sandbox sees hash to what the
 * command authorized; nothing setuid or irregular reaches it; and executing a source file is refused.
 *
 * <p>Recorded, with the assertion stated in the honest direction: {@code noexec} is <strong>not</strong>
 * carried onto the mount here. That is a gap in the boundary rather than a property of this design, and it is
 * asserted as it currently stands so that a gVisor release which closes it fails this test and forces the
 * adjudication to be redone rather than silently improving under an unchanged claim. See
 * {@code docs/security/tenant-source-delivery.md}.
 */
@DisplayName("Strong runtime source delivery")
class StrongRuntimeSourceDeliveryTests {

    private final String generation = "strong-source-" + UUID.randomUUID();
    private Path stagingRoot;

    @BeforeAll
    static void requireTheRuntime() {
        // FAIL, never skip. The whole value of this suite is that it observed the mediating runtime; a skip
        // on a host without it would report the same green as a real measurement.
        assertThat(SandboxTestSupport.docker().infoCmd().exec().getRuntimes())
                .as("this suite is the source-delivery evidence for the mediating runtime; without the "
                        + "runtime there is no evidence, and that must fail rather than skip")
                .containsKey(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    @AfterEach
    void nothingSurvives() throws Exception {
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of("kaas.launcher.generation", generation))
                        .exec())
                .as("no mediated container may outlive a source delivery")
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

    @Test
    @Timeout(600)
    @DisplayName("the mounted bytes are the authorized bytes, seen from inside the mediated sandbox")
    void theMediatedSandboxSeesExactlyTheAuthorizedBytes() throws Exception {
        // Awkward but valid UTF-8 with CRLF, combining marks and a non-BMP character. Byte exactness through
        // a gofer is a different claim from byte exactness through an overlay, and normalisation or
        // line-ending conversion anywhere in that path would show up here and nowhere else.
        byte[] awkward = ("CRLF\r\nLF\ntab\there \"quotes\" \\backslash\\ "
                        + "emoji 🙂 combining é non-BMP 𝄞\n")
                .getBytes(StandardCharsets.UTF_8);
        var observations = verify(new LinkedHashMap<>(Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/nested/b.feature", awkward)));

        assertThat(observations).containsEntry("workload_outcome", "PASSED");
        assertThat(observations).containsEntry("source_entry_mismatches", "0");
        assertThat(observations).containsEntry("source_entries_verified", "2");
        assertThat(observations).containsEntry("source_entries_present", "2");
        assertThat(observations).containsEntry("source_format", SourceBundleContract.FORMAT_VERSION);
    }

    @Test
    @Timeout(600)
    @DisplayName("the mediated mount is read-only in fact, not only in its options")
    void theMediatedMountIsReadOnly() throws Exception {
        var observations = verify(new LinkedHashMap<>(
                Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8))));

        // The half of the requirement this runtime does meet, and it meets it in both forms: the mount says
        // ro, and a write actually fails. Either alone would be weaker -- a mount can report a flag it does
        // not honour, and a write can fail for a reason unrelated to the mount.
        assertThat(observations)
                .as("mediated mount options were: %s", observations.get("source_mount_options"))
                .containsEntry("source_mount_ro", "true");
        assertThat(observations).containsEntry("source_write_refused", "true");
    }

    @Test
    @Timeout(600)
    @DisplayName("executing mounted source is refused, though noexec is not what refuses it here")
    void executingMountedSourceIsRefused() throws Exception {
        // THE GAP, ASSERTED AS IT STANDS.
        //
        // The requested configuration asks for noexec. Under this runtime the mount does not carry it: the
        // bind arrives over the gofer and only ro survives. What refuses execution is the other barrier --
        // the materialiser writes every file 0444, and the bundle format cannot express a mode at all -- so
        // the outcome is right for a reason weaker than the one that was asked for.
        //
        // Both are asserted, in the direction each is actually true. If a future runtime release starts
        // carrying noexec, the second assertion fails and this adjudication gets redone rather than quietly
        // becoming stale in the platform's favour.
        var observations = verify(new LinkedHashMap<>(
                Map.of("features/runnable.feature", "#!/bin/sh\ntouch /tmp/kaas-owned\n"
                        .getBytes(StandardCharsets.UTF_8))));
        // This case carries every key the gate reads back: the mount options, both refusals, and the counts.
        record(observations);

        assertThat(observations)
                .as("no tenant byte may be executed, whatever the mount carries")
                .containsEntry("source_exec_refused", "true");
        assertThat(observations)
                .as("mount options were: %s -- if this now reports noexec, the delivery boundary improved "
                        + "and docs/security/tenant-source-delivery.md must be re-adjudicated",
                        observations.get("source_mount_options"))
                .containsEntry("source_mount_noexec", "false");
    }

    @Test
    @Timeout(600)
    @DisplayName("nothing setuid or irregular reaches the mediated mount")
    void nothingSetuidOrIrregularReachesTheMount() throws Exception {
        // NO_SETUID_BINARIES is one of the two controls compensating for the NoNewPrivs observation this
        // runtime cannot provide (KAAS-17). Source delivery is the first thing that puts platform-external
        // bytes inside that boundary, so the compensation is re-observed with a bundle actually mounted
        // rather than argued to be unaffected.
        var observations = verify(new LinkedHashMap<>(Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/b.feature", "Feature: b\n".getBytes(StandardCharsets.UTF_8))));

        assertThat(observations).containsEntry("source_setuid_files", "0");
        assertThat(observations).containsEntry("source_irregular_entries", "0");
    }

    @Test
    @Timeout(600)
    @DisplayName("bytes altered between host verification and launch fail inside the mediated sandbox")
    void alteredMountedBytesFailUnderTheMediatingRuntime() throws Exception {
        // The second axis, under the runtime that matters. The runner's host-side digest check describes a
        // moment that has passed; this changes the files after it and before the launch. Only the in-sandbox
        // verifier can see that, and only because it recomputes from the mounted view.
        stagingRoot = Files.createTempDirectory("kaas-strong-source-");
        byte[] original = "Feature: original\n".getBytes(StandardCharsets.UTF_8);
        var expected =
                java.util.List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(original)));
        SourceBundle bundle = SourceBundle.verified(
                archiveOf(new LinkedHashMap<>(Map.of("features/a.feature", original))),
                expected,
                SourceBundle.bundleDigest(expected));

        try (SourceStaging staging = SourceStaging.materialise(stagingRoot, bundle)) {
            Path file = staging.root().resolve(SourceBundleContract.FILES_DIRECTORY).resolve("features/a.feature");
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.write(file, "Feature: substituted\n".getBytes(StandardCharsets.UTF_8));

            var observations = launch(staging);
            assertThat(observations).containsEntry("workload_outcome", "FAILED");
            assertThat(observations).containsEntry("source_entry_mismatches", "1");
        }
    }

    /** Stages a bundle under the mediating runtime and returns what the sandbox observed. */
    private Map<String, String> verify(Map<String, byte[]> sources) throws Exception {
        stagingRoot = Files.createTempDirectory("kaas-strong-source-");
        var expected = sources.entrySet().stream()
                .map(entry -> new SourceBundle.ExpectedEntry(entry.getKey(), SourceBundle.sha256(entry.getValue())))
                .toList();
        SourceBundle bundle =
                SourceBundle.verified(archiveOf(sources), expected, SourceBundle.bundleDigest(expected));
        try (SourceStaging staging = SourceStaging.materialise(stagingRoot, bundle)) {
            return launch(staging);
        }
    }

    /**
     * Writes what the sandbox observed where the gate can read it back.
     *
     * <p>The assertions in this suite are the check; this is the record. A green job that says only "the
     * suite passed" asks a reader to take the mount findings on trust, and the mount findings are the honest
     * part of this slice — one requested flag is carried and another is not. The gate re-reads these lines
     * and fails on them independently, so the evidence is load-bearing rather than decorative.
     *
     * <p>Only platform-defined keys and values are written. No logical path, no source byte, and no
     * capability material passes through here.
     */
    private static void record(Map<String, String> observations) {
        String directory = System.getenv("RUNNER_TEMP");
        if (directory == null || directory.isBlank()) {
            return; // Off CI there is no gate to read it.
        }
        StringBuilder evidence = new StringBuilder();
        evidence.append("source_verification=")
                .append("PASSED".equals(observations.get("workload_outcome")) ? "VALID" : "INVALID")
                .append('\n');
        for (String key : java.util.List.of(
                "source_mount_options", "source_mount_ro", "source_mount_noexec", "source_mount_nosuid",
                "source_mount_nodev", "source_write_refused", "source_exec_refused", "source_setuid_files",
                "source_irregular_entries", "source_entries_verified", "source_entry_mismatches")) {
            String value = observations.get(key);
            if (value != null) {
                evidence.append(key).append('=').append(value).append('\n');
            }
        }
        try {
            Files.writeString(
                    Path.of(directory, "source-delivery-evidence.txt"),
                    evidence.toString(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException unwritable) {
            throw new java.io.UncheckedIOException(unwritable);
        }
    }

    private Map<String, String> launch(SourceStaging staging) {
        // The MEDIATED profile, carrying this execution's source. Derived from the runtime's own profile so
        // it differs from an ordinary mediated launch in exactly one respect.
        var profile = SandboxSecurityProfile.withSource(
                SandboxSecurityProfile.version1(SandboxTestSupport.probeImage(), ExecutionRuntimeType.GVISOR),
                staging.root());
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.WORKLOAD_SOURCE_VERIFY, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        return outcome.observations();
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
}
