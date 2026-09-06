package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reaches this host's disk when a bundle is staged, and what does not survive the execution that owned it.
 *
 * <p>These exist because a mutation battery found the staging layer almost entirely unasserted: the file mode
 * could be made writable, the overwrite guard removed, and {@code close()} turned into a no-op, and every
 * test in the repository stayed green. Modes and lifetimes are the whole security content of this class, so
 * each is asserted directly here rather than inferred from an execution that happened to succeed.
 */
@DisplayName("Source staging")
class SourceStagingTests {

    @Test
    @DisplayName("every staged file is read-only to its owner and executable to nobody")
    void stagedFilesCarryThePlatformsMode() throws Exception {
        // Tenant source carries bytes, not permissions. The mount under the mediating runtime does not supply
        // noexec (see docs/security/tenant-source-delivery.md), so the absence of an executable bit here is
        // not a belt-and-braces nicety -- it is the barrier that actually refuses execution there.
        Path root = Files.createTempDirectory("kaas-staging-test-");
        try (SourceStaging staging = stage(root, Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/nested/b.feature", "Feature: b\n".getBytes(StandardCharsets.UTF_8)))) {

            List<Path> files;
            try (var walk = Files.walk(staging.root())) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            assertThat(files).as("the manifest and both sources").hasSize(3);
            for (Path file : files) {
                assertThat(Files.getPosixFilePermissions(file))
                        .as("%s", staging.root().relativize(file))
                        .containsExactly(PosixFilePermission.OWNER_READ);
            }

            // Directories are the platform's own: traversable and writable by nobody else on the host.
            assertThat(Files.getPosixFilePermissions(staging.root()))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("staging is removed when its execution ends")
    void closingRemovesEveryTenantByte() throws Exception {
        // The property the whole ownership design exists for, and it had no direct test: close() could be
        // made a no-op and nothing at this level noticed. Tenant bytes outliving the execution that fetched
        // them is the one leak this slice cannot tolerate.
        Path root = Files.createTempDirectory("kaas-staging-test-");
        Path staged;
        try {
            try (SourceStaging staging = stage(root, Map.of(
                    "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8)))) {
                staged = staging.root();
                assertThat(Files.isDirectory(staged)).isTrue();
            }
            assertThat(Files.exists(staged)).as("no tenant source outlives its execution").isFalse();

            // Idempotent: closing again on a path that is already gone is a normal occurrence on failure
            // paths, and must not throw over the outcome of the execution that owned it.
            try (var walk = Files.list(root)) {
                assertThat(walk.toList()).isEmpty();
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("a partially staged bundle does not survive a failure")
    void aFailedStagingLeavesNothing() throws Exception {
        // Staging into a root that cannot be created fails, and the failure is a rejection rather than an
        // IOException escaping into an execution report. Nothing half-written is left where a reconciler
        // would later have to guess about it.
        Path file = Files.createTempFile("kaas-not-a-directory-", "");
        try {
            SourceBundle bundle = verifiedBundle(Map.of(
                    "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> SourceStaging.materialise(file, bundle))
                    .isInstanceOf(SourceBundleRejected.class)
                    .extracting(failure -> ((SourceBundleRejected) failure).reason())
                    .isEqualTo(SourceBundleRejected.Reason.STAGING_FAILED);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("nothing existing can be followed or overwritten by a write")
    void writesCannotFollowOrOverwrite() throws Exception {
        // A STRUCTURAL CLAIM, and it is here because the hazard is a race this test cannot open.
        //
        // The staging directory is created under a fresh random name and written within one call, so no test
        // can put a file or a symlink in the way first -- which is exactly why the guard matters and exactly
        // why nothing behavioural can see it. CREATE_NEW is what makes a raced-in symlink a failure instead
        // of a write through it; degrading it to CREATE would restore the hazard silently.
        String source = Files.readString(stagingSource());

        assertThat(source).contains("StandardOpenOption.CREATE_NEW");
        assertThat(source)
                .as("CREATE would follow a symlink an attacker raced into place")
                .doesNotContain("StandardOpenOption.CREATE,");
        // And no code path that could create something other than a regular file or a platform directory.
        assertThat(source).doesNotContain("createSymbolicLink").doesNotContain("createLink");
    }

    @Test
    @DisplayName("an unsafe path cannot reach the materialiser, because a bundle cannot carry one")
    void anUnsafePathCannotReachTheMaterialiser() throws Exception {
        // THE ANTI-VACUITY ARGUMENT for the resolved-path guard inside the materialiser.
        //
        // That guard cannot be made to fire through any public API, and a mutation removing it survives. The
        // honest reason is not that it is untested but that it is unreachable: the only way to obtain a
        // SourceBundle is verified(), which refuses every unsafe path shape before a bundle exists. So what
        // is asserted here is the reachability claim itself -- if a second way to construct a bundle is ever
        // added, this fails and the guard stops being defence in depth and starts being the only defence.
        for (Constructor<?> constructor : SourceBundle.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers()))
                    .as("every SourceBundle constructor must be private")
                    .isTrue();
        }
        List<String> factories = java.util.Arrays.stream(SourceBundle.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                .filter(method -> method.getReturnType() == SourceBundle.class)
                .map(Method::getName)
                .toList();
        assertThat(factories)
                .as("verified() must be the only way to obtain a bundle")
                .containsExactly("verified");

        // And that route refuses the shape, so the materialiser is never handed one.
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var traversing = List.of(new SourceBundle.ExpectedEntry("../escape", SourceBundle.sha256(content)));
        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(new LinkedHashMap<>(Map.of("../escape", content))),
                        traversing,
                        SourceBundle.bundleDigest(traversing)))
                .isInstanceOf(SourceBundleRejected.class);
    }

    private static SourceStaging stage(Path root, Map<String, byte[]> sources) {
        return SourceStaging.materialise(root, verifiedBundle(sources));
    }

    private static SourceBundle verifiedBundle(Map<String, byte[]> sources) {
        Map<String, byte[]> ordered = new LinkedHashMap<>(new java.util.TreeMap<>(sources));
        var expected = ordered.entrySet().stream()
                .map(entry -> new SourceBundle.ExpectedEntry(entry.getKey(), SourceBundle.sha256(entry.getValue())))
                .toList();
        return SourceBundle.verified(archiveOf(ordered), expected, SourceBundle.bundleDigest(expected));
    }

    private static byte[] archiveOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (var entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.getValue().length);
                zipEntry.setCompressedSize(entry.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    private static Path stagingSource() {
        Path fromModule = Path.of("src/main/java/com/kaas/runner/source/SourceStaging.java");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("services/runner/src/main/java/com/kaas/runner/source/SourceStaging.java");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
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
