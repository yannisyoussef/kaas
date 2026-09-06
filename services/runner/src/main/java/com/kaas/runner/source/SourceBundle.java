package com.kaas.runner.source;

import com.kaas.runner.source.SourceBundleRejected.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A redeemed source bundle, verified against the command that authorized it.
 *
 * <h2>The archive's entry names are not authoritative</h2>
 *
 * <p>This is the property that makes reading an archive here defensible. The runner does not walk the archive
 * and materialise whatever paths it finds; it walks the <strong>command's</strong> feature list — which is
 * platform-authored and covered by the command digest — and looks each expected logical path up in what
 * arrived. An entry the command did not authorize is a refusal, not a file.
 *
 * <p>So the classic archive attacks do not apply in the usual way. A traversing or absolute entry name cannot
 * become a path because no name from the archive is ever used as one; a duplicate cannot win a race because
 * lookup is by expected key; an extra entry is detected rather than extracted. The path rules are still
 * applied to the command's own paths, because a control-plane defect must not become source substitution.
 *
 * <h2>What is deliberately ignored</h2>
 *
 * <p>Every byte of archive metadata: modes, timestamps, external attributes, comments, extra fields. Tenant
 * source carries bytes. It does not carry filesystem permissions, and nothing here can turn it into a file
 * that is executable, setuid, or anything other than a regular file.
 */
public final class SourceBundle {

    private final Map<String, byte[]> contentsByLogicalPath;
    private final String bundleDigest;
    private final long totalBytes;

    private SourceBundle(Map<String, byte[]> contents, String bundleDigest, long totalBytes) {
        this.contentsByLogicalPath = contents;
        this.bundleDigest = bundleDigest;
        this.totalBytes = totalBytes;
    }

    /** One feature the command authorized: where it goes and what it must contain. */
    public record ExpectedEntry(String logicalPath, String contentDigest) {}

    /**
     * Reads a redeemed bundle and verifies it against exactly what the command authorized.
     *
     * @param archive the redeemed bytes
     * @param expected the command's feature list, which is the only source of truth for what belongs here
     * @param expectedBundleDigest the aggregate digest the command authorized
     */
    public static SourceBundle verified(
            byte[] archive, List<ExpectedEntry> expected, String expectedBundleDigest) {

        requireSafePaths(expected.stream().map(ExpectedEntry::logicalPath).toList());
        if (expected.size() > SourceBundleContract.MAX_ENTRIES) {
            throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle carries more entries than permitted.");
        }

        Map<String, byte[]> found = read(archive);

        // EXACTLY the authorized set: no extra, no missing, no duplicate. Checked as a set comparison rather
        // than by iterating one side, because "every expected entry is present" and "every present entry is
        // expected" are different claims and only the pair excludes substitution.
        if (found.size() != expected.size()) {
            throw new SourceBundleRejected(
                    Reason.WRONG_FEATURE_SET, "A bundle carried a different number of entries than authorized.");
        }
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        long total = 0;
        for (ExpectedEntry entry : expected) {
            byte[] content = found.get(entry.logicalPath());
            if (content == null) {
                throw new SourceBundleRejected(
                        Reason.WRONG_FEATURE_SET, "A bundle did not carry an authorized entry.");
            }
            if (content.length > SourceBundleContract.MAX_ENTRY_BYTES) {
                throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle entry exceeded its ceiling.");
            }
            String actual = sha256(content);
            if (!actual.equals(entry.contentDigest())) {
                // The digest the SNAPSHOT recorded, compared against the bytes that actually arrived. This is
                // what stops a control-plane defect substituting one feature's source for another's.
                throw new SourceBundleRejected(
                        Reason.DIGEST_MISMATCH, "A bundle entry did not match its authorized digest.");
            }
            total += content.length;
            if (total > SourceBundleContract.MAX_TOTAL_BYTES) {
                throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle exceeded its aggregate ceiling.");
            }
            ordered.put(entry.logicalPath(), content);
        }

        String digest = bundleDigest(expected);
        if (!digest.equals(expectedBundleDigest)) {
            throw new SourceBundleRejected(
                    Reason.BUNDLE_DIGEST_MISMATCH, "A bundle did not match the digest the command authorized.");
        }
        return new SourceBundle(ordered, digest, total);
    }

    /**
     * The canonical aggregate digest, computed the same way the control plane computes it.
     *
     * <p>Sorted by path, length-prefixed, and covering the count as well as the entries, so no rearrangement
     * or concatenation of one bundle's fields can produce another's. Independently implemented here rather
     * than shared, for the same reason the attestation preimage is: two implementations of one written rule
     * can disagree visibly, and one implementation agreeing with itself proves nothing.
     */
    public static String bundleDigest(List<ExpectedEntry> entries) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, SourceBundleContract.FORMAT_VERSION);
            update(sha, "ENTRY_COUNT");
            update(sha, Integer.toString(entries.size()));
            for (ExpectedEntry entry :
                    entries.stream().sorted(Comparator.comparing(ExpectedEntry::logicalPath)).toList()) {
                update(sha, "ENTRY");
                update(sha, entry.logicalPath());
                update(sha, entry.contentDigest());
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Every entry in the archive, keyed by its declared name, bounded while reading.
     *
     * <p>The names read here are used only as lookup keys against the authorized set. They never reach a
     * filesystem call.
     */
    private static Map<String, byte[]> read(byte[] archive) {
        Map<String, byte[]> found = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    // Nothing in this format needs one, and a directory entry is metadata rather than content.
                    continue;
                }
                if (found.size() >= SourceBundleContract.MAX_ENTRIES) {
                    throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle carried too many entries.");
                }
                // Bounded WHILE READING rather than after. A declared size is the peer's claim; this is what
                // was actually delivered, and stopping here is what keeps a hostile or broken response from
                // consuming memory before any limit is consulted.
                byte[] content = zip.readNBytes((int) SourceBundleContract.MAX_ENTRY_BYTES + 1);
                if (content.length > SourceBundleContract.MAX_ENTRY_BYTES) {
                    throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle entry exceeded its ceiling.");
                }
                total += content.length;
                if (total > SourceBundleContract.MAX_TOTAL_BYTES) {
                    throw new SourceBundleRejected(Reason.TOO_LARGE, "A bundle exceeded its aggregate ceiling.");
                }
                if (found.put(entry.getName(), content) != null) {
                    throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle repeated an entry name.");
                }
            }
        } catch (IOException unreadable) {
            throw new SourceBundleRejected(Reason.MALFORMED, "A bundle could not be read.");
        }
        return found;
    }

    /**
     * The path rules, applied to the paths the COMMAND names.
     *
     * <p>Mirrors the control plane's own rules. Applied here because a control-plane defect must not become a
     * traversal on this host, and because these paths are about to be joined onto a staging root.
     *
     * <p>Messages name the rule and never the offending path: a hostile path in an exception is tenant
     * content in a log.
     */
    static void requireSafePaths(List<String> logicalPaths) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> folded = new java.util.HashSet<>();
        for (String path : logicalPaths) {
            if (path == null || path.isBlank()) {
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry must have a path.");
            }
            if (path.length() > SourceBundleContract.MAX_PATH_LENGTH) {
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path is bounded.");
            }
            if (path.indexOf('\0') >= 0) {
                // A NUL truncates the path in any C-based filesystem call, so what a checker sees and what the
                // kernel acts on are different strings.
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path must not contain NUL.");
            }
            if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) {
                throw new SourceBundleRejected(
                        Reason.UNSAFE_PATH, "A bundle entry path must be relative and use forward slashes.");
            }
            if (path.contains("//") || path.endsWith("/")) {
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path must name a file.");
            }
            if (path.length() > 1 && path.charAt(1) == ':') {
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path must be relative.");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path must not traverse.");
                }
            }
            if (path.codePoints().anyMatch(Character::isISOControl)) {
                throw new SourceBundleRejected(
                        Reason.UNSAFE_PATH, "A bundle entry path must not contain control characters.");
            }
            if (!seen.add(path)) {
                throw new SourceBundleRejected(Reason.UNSAFE_PATH, "A bundle entry path must be unique.");
            }
            if (!folded.add(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC)
                    .toLowerCase(java.util.Locale.ROOT))) {
                // Two paths differing only by case or Unicode normalisation collapse onto one file on some
                // filesystems, which loses a feature silently rather than failing.
                throw new SourceBundleRejected(
                        Reason.UNSAFE_PATH, "A bundle entry path must be unique when case is folded.");
            }
        }
        for (String path : logicalPaths) {
            for (String other : logicalPaths) {
                if (!path.equals(other) && other.startsWith(path + "/")) {
                    throw new SourceBundleRejected(
                            Reason.UNSAFE_PATH, "A bundle entry path must not be a directory of another.");
                }
            }
        }
    }

    /** The digest of some bytes, in the one form this system exchanges. */
    public static String sha256(byte[] content) {
        try {
            return "sha256:"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** The verified contents, keyed by the logical path the command authorized. */
    public Map<String, byte[]> contents() {
        return contentsByLogicalPath;
    }

    public String bundleDigest() {
        return bundleDigest;
    }

    public long totalBytes() {
        return totalBytes;
    }
}
