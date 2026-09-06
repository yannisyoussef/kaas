package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.execution.domain.SourceBundlePolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What may go into a source bundle, and what its digest is a statement about. */
class SourceBundlePolicyTest {

    @ParameterizedTest(name = "a path that is {0} is refused")
    @ValueSource(
            strings = {
                "/etc/passwd",
                "../outside.feature",
                "features/../../outside.feature",
                "features/./same.feature",
                "features//double.feature",
                "features/",
                "C:/windows/system32",
                "features\\windows.feature",
                "",
                ".",
                ".."
            })
    void aPathThatCouldEscapeAFilesystemIsRefused(String path) {
        // These were validated once already, when the feature revision was created. They are validated again
        // here because the boundary that builds a filesystem layout is the right place to refuse a path that
        // would escape one, and because assuming the earlier check is still there is how it stops being there.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(List.of(path)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPathContainingNulIsRefused() {
        // A NUL truncates the path in any C-based filesystem call, so what a checker sees and what the kernel
        // acts on are different strings.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(List.of("features/a\u0000.feature")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoEntriesAtOnePathAreRefused() {
        // Otherwise the archive's meaning would depend on the order something extracted it in.
        assertThatThrownBy(() ->
                        SourceBundlePolicy.requireSafePaths(List.of("features/a.feature", "features/a.feature")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathsThatCollideWhenCaseIsFoldedAreRefused() {
        // Demonstrated data loss, not theory: four features created through the public API, all accepted, all in
        // one snapshot — and two of the four vanished on extraction. features/collide.feature and
        // Features/COLLIDE.feature collapse onto one file on macOS and Windows, and which content survives
        // depends on write order. Exact-match dedup could not see it.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(
                        List.of("features/collide.feature", "Features/COLLIDE.feature")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPathThatIsADirectoryOfAnotherIsRefused() {
        // shared/x.feature cannot exist as a file and a directory at once, so one of the two entries is simply
        // not written. The archive silently carries less than the snapshot pinned.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(
                        List.of("shared/x.feature", "shared/x.feature/y.feature")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPathThatIsNotEncodableAsUtf8IsRefused() {
        // A lone surrogate passed every structural check and then crashed the archive writer mid-entry, leaving
        // a half-written file behind.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(List.of("features/\uD800.feature")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theArchiveIsByteIdenticalRegardlessOfTheDefaultTimeZone() {
        // The previous byte-stability was an accident. setTime() converts epoch millis through the system
        // timezone; the old constant only avoided that by happening to land before 1980, where the JDK writes a
        // sentinel and the timezone drops out. Any post-1980 value would have made identical content produce
        // different bytes on different machines, and a test comparing two archives in one JVM could not notice.
        var entries = List.of(entry("features/a.feature", "sha256:aaa", "Feature: a\n"));
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            byte[] inUtc = SourceBundlePolicy.archive(entries);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertThat(SourceBundlePolicy.archive(entries)).isEqualTo(inUtc);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void theBundleCeilingsAreEnforcedRatherThanMerelyDeclared() {
        // Both constants had zero coverage: removing either left the whole suite green.
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, SourceBundlePolicy.MAX_FEATURES)
                .mapToObj(index -> "features/f" + index + ".feature")
                .toList();
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(tooMany))
                .isInstanceOf(IllegalArgumentException.class);

        // The AGGREGATE ceiling, built from entries that each pass the per-entry one. Two 32 MiB entries used
        // to stand here and proved less than they appeared to: each of them also exceeded the per-entry
        // ceiling, so the per-entry check could be deleted without this noticing -- and a mutation showed it.
        byte[] atTheEntryCeiling = new byte[(int) SourceBundlePolicy.MAX_ENTRY_BYTES];
        List<SourceBundlePolicy.BundleEntry> justOverTheAggregate = java.util.stream.IntStream.rangeClosed(
                        0, (int) (SourceBundlePolicy.MAX_TOTAL_BYTES / SourceBundlePolicy.MAX_ENTRY_BYTES))
                .mapToObj(index -> new SourceBundlePolicy.BundleEntry(
                        "features/f" + index + ".feature", "sha256:aaa", atTheEntryCeiling))
                .toList();
        assertThatThrownBy(() -> SourceBundlePolicy.archive(justOverTheAggregate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneOversizedEntryIsRefusedEvenWhenTheBundleIsSmall() {
        // The PER-ENTRY ceiling on its own, well inside the aggregate one, so only the entry check can refuse
        // it. This is the delivery boundary declining to hand over something the runner would refuse to
        // materialise: the database's column constraint normally prevents it, and "normally" is not a check.
        byte[] overTheEntryCeiling = new byte[(int) SourceBundlePolicy.MAX_ENTRY_BYTES + 1];

        assertThatThrownBy(() -> SourceBundlePolicy.archive(List.of(
                        new SourceBundlePolicy.BundleEntry(
                                "features/a.feature", "sha256:aaa", overTheEntryCeiling))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry");

        // Anti-vacuity: one byte under, and the same call produces an archive.
        assertThat(SourceBundlePolicy.archive(List.of(new SourceBundlePolicy.BundleEntry(
                        "features/a.feature", "sha256:aaa", new byte[(int) SourceBundlePolicy.MAX_ENTRY_BYTES]))))
                .isNotEmpty();
    }

    @Test
    void anOrdinaryNestedPathIsAccepted() {
        // Without this the tests above would be satisfied by a validator that refused everything.
        SourceBundlePolicy.requireSafePaths(List.of("features/login/happy-path.feature", "support/hooks.feature"));
    }

    @Test
    void theRefusalNamesThePropertyRatherThanTheOffendingPath() {
        // A hostile path must not ride an error message into a log line.
        assertThatThrownBy(() -> SourceBundlePolicy.requireSafePaths(List.of("../../etc/shadow")))
                .hasMessageNotContaining("etc")
                .hasMessageNotContaining("..");
    }

    @Test
    void theDigestCoversContentsRatherThanOrdering() {
        var first = entry("features/b.feature", "sha256:bbb");
        var second = entry("features/a.feature", "sha256:aaa");

        assertThat(SourceBundlePolicy.digest(List.of(first, second)))
                .isEqualTo(SourceBundlePolicy.digest(List.of(second, first)));
    }

    @Test
    void theDigestChangesWhenContentChanges() {
        var original = List.of(entry("features/a.feature", "sha256:aaa"));
        var edited = List.of(entry("features/a.feature", "sha256:zzz"));
        var renamed = List.of(entry("features/renamed.feature", "sha256:aaa"));

        assertThat(SourceBundlePolicy.digest(original)).isNotEqualTo(SourceBundlePolicy.digest(edited));
        assertThat(SourceBundlePolicy.digest(original)).isNotEqualTo(SourceBundlePolicy.digest(renamed));
    }

    @Test
    void theDigestCannotBeForgedByRearrangingFieldBoundaries() {
        // Length-prefixing is what stops ("ab","c") and ("a","bc") producing one digest. Without it a path and
        // a digest could be shifted across their boundary to impersonate a different bundle.
        assertThat(SourceBundlePolicy.digest(List.of(entry("ab", "sha256:c"))))
                .isNotEqualTo(SourceBundlePolicy.digest(List.of(entry("a", "sha256:bc"))));
    }

    @Test
    void theArchiveIsByteIdenticalForTheSameContent() {
        // No timestamps, no ordering drift, no compression-level dependence. The same snapshot must produce the
        // same bytes, or a worker comparing two downloads would see a difference that means nothing.
        var entries = List.of(
                entry("features/b.feature", "sha256:bbb", "Feature: b\n"),
                entry("features/a.feature", "sha256:aaa", "Feature: a\n"));

        assertThat(SourceBundlePolicy.archive(entries)).isEqualTo(SourceBundlePolicy.archive(entries));
        // And the same regardless of the order it was handed the entries in.
        assertThat(SourceBundlePolicy.archive(entries))
                .isEqualTo(SourceBundlePolicy.archive(List.of(entries.get(1), entries.get(0))));
    }

    @Test
    void theArchiveContainsExactlyTheEntriesItWasGiven() throws Exception {
        var entries = List.of(
                entry("features/a.feature", "sha256:aaa", "Feature: a\nScenario: one\n"),
                entry("support/hooks.feature", "sha256:bbb", "Feature: hooks\n"));

        List<String> names = new ArrayList<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(SourceBundlePolicy.archive(entries)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).startsWith("Feature:");
            }
        }
        assertThat(names).containsExactly("features/a.feature", "support/hooks.feature");
    }

    @Test
    void anArchiveWithATraversingPathIsRefusedAtTheArchiveBoundaryToo() {
        // Not only at the digest boundary. A caller that skipped the explicit path check still cannot write one.
        assertThatThrownBy(() ->
                        SourceBundlePolicy.archive(List.of(entry("../escape.feature", "sha256:aaa", "Feature: x\n"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SourceBundlePolicy.BundleEntry entry(String path, String digest) {
        return entry(path, digest, "Feature: placeholder\n");
    }

    private static SourceBundlePolicy.BundleEntry entry(String path, String digest, String content) {
        return new SourceBundlePolicy.BundleEntry(path, digest, content.getBytes(StandardCharsets.UTF_8));
    }
}
