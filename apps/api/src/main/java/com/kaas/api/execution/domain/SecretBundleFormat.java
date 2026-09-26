package com.kaas.api.execution.domain;

import com.kaas.api.secrets.domain.SecretLimits;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The secret bundle: the binary frame a secret capability redeems for.
 *
 * <p>Specified in {@code packages/api-contracts/secret-bundle.md}, and implemented independently by the runner,
 * which parses it with the same bounds. Binary rather than JSON because every value is opaque bytes that must
 * survive exactly — a JSON string would impose an escaping layer and, on the runner, an immutable
 * {@code String} copy of every value before any code could check it.
 *
 * <pre>
 *   "KAASSEC1"                          8 bytes
 *   count                               u32 big-endian, 1..50
 *   count times, keys strictly ascending:
 *     keyLength                         u16 big-endian, 1..128
 *     key                               ASCII, ^[A-Za-z_][A-Za-z0-9_.-]{0,127}$
 *     valueLength                       u32 big-endian, 1..8192
 *     value                             UTF-8 bytes, exactly as stored
 *   "KAASEND1"                          8 bytes
 * </pre>
 *
 * <p>Nothing follows the trailer. A frame with trailing bytes, a duplicate or unsorted key, an empty value, or
 * any bound exceeded is not a bundle.
 */
public final class SecretBundleFormat {
    public static final byte[] MAGIC = "KAASSEC1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] TRAILER = "KAASEND1".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$");

    private SecretBundleFormat() {}

    /** One binding key and its value. The value array is the caller's, and is copied into the frame. */
    public record Entry(String key, byte[] value) {
        @Override
        public String toString() {
            return "Entry[key=" + key + ", value=<secret>]";
        }
    }

    /**
     * Encodes the entries in key order.
     *
     * <p>The returned array is the only complete copy; the caller sends it and clears it. The intermediate
     * stream's own buffer is overwritten before this returns.
     */
    public static byte[] encode(List<Entry> entries) {
        if (entries.isEmpty() || entries.size() > SecretLimits.MAX_SECRETS_PER_RUN) {
            throw new IllegalArgumentException("A secret bundle carries between one and fifty secrets.");
        }
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(Entry::key));
        long total = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Entry entry = ordered.get(index);
            if (!KEY.matcher(entry.key()).matches()) {
                throw new IllegalArgumentException("A secret binding key is malformed.");
            }
            if (index > 0 && ordered.get(index - 1).key().equals(entry.key())) {
                throw new IllegalArgumentException("A secret binding key appears twice.");
            }
            if (entry.value().length == 0 || entry.value().length > SecretLimits.MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("A secret value is out of bounds.");
            }
            total += entry.value().length;
        }
        if (total > SecretLimits.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("The secret bundle is out of bounds.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) total + 256);
        out.writeBytes(MAGIC);
        u32(out, ordered.size());
        for (Entry entry : ordered) {
            byte[] key = entry.key().getBytes(StandardCharsets.US_ASCII);
            u16(out, key.length);
            out.writeBytes(key);
            u32(out, entry.value().length);
            out.writeBytes(entry.value());
        }
        out.writeBytes(TRAILER);
        byte[] frame = out.toByteArray();
        out.reset();
        out.writeBytes(new byte[frame.length]);
        return frame;
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    /** For tests: whether a frame starts and ends as a bundle does. Never inspects a value. */
    public static boolean framed(byte[] frame) {
        return frame.length >= MAGIC.length + 4 + TRAILER.length
                && Arrays.equals(Arrays.copyOfRange(frame, 0, MAGIC.length), MAGIC)
                && Arrays.equals(Arrays.copyOfRange(frame, frame.length - TRAILER.length, frame.length), TRAILER);
    }
}
