package com.kaas.runner.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Removes tenant source a crashed runner left on this host.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It looks in one directory: the operator-configured staging root, and nothing above or beside it. Inside
 * it, it considers only directories whose names carry the staging prefix. It never matches on a tenant name,
 * never walks a symlink out of the root, and has no path that could reach {@code /tmp} at large or "every old
 * directory". A reconciler that guesses is a reconciler that eventually deletes something it should not.
 *
 * <h2>Why an age rather than a lock</h2>
 *
 * <p>Several executions can share a host, and one execution's cleanup must never remove another's live
 * bundle. The grace period is what separates them: a directory younger than it may belong to a run that is
 * still provisioning, and is left alone. Being early here destroys live work, which is worse than leaving
 * bytes for another few minutes — and the normal path removes them the moment its execution ends anyway.
 */
public final class StaleSourceReconciler {

    /**
     * How long a staged bundle must have gone untouched before another process reclaims it.
     *
     * <p>Longer than the longest execution deadline plus the time a healthy runner takes to clean up. The
     * cost of being generous is tenant bytes living slightly longer on a host that already held them; the
     * cost of being eager is deleting a running execution's source out from under it.
     */
    static final Duration ABANDONMENT_GRACE = Duration.ofMinutes(30);

    private final Path stagingRoot;
    private final Duration grace;

    public StaleSourceReconciler(Path stagingRoot) {
        this(stagingRoot, ABANDONMENT_GRACE);
    }

    StaleSourceReconciler(Path stagingRoot, Duration grace) {
        this.stagingRoot = stagingRoot;
        this.grace = grace;
    }

    /**
     * Removes staged bundles older than the grace period.
     *
     * @return how many were removed
     */
    public int reclaim(Instant now) {
        if (stagingRoot == null || !Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        List<Path> stale = new ArrayList<>();
        try (var entries = Files.list(stagingRoot)) {
            entries.forEach(candidate -> {
                if (!isReclaimable(candidate, now)) {
                    return;
                }
                stale.add(candidate);
            });
        } catch (IOException unreadable) {
            return 0;
        }
        int removed = 0;
        for (Path directory : stale) {
            if (delete(directory)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Whether one entry of the staging root is a staged bundle old enough to reclaim.
     *
     * <p>Every condition is necessary. It must be a real directory rather than a symlink pointing somewhere
     * else; it must carry the prefix this platform writes, so an operator's own file in the same root is left
     * alone; and it must be older than the grace period, so a live execution's bundle is not taken from it.
     */
    private boolean isReclaimable(Path candidate, Instant now) {
        try {
            if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!candidate.getFileName().toString().startsWith(SourceStaging.DIRECTORY_PREFIX)) {
                return false;
            }
            Instant modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS).toInstant();
            return modified.isBefore(now.minus(grace));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private boolean delete(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A file that cannot be removed is reported by the next pass rather than thrown here.
                }
            });
            return !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            return false;
        }
    }
}
