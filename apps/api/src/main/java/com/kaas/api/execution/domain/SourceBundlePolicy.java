package com.kaas.api.execution.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles the immutable feature sources one run's snapshot names, and digests what it assembled.
 *
 * <p>The server constructs this archive from its own database rows. It never extracts a user-supplied archive,
 * which removes the entire zip-slip family of problems by construction rather than by defending against them.
 * What remains is the risk that a stored logical path is hostile, and that is defended here even though the same
 * paths were validated when the feature revision was created — the boundary that builds a filesystem layout is
 * the right place to refuse a path that would escape one, and defence in depth means not assuming the earlier
 * check is still there.
 *
 * <h2>What the digest covers</h2>
 *
 * <p>The bundle digest is a <strong>canonical semantic digest</strong>, not a digest of the archive bytes. It
 * covers the set of logical paths and the content digest of each, in sorted order. It deliberately does not
 * cover the ZIP framing: entry ordering as written, compression method, extra fields, or timestamps.
 *
 * <p>That choice is stated rather than assumed because the alternative is a trap. Digesting the bytes would make
 * the value depend on the exact behaviour of whichever ZIP implementation happened to produce it, so a JDK
 * upgrade could change the digest of a bundle whose contents had not changed by a single byte. A consumer
 * verifying this digest must therefore verify it the same way — parse the archive, take the paths and contents,
 * and canonicalize — rather than hashing the file it received and expecting a match.
 *
 * <p>The archive is nonetheless written deterministically, so that the same snapshot produces byte-identical
 * output on the same runtime: entries sorted by path, fixed timestamps, and a fixed compression method.
 */
public final class SourceBundlePolicy {
    /** The bundle format this build produces. Shared with the runner through the api-contracts file. */
    public static final String FORMAT = "kaas.source-bundle.v1";

    /**
     * A fixed local timestamp for every entry.
     *
     * <p>{@code setTimeLocal}, not {@code setTime}. The latter takes epoch millis and converts through
     * {@code ZoneId.systemDefault()}, so it makes the archive depend on the machine's timezone. The previous
     * constant only appeared to avoid that: it happened to land before 1980, so the JDK wrote the DOS sentinel
     * and the timezone dropped out by accident. Any post-1980 value would have made identical content produce
     * different bytes in different timezones, and the byte-stability test — two archives in one JVM — could not
     * have noticed.
     */
    private static final LocalDateTime FIXED_ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    /** The same instant for the extended timestamp field, expressed where no timezone can reach it. */
    private static final long FIXED_EPOCH_MILLIS = 315_532_800_000L;

    /** The same ceiling the snapshot itself enforces, restated so the bundle cannot exceed what it describes. */
    public static final int MAX_FEATURES = 1000;

    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /**
     * One entry's ceiling, restated here rather than left to the database.
     *
     * <p>Deliberately looser than the {@code feature_revisions} column constraint. This is not that check
     * repeated; it is the delivery boundary refusing to hand over an entry the runner would refuse to
     * materialise, so a corrupted or migrated row cannot produce a bundle that dies inside a sandbox instead
     * of at the boundary that assembled it.
     */
    public static final long MAX_ENTRY_BYTES = 1024L * 1024;

    /** A logical path. Bounds what any filesystem this is written into has to accommodate. */
    public static final int MAX_PATH_LENGTH = 512;

    private SourceBundlePolicy() {}

    /**
     * Refuses any logical path that could escape, collide, or confuse a filesystem it is written into.
     *
     * @throws IllegalArgumentException naming the property that failed, never the offending value, so a hostile
     *     path cannot ride an error message into a log
     */
    public static void requireSafePaths(List<String> logicalPaths) {
        if (logicalPaths.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("A bundle carries at most " + MAX_FEATURES + " sources.");
        }
        // Two collision families that exact-match dedup misses, both of which silently LOSE files rather than
        // failing: paths differing only by case collapse onto one file on macOS and Windows, and a path that is
        // a directory prefix of another cannot be written at all once the shorter one exists as a file. Both are
        // reachable through the public API today — demonstrated with four features, of which two survived
        // extraction. A worker verifying the bundle digest against what it extracted would fail; one that did
        // not would run a suite quietly missing tests.
        Set<String> seen = new HashSet<>();
        Set<String> foldedPaths = new HashSet<>();
        for (String path : logicalPaths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A bundle entry must have a path.");
            }
            if (path.length() > MAX_PATH_LENGTH) {
                throw new IllegalArgumentException("A bundle entry path is bounded.");
            }
            if (path.indexOf('\0') >= 0) {
                // A NUL truncates the path in any C-based filesystem call, so what a checker sees and what the
                // kernel acts on are different strings.
                throw new IllegalArgumentException("A bundle entry path must not contain NUL.");
            }
            if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) {
                throw new IllegalArgumentException("A bundle entry path must be relative and use forward slashes.");
            }
            if (path.contains("//") || path.endsWith("/")) {
                throw new IllegalArgumentException("A bundle entry path must name a file.");
            }
            // Drive letters and UNC prefixes are absolute on Windows even though they do not begin with a slash.
            if (path.length() > 1 && path.charAt(1) == ':') {
                throw new IllegalArgumentException("A bundle entry path must be relative.");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException("A bundle entry path must not traverse.");
                }
            }
            if (path.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A bundle entry path must not contain control characters.");
            }
            if (!seen.add(path)) {
                // Two entries at one path means the archive's meaning depends on extraction order.
                throw new IllegalArgumentException("A bundle entry path must be unique.");
            }
            // Encodable as UTF-8 without substitution. A lone surrogate passes every check above and then
            // crashes the archive writer mid-entry, leaving a half-written file.
            try {
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(path));
            } catch (CharacterCodingException notEncodable) {
                throw new IllegalArgumentException("A bundle entry path must be encodable as UTF-8.");
            }
            if (!foldedPaths.add(Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("A bundle entry path must be unique when case is folded.");
            }
        }
        for (String path : logicalPaths) {
            for (String other : logicalPaths) {
                if (!path.equals(other) && other.startsWith(path + "/")) {
                    throw new IllegalArgumentException("A bundle entry path must not be a directory of another.");
                }
            }
        }
    }

    /**
     * The canonical semantic digest of a bundle's contents.
     *
     * <p>Sorted by path, length-prefixed, and covering the count as well as the entries, so no rearrangement or
     * concatenation of one bundle's fields can produce another's digest.
     */
    public static String digest(List<BundleEntry> entries) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, FORMAT);
            update(sha, "ENTRY_COUNT");
            update(sha, Integer.toString(entries.size()));
            for (BundleEntry entry : entries.stream().sorted(Comparator.comparing(BundleEntry::logicalPath)).toList()) {
                update(sha, "ENTRY");
                update(sha, entry.logicalPath());
                update(sha, entry.contentDigest());
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** A deterministic ZIP of the given entries. Same input, same bytes. */
    public static byte[] archive(List<BundleEntry> entries) {
        requireSafePaths(entries.stream().map(BundleEntry::logicalPath).toList());
        for (BundleEntry entry : entries) {
            if (entry.content().length > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("A bundle entry is bounded in size.");
            }
        }
        long total = entries.stream().mapToLong(entry -> entry.content().length).sum();
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("A bundle is bounded in total size.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            // STORED rather than DEFLATE: the deflate level and strategy are implementation details that could
            // change between runtimes, and these are small text files where compression buys little.
            zip.setMethod(ZipOutputStream.STORED);
            for (BundleEntry entry : entries.stream().sorted(Comparator.comparing(BundleEntry::logicalPath)).toList()) {
                ZipEntry zipEntry = new ZipEntry(entry.logicalPath());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.content().length);
                zipEntry.setCompressedSize(entry.content().length);
                CRC32 crc = new CRC32();
                crc.update(entry.content());
                zipEntry.setCrc(crc.getValue());
                // Fixed times, set through the DOS field so no timezone or clock reaches the archive.
                // Both time representations, because the writer emits both. setTimeLocal fixes the DOS field
                // without a timezone conversion; setLastModifiedTime fixes the extended "UT" field, which is
                // written from an epoch value and would otherwise be derived through the default zone. Fixing
                // only one leaves the archive timezone-dependent through the other, which is exactly what the
                // previous version did without anyone noticing.
                zipEntry.setTimeLocal(FIXED_ENTRY_TIME);
                zipEntry.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(FIXED_EPOCH_MILLIS));
                zip.putNextEntry(zipEntry);
                zip.write(entry.content());
                zip.closeEntry();
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("Writing to memory cannot fail", impossible);
        }
        return bytes.toByteArray();
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** One file in the bundle: where it goes, what it contains, and the digest the snapshot recorded for it. */
    public record BundleEntry(String logicalPath, String contentDigest, byte[] content) {
        public BundleEntry {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
