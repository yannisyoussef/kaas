package com.kaas.runner.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the engine's result protocol out of the RAW output, in trusted memory, before anything is redacted.
 *
 * <h2>Why raw, and why a separate branch</h2>
 *
 * <p>The protocol is a handful of fixed lines the platform's adapter prints: which engine ran, what the verdict
 * was, whether the secret channel was consumed. Redaction must not be able to change what those lines mean — a
 * tenant whose secret happened to be {@code PASSED} would otherwise turn every verdict into {@code [REDACTED]},
 * and a redactor that rewrote a key could hide a forged duplicate. So this scanner sees the bytes as the sandbox
 * wrote them, and the redactor sees the same bytes independently on the other branch.
 *
 * <h2>Why that is safe</h2>
 *
 * <p>This scanner keeps almost nothing. A line is considered only if it is short (at most
 * {@value #MAX_LINE_BYTES} bytes), starts with one of four platform keys, and has a value from that key's
 * closed vocabulary. Only such a value is kept — a word like {@code PASSED}, or {@code karate 2.1.2}. For
 * anything else it keeps a COUNT: how many times each key appeared, and how many of those appearances carried a
 * value outside the vocabulary. The line itself, and in particular a malformed line that might carry a secret,
 * is never stored, returned, logged or put in an exception. A secret cannot survive here because nothing that
 * is not a vocabulary word survives here.
 *
 * <p>Tenant code can print protocol-shaped lines, and cannot be stopped from doing so. The counts are what make
 * that harmless: a key seen twice is a stream that answered twice, and the runner refuses it rather than
 * choosing — the KAAS-21 rule, preserved.
 */
public final class ProtocolScanner {

    /** A protocol line is short. Anything longer is output, not protocol, and is ignored whole. */
    static final int MAX_LINE_BYTES = 256;

    public static final String RESULT_KEY = "kaas.karate-result.v1";
    public static final String ENGINE_KEY = "kaas.engine";
    public static final String ENGINE_ERROR_KEY = "engine_error";
    public static final String SECRET_CHANNEL_KEY = "kaas.secrets";

    private static final Map<String, Pattern> VOCABULARY = Map.of(
            RESULT_KEY, Pattern.compile("^(PASSED|FAILED|ENGINE_ERROR)$"),
            ENGINE_KEY, Pattern.compile("^(karate [0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}|unknown)$"),
            ENGINE_ERROR_KEY,
                    Pattern.compile("^(SOURCE_UNREADABLE|NO_AUTHORIZED_FEATURES|ENGINE_FAILURE|SECRET_CHANNEL)$"),
            SECRET_CHANNEL_KEY, Pattern.compile("^(CONSUMED)$"));

    private final byte[] line = new byte[MAX_LINE_BYTES];
    private int length;
    private boolean overflowed;

    private final Map<String, Integer> seen = new LinkedHashMap<>();
    private final Map<String, Integer> invalid = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    public void write(byte[] data, int offset, int count) {
        for (int index = offset; index < offset + count; index++) {
            byte value = data[index];
            if (value == '\n') {
                complete();
                continue;
            }
            if (overflowed) {
                continue;
            }
            if (length == MAX_LINE_BYTES) {
                overflowed = true;
                continue;
            }
            line[length++] = value;
        }
    }

    /** The stream ended: a final line without a newline is still a line. */
    public void finish() {
        if (length > 0 || overflowed) {
            complete();
        }
    }

    private void complete() {
        try {
            if (overflowed || length == 0) {
                return;
            }
            int end = length;
            if (line[end - 1] == '\r') {
                end--;
            }
            int equals = -1;
            for (int index = 0; index < end; index++) {
                if (line[index] == '=') {
                    equals = index;
                    break;
                }
            }
            if (equals <= 0) {
                return;
            }
            String key = new String(line, 0, equals, StandardCharsets.US_ASCII);
            Pattern vocabulary = VOCABULARY.get(key);
            if (vocabulary == null) {
                return;
            }
            seen.merge(key, 1, Integer::sum);
            // Decoded only after the key has been recognised, and kept only if it is a vocabulary word. A value
            // outside the vocabulary is counted and discarded without ever becoming a String.
            boolean printable = true;
            for (int index = equals + 1; index < end; index++) {
                if (line[index] < 0x20 || line[index] > 0x7e) {
                    printable = false;
                    break;
                }
            }
            String value = printable
                    ? new String(line, equals + 1, end - equals - 1, StandardCharsets.US_ASCII)
                    : null;
            if (value != null && vocabulary.matcher(value).matches()) {
                values.put(key, value);
            } else {
                invalid.merge(key, 1, Integer::sum);
            }
        } finally {
            java.util.Arrays.fill(line, 0, Math.max(length, 0), (byte) 0);
            length = 0;
            overflowed = false;
        }
    }

    /** What this stream said, as counts and vocabulary words. */
    public Observed observed() {
        return new Observed(Map.copyOf(seen), Map.copyOf(invalid), Map.copyOf(values));
    }

    /**
     * Protocol observations: how many times each key appeared, how many of those were not vocabulary words,
     * and the vocabulary word each key last carried.
     */
    public record Observed(Map<String, Integer> seen, Map<String, Integer> invalid, Map<String, String> values) {

        public static final Observed NONE = new Observed(Map.of(), Map.of(), Map.of());

        /** Both streams together: a key that appears once on each has appeared twice. */
        public Observed plus(Observed other) {
            Map<String, Integer> seen = new LinkedHashMap<>(this.seen);
            other.seen.forEach((key, count) -> seen.merge(key, count, Integer::sum));
            Map<String, Integer> invalid = new LinkedHashMap<>(this.invalid);
            other.invalid.forEach((key, count) -> invalid.merge(key, count, Integer::sum));
            Map<String, String> values = new LinkedHashMap<>(this.values);
            values.putAll(other.values);
            return new Observed(Map.copyOf(seen), Map.copyOf(invalid), Map.copyOf(values));
        }

        public int count(String key) {
            return seen.getOrDefault(key, 0);
        }

        /**
         * The key's value if it appeared exactly once and that once was a vocabulary word; otherwise null.
         * Null is the only answer to "which one do you believe?" when there were two.
         */
        public String single(String key) {
            if (count(key) != 1 || invalid.getOrDefault(key, 0) != 0) {
                return null;
            }
            return values.get(key);
        }
    }
}
