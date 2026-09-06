package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.source.SourceBundle.ExpectedEntry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    @DisplayName("verification applies the path rules, not only the rule method")
    void verificationAppliesThePathRules() {
        // THE GAP A MUTATION FOUND. Every assertion above calls requireSafePaths directly, so deleting the
        // call from verified() -- the only entry point production uses -- changed nothing that any test
        // could see. A rule enforced by a method nobody is proven to call is not enforced.
        //
        // Driven through verified() with a traversing path in the COMMAND's own feature list, which is the
        // shape a control-plane defect would produce and the one that would otherwise be joined onto a
        // staging root.
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var traversing = List.of(expect("../escape.feature", content));

        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("../escape.feature", content)),
                        traversing,
                        SourceBundle.bundleDigest(traversing)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.UNSAFE_PATH);

        // The same, for a collision that loses a feature silently rather than escaping anywhere. Two shapes
        // rather than one, because a call site could be restored for traversal alone and still skip the rest.
        var colliding = List.of(expect("a.feature", content), expect("A.feature", content));
        assertThatThrownBy(() -> SourceBundle.verified(
                        archiveOf(entries("a.feature", content, "A.feature", content)),
                        colliding,
                        SourceBundle.bundleDigest(colliding)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.UNSAFE_PATH);

        // Anti-vacuity: the same route with ordinary paths succeeds, so the refusals above are about the
        // paths and not about verified() refusing whatever it is given.
        var ordinary = List.of(expect("features/a.feature", content));
        assertThat(SourceBundle.verified(
                                archiveOf(entries("features/a.feature", content)),
                                ordinary,
                                SourceBundle.bundleDigest(ordinary))
                        .contents())
                .containsOnlyKeys("features/a.feature");
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
    @DisplayName("a bundle is refused while reading when its entries aggregate past the ceiling")
    void anOversizedAggregateIsRefusedWhileReading() {
        // MEASURED AT THE READ, not at the match. Mutation testing found this: the per-entry ceiling and the
        // aggregate accounting in verified() both survive removing the reader's own total, because every
        // oversized case the suite had was also caught later. But "later" is after the whole archive is in
        // memory, and an archive of individually legal entries is exactly how a peer would get there.
        //
        // Driven with an EMPTY authorized set on purpose. The later accounting only runs over entries the
        // command named, so with nothing named it cannot fire -- which makes this test fail for the reader's
        // reason or not at all.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] oneMebibyte = new byte[(int) SourceBundleContract.MAX_ENTRY_BYTES];
        for (int i = 0; i <= SourceBundleContract.MAX_TOTAL_BYTES / SourceBundleContract.MAX_ENTRY_BYTES; i++) {
            entries.put("features/" + i + ".feature", oneMebibyte);
        }

        assertThatThrownBy(() -> SourceBundle.verified(archiveOf(entries), List.of(), "sha256:unused"))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("a bundle is refused while reading when it carries more entries than permitted")
    void anOverfullArchiveIsRefusedWhileReading() {
        // The same shape as above, for entry count rather than bytes, and for the same reason: the check in
        // verified() bounds what the COMMAND named, which says nothing about how much a peer can make this
        // buffer before the command is consulted.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] tiny = "x".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= SourceBundleContract.MAX_ENTRIES; i++) {
            entries.put("features/" + i + ".feature", tiny);
        }

        assertThatThrownBy(() -> SourceBundle.verified(archiveOf(entries), List.of(), "sha256:unused"))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("an archive repeating an entry name is refused rather than resolved")
    void aRepeatedEntryNameIsRefused() {
        // Without this the last copy silently wins, the set comparison still balances, and the digests still
        // match -- so an archive that is ambiguous about what it contains is accepted as though it were not.
        // A bundle whose meaning depends on which copy a reader picked is not a bundle this platform accepts,
        // even when both copies happen to agree.
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(expect("a.feature", content));

        assertThatThrownBy(() -> SourceBundle.verified(
                        repeatedArchive("a.feature", content), expected, SourceBundle.bundleDigest(expected)))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.UNSAFE_PATH);
    }

    @Test
    @DisplayName("entries are read under a bound rather than buffered and then measured")
    void entriesAreReadUnderABound() throws Exception {
        // A STRUCTURAL CLAIM, and it is here because no behavioural test can make it.
        //
        // Replacing the bounded read with readAllBytes() leaves every rejection in this suite intact: the
        // length check on the next line still refuses the entry. What changes is that the bytes are in memory
        // first, which is precisely what the bound exists to prevent and which a passing test cannot
        // distinguish from a failing one. Reading the source is the honest way to assert it.
        String source = Files.readString(bundleSource());

        assertThat(source)
                .as("an unbounded read makes the entry ceiling a report rather than a limit")
                .doesNotContain("readAllBytes");
        assertThat(source).contains("readNBytes");
    }

    private static java.nio.file.Path bundleSource() {
        java.nio.file.Path fromModule =
                java.nio.file.Path.of("src/main/java/com/kaas/runner/source/SourceBundle.java");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : java.nio.file.Path.of("services/runner/src/main/java/com/kaas/runner/source/SourceBundle.java");
    }

    /**
     * An archive carrying the same entry name twice.
     *
     * <p>{@code ZipOutputStream} refuses to write one, which is the whole point: a well-behaved writer cannot
     * produce this and a hostile or broken peer is under no such constraint. Built by writing two entries
     * whose names are the same length and whose contents are identical, then renaming the second in the raw
     * bytes -- so every offset, size and CRC in the archive stays correct and only the name repeats.
     */
    private static byte[] repeatedArchive(String path, byte[] content) {
        String other = "z".repeat(path.length());
        byte[] archive = archiveOf(entries(path, content, other, content));
        byte[] from = other.getBytes(StandardCharsets.UTF_8);
        byte[] to = path.getBytes(StandardCharsets.UTF_8);
        int replaced = 0;
        for (int i = 0; i + from.length <= archive.length; i++) {
            if (java.util.Arrays.equals(archive, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, archive, i, to.length);
                replaced++;
            }
        }
        // The name appears in the local header and again in the central directory. Asserted rather than
        // assumed: a rename that missed one would produce a malformed archive, and MALFORMED is a different
        // refusal from the one this test is about.
        if (replaced != 2) {
            throw new IllegalStateException("Expected two occurrences of the entry name, found " + replaced);
        }
        return archive;
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
