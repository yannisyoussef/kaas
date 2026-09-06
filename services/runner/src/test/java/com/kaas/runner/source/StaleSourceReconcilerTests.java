package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stale tenant source is reclaimed; live source, other executions' source, and everything else is not.
 *
 * <p>The dangerous failure of a cleaner is not that it misses something. It is that it removes something it
 * should not — a running execution's bundle, an operator's own file, or a directory outside the root it was
 * given. Each of those is asserted here rather than argued about.
 */
@DisplayName("Stale source reconciler")
class StaleSourceReconcilerTests {

    private static Path aged(Path directory, Duration age) throws Exception {
        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(age)));
        return directory;
    }

    @Test
    @DisplayName("a bundle a crashed runner left behind is removed")
    void staleSourceIsReclaimed() throws Exception {
        Path root = Files.createTempDirectory("kaas-reconcile-");
        Path stale = aged(
                Files.createDirectory(root.resolve(SourceStaging.DIRECTORY_PREFIX + "abandoned")),
                Duration.ofHours(2));
        Files.writeString(stale.resolve("manifest.tsv"), "tenant bytes");
        aged(stale, Duration.ofHours(2));

        assertThat(new StaleSourceReconciler(root).reclaim(Instant.now())).isOne();
        assertThat(Files.exists(stale)).as("no tenant source persists indefinitely").isFalse();
    }

    @Test
    @DisplayName("a live execution's bundle is left alone")
    void liveSourceIsRetained() throws Exception {
        // The failure that matters. Several executions share a host, and reclaiming one that is merely young
        // would delete source out from under a run that is still provisioning.
        Path root = Files.createTempDirectory("kaas-reconcile-");
        Path live = Files.createDirectory(root.resolve(SourceStaging.DIRECTORY_PREFIX + "running"));

        assertThat(new StaleSourceReconciler(root).reclaim(Instant.now())).isZero();
        assertThat(Files.exists(live)).isTrue();
    }

    @Test
    @DisplayName("anything that is not a staged bundle is left alone, however old")
    void unrelatedFilesAreRetained() throws Exception {
        // An operator's own directory in the same root, and a file. Both older than any grace period. A
        // cleaner that matched on age alone would take both.
        Path root = Files.createTempDirectory("kaas-reconcile-");
        Path unrelated = aged(Files.createDirectory(root.resolve("operator-notes")), Duration.ofDays(30));
        Path file = root.resolve("README");
        Files.writeString(file, "not a bundle");
        aged(file, Duration.ofDays(30));

        assertThat(new StaleSourceReconciler(root).reclaim(Instant.now())).isZero();
        assertThat(Files.exists(unrelated)).isTrue();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("a symlink into somebody else's directory is never followed")
    void symlinksAreNotFollowed() throws Exception {
        // A link named like a bundle, pointing at a directory the reconciler has no business touching. It is
        // old enough to reclaim by every other rule.
        Path root = Files.createTempDirectory("kaas-reconcile-");
        Path elsewhere = Files.createTempDirectory("kaas-elsewhere-");
        Files.writeString(elsewhere.resolve("important"), "not ours");
        Path link = root.resolve(SourceStaging.DIRECTORY_PREFIX + "link");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return; // A platform without symlinks cannot exhibit the hazard.
        }
        // AGED, AND AGED ON THE LINK ITSELF. Without this the link's own timestamp is current and the grace
        // period alone declines it -- so the test passed while the symlink guard was removed, which is how a
        // mutation found it.
        //
        // Through the attribute view with NOFOLLOW_LINKS, not Files.setLastModifiedTime, which follows the
        // link and ages the TARGET instead. That first fix looked right, still left the link's own time
        // current, and the mutation survived a second time -- so the age is read back below rather than
        // assumed, and every other rule now says "reclaim this" with the guard as the only thing refusing.
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(30)));
        Files.getFileAttributeView(link, java.nio.file.attribute.BasicFileAttributeView.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .setTimes(old, null, null);
        assertThat(Files.getLastModifiedTime(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("the link itself must be old, or this test proves nothing about the symlink guard")
                .isEqualTo(old);

        assertThat(new StaleSourceReconciler(root).reclaim(Instant.now())).isZero();
        assertThat(Files.exists(elsewhere.resolve("important")))
                .as("the reconciler must never delete through a link")
                .isTrue();
    }

    @Test
    @DisplayName("a root that does not exist is not an error, and reclaims nothing")
    void anAbsentRootIsHarmless() {
        assertThat(new StaleSourceReconciler(Path.of("/nonexistent/kaas/staging")).reclaim(Instant.now()))
                .isZero();
    }
}
