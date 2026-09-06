package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.source.SourceBundle.ExpectedEntry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A redeemed bundle is verified against what the command authorized, not against itself.
 *
 * <p>The archive's entry names are never used as paths. The runner walks the command's feature list and looks
 * each authorized path up in what arrived, so an entry the command did not name is a refusal rather than a
 * file — which is what makes reading an archive here defensible at all.
 */
@DisplayName("Source bundle")
class SourceBundleTests {

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

    private static ExpectedEntry expect(String path, byte[] content) {
        return new ExpectedEntry(path, SourceBundle.sha256(content));
    }

    private static Map<String, byte[]> entries(String k1, byte[] v1) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(k1, v1);
        return map;
    }

    private static Map<String, byte[]> entries(String k1, byte[] v1, String k2, byte[] v2) {
        Map<String, byte[]> map = entries(k1, v1);
        map.put(k2, v2);
        return map;
    }

    @Test
    @DisplayName("a bundle carrying exactly the authorized features is accepted, byte for byte")
    void anAuthorizedBundleIsAccepted() {
        byte[] one = "Feature: a\r\n  # tab\there\n".getBytes(StandardCharsets.UTF_8);
        byte[] two = "emoji \uD83D\uDE42 combining e\u0301 and non-BMP \uD834\uDD1E\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", one), expect("nested/b.feature", two));

        SourceBundle bundle = SourceBundle.verified(
                archiveOf(entries("a.feature", one, "nested/b.feature", two)),
                expected,
                SourceBundle.bundleDigest(expected));

        // BYTES, not strings. A comparison of decoded text would pass while CRLF, a BOM, or a Unicode
        // normalisation difference had changed what the tenant actually wrote.
        assertThat(bundle.contents().get("a.feature")).isEqualTo(one);
        assertThat(bundle.contents().get("nested/b.feature")).isEqualTo(two);
        assertThat(bundle.totalBytes()).isEqualTo(one.length + two.length);
    }

    @Test
    @DisplayName("an entry the command did not authorize is refused rather than ignored")
    void anExtraEntryIsRefused() {
        byte[] one = "a".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", one));

        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", one, "extra.feature", "b".getBytes(StandardCharsets.UTF_8))),
                        expected,
                        SourceBundle.bundleDigest(expected)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.WRONG_FEATURE_SET);
    }

    @Test
    @DisplayName("a missing entry is refused")
    void aMissingEntryIsRefused() {
        byte[] one = "a".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", one), expect("b.feature", one));

        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", one)), expected, SourceBundle.bundleDigest(expected)))
                .isInstanceOf(SourceBundleRejected.class);
    }

    @Test
    @DisplayName("an entry whose bytes differ from the authorized digest is refused")
    void substitutedBytesAreRefused() {
        byte[] authorized = "the tenant's source".getBytes(StandardCharsets.UTF_8);
        byte[] substituted = "somebody else's source".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", authorized));

        // Well-formed, right shape, right count. Only the bytes are wrong -- which is exactly what a
        // control-plane defect or a substitution attack produces.
        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", substituted)),
                        expected,
                        SourceBundle.bundleDigest(expected)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.DIGEST_MISMATCH);
    }

    @Test
    @DisplayName("a bundle whose aggregate digest is not the authorized one is refused")
    void aDifferentBundleIsRefused() {
        byte[] one = "a".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", one));

        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", one)), expected, "sha256:" + "0".repeat(64)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.BUNDLE_DIGEST_MISMATCH);
    }

    @Test
    @DisplayName("every unsafe logical path shape is refused")
    void unsafePathsAreRefused() {
        // The whole matrix, because these are the paths that get joined onto a staging root. A control-plane
        // defect must not become a traversal on this host.
        for (String path : List.of(
                "../escape", "a/../../escape", "/absolute", "C:/drive", "back\\slash", ".", "..",
                "double//slash", "trailing/", "control\u0001char", "nul\u0000byte", "")) {
            assertThatThrownBy(() -> SourceBundle.requireSafePaths(List.of(path)))
                    .as("path %s", path.replace('\u0000', '?'))
                    .isInstanceOf(SourceBundleRejected.class);
        }
        // Collisions that silently LOSE a feature rather than failing.
        assertThatThrownBy(() -> SourceBundle.requireSafePaths(List.of("a.feature", "a.feature")))
                .isInstanceOf(SourceBundleRejected.class);
        assertThatThrownBy(() -> SourceBundle.requireSafePaths(List.of("A.feature", "a.feature")))
                .isInstanceOf(SourceBundleRejected.class);
        assertThatThrownBy(() -> SourceBundle.requireSafePaths(List.of("dir", "dir/child")))
                .isInstanceOf(SourceBundleRejected.class);

        // Anti-vacuity: ordinary paths are accepted, so the assertions above are about those shapes and not
        // about a method that refuses everything.
        SourceBundle.requireSafePaths(List.of("features/a.feature", "features/nested/b.feature"));
    }

    @Test
    @DisplayName("an oversized entry is refused while reading, not after buffering it")
    void anOversizedEntryIsRefused() {
        byte[] huge = new byte[(int) SourceBundleContract.MAX_ENTRY_BYTES + 1024];
        var expected = List.of(expect("a.feature", huge));

        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", huge)), expected, SourceBundle.bundleDigest(expected)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("the aggregate digest is canonical and content-sensitive")
    void theAggregateDigestIsCanonical() {
        byte[] one = "a".getBytes(StandardCharsets.UTF_8);
        byte[] two = "b".getBytes(StandardCharsets.UTF_8);
        // Order must not matter: the digest sorts by path, so a bundle assembled either way is one bundle.
        assertThat(SourceBundle.bundleDigest(List.of(expect("a", one), expect("b", two))))
                .isEqualTo(SourceBundle.bundleDigest(List.of(expect("b", two), expect("a", one))));
        // And a different set is a different identity.
        assertThat(SourceBundle.bundleDigest(List.of(expect("a", one))))
                .isNotEqualTo(SourceBundle.bundleDigest(List.of(expect("a", two))));
    }
}
