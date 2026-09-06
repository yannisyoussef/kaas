package com.kaas.runner.source;

import com.kaas.runner.source.SourceBundleRejected.Reason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant source bytes, on this host, in a place the platform chose.
 *
 * <h2>What it will and will not create</h2>
 *
 * <p>Regular files, and platform-owned directories. Nothing else is reachable: the materialiser writes bytes
 * with {@link StandardOpenOption#CREATE_NEW}, so it cannot follow or overwrite anything that already exists,
 * and it has no code path that creates a symlink, a hard link, a FIFO, a socket or a device node. That is a
 * stronger statement than validating a bundle for those things, because the bundle format cannot express them
 * either — an entry is a path and bytes.
 *
 * <p>Modes are platform-owned. Tenant source carries bytes, not permissions: every file is written read-only
 * to its owner, with no executable, setuid or setgid bit, whatever the transport happened to contain.
 *
 * <h2>Ownership and cleanup</h2>
 *
 * <p>{@link AutoCloseable}, and the only way to create one is to stage a bundle. Closing removes the whole
 * directory. Tenant bytes therefore live exactly as long as the try-with-resources that owns them, on every
 * path including failure — rather than depending on a caller remembering a cleanup call on each branch.
 */
public final class SourceStaging implements AutoCloseable {

    /** Read for the owner only. No group, no world, and no executable bit anywhere. */
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("r--------");

    /** Traversable and writable only by the owner, so nothing else on the host can add to it. */
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");

    /** Names a KaaS source bundle directory, so a reconciler can recognise one without guessing. */
    public static final String DIRECTORY_PREFIX = "kaas-source-";

    private final Path root;

    private SourceStaging(Path root) {
        this.root = root;
    }

    /**
     * Materialises a verified bundle under a fresh directory of the given root.
     *
     * <p>The directory name is an opaque identifier. It carries no tenant data — not a project, not a feature
     * name, not a path — because a directory listing on a shared host is readable by anyone who can list it.
     *
     * @param stagingRoot the operator-configured root. Platform-owned, never tenant-selected.
     */
    public static SourceStaging materialise(Path stagingRoot, SourceBundle bundle) {
        Path root = stagingRoot.resolve(DIRECTORY_PREFIX + UUID.randomUUID());
        SourceStaging staging = new SourceStaging(root);
        try {
            Files.createDirectories(stagingRoot);
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            Path files = root.resolve(SourceBundleContract.FILES_DIRECTORY);
            Files.createDirectory(files, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));

            StringBuilder manifest = new StringBuilder();
            manifest.append(SourceBundleContract.FORMAT_VERSION)
                    .append('\t')
                    .append(bundle.bundleDigest())
                    .append('\t')
                    .append(bundle.contents().size())
                    .append('\n');
            for (Map.Entry<String, byte[]> entry : bundle.contents().entrySet()) {
                Path target = resolveWithin(files, entry.getKey());
                Files.createDirectories(target.getParent(), PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
                // CREATE_NEW: if anything already exists at this path -- including a symlink somebody raced
                // into place -- the write fails rather than following it.
                Files.write(target, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(target, FILE_MODE);
                manifest.append(entry.getKey())
                        .append('\t')
                        .append(SourceBundle.sha256(entry.getValue()))
                        .append('\t')
                        .append(entry.getValue().length)
                        .append('\n');
            }
            Path manifestFile = root.resolve(SourceBundleContract.MANIFEST_NAME);
            Files.write(
                    manifestFile,
                    manifest.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(manifestFile, FILE_MODE);
            return staging;
        } catch (IOException | RuntimeException failure) {
            // Nothing half-written survives a failure. A partially staged bundle is tenant bytes on a host
            // that no execution owns.
            staging.close();
            if (failure instanceof SourceBundleRejected rejected) {
                throw rejected;
            }
            throw new SourceBundleRejected(Reason.STAGING_FAILED, "A bundle could not be staged.");
        }
    }

    /**
     * Joins a logical path onto the files root and proves the result is still inside it.
     *
     * <p>The path has already been checked for traversal, and this checks the outcome anyway. The rules
     * operate on the string; this operates on the resolved path, which is what the filesystem will act on —
     * and the two can differ for reasons a string check cannot see.
     */
    private static Path resolveWithin(Path filesRoot, String logicalPath) throws IOException {
        Path base = filesRoot.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
        Path resolved = base.resolve(logicalPath).normalize();
        if (!resolved.startsWith(base)) {
            throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry resolved outside its root.");
        }
        return resolved;
    }

    /** The directory to mount. */
    public Path root() {
        return root;
    }

    /**
     * Removes the staged bundle.
     *
     * <p>Deletes only inside its own directory, depth-first, and never follows a link out of it. Idempotent,
     * because it runs on paths that may already have cleaned up.
     */
    @Override
    public void close() {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Reported by the caller's leak assertions rather than thrown here: a cleanup failure must
                    // not replace the outcome of the execution that owned it.
                }
            });
        } catch (IOException unreadable) {
            // Same reasoning.
        }
    }
}
