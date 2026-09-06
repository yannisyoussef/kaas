package com.kaas.runner.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime binary is measured, not asserted.
 *
 * <h2>Why this suite exists</h2>
 *
 * <p>A mutation replaced the whole measurement with three constants — a fixed version string and a digest of
 * zeroes — and every test in the repository stayed green. That is the shape of failure this field was added
 * to prevent: signed, authentic, confident evidence about a program nobody looked at.
 *
 * <p>So these assert the measurement rather than the plumbing. A digest that did not come from the file is
 * caught by comparing against one computed here; a version that was not read from the binary is caught by
 * running a binary that prints something only this test knows.
 */
@DisplayName("Runtime implementation measurement")
class RuntimeImplementationTest {

    @Test
    @DisplayName("the digest is of the registered file, not of anything the caller supplied")
    void theDigestIsOfTheRegisteredFile() throws Exception {
        // A stand-in for the runtime binary: something executable that prints a version, so both halves of
        // the measurement have a knowable right answer.
        Path binary = executable("#!/bin/sh\necho 'runsc version test-" + System.nanoTime() + "'\n");
        try {
            var measured = RuntimeImplementation.measureAt("runsc", binary);

            assertThat(measured.digest())
                    .as("the digest must be of the file the daemon would run")
                    .isEqualTo(sha256Of(binary));
            assertThat(measured.version())
                    .as("the version must come from executing that file")
                    .isEqualTo(Files.readString(binary).lines().skip(1).findFirst().orElseThrow()
                            .replace("echo '", "").replace("'", ""));
            assertThat(measured.name()).isEqualTo("runsc");
            assertThat(measured.pathIdentity()).matches("path:[a-f0-9]{32}");
        } finally {
            Files.deleteIfExists(binary);
        }
    }

    @Test
    @DisplayName("two different binaries measure differently, which is the whole point of the field")
    void differentBinariesMeasureDifferently() throws Exception {
        Path first = executable("#!/bin/sh\necho 'runsc version alpha'\n");
        Path second = executable("#!/bin/sh\necho 'runsc version beta'\n");
        try {
            var a = RuntimeImplementation.measureAt("runsc", first);
            var b = RuntimeImplementation.measureAt("runsc", second);

            assertThat(a.digest()).isNotEqualTo(b.digest());
            assertThat(a.version()).isNotEqualTo(b.version());
            // And the path identity differs too, so repointing a registration at another build changes the
            // evidence even where the two binaries were somehow identical.
            assertThat(a.pathIdentity()).isNotEqualTo(b.pathIdentity());
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    @Test
    @DisplayName("a registration pointing at nothing is refused rather than measured as absent")
    void aMissingBinaryIsRefused() {
        // Measuring the wrong binary is worse than measuring none: it produces confident, signed evidence
        // about a program the daemon will never run. So an unresolvable registration fails here rather than
        // falling back to whatever a PATH lookup would find.
        assertThatThrownBy(() -> RuntimeImplementation.measureAt("runsc", Path.of("/nonexistent/kaas/runsc")))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failure -> assertThat(((AttestationProductionFailed) failure).failure())
                        .isEqualTo(AttestationFailure.RUNTIME_UNIDENTIFIED));
    }

    @Test
    @DisplayName("a directory is not a runtime binary")
    void aDirectoryIsRefused() throws Exception {
        Path directory = Files.createTempDirectory("kaas-runtime-");
        try {
            assertThatThrownBy(() -> RuntimeImplementation.measureAt("runsc", directory))
                    .isInstanceOf(AttestationProductionFailed.class);
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    private static Path executable(String script) throws Exception {
        Path file = Files.createTempFile("kaas-runtime-", "");
        Files.writeString(file, script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private static String sha256Of(Path file) throws Exception {
        return "sha256:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
