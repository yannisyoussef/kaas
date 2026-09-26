package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The redactor against the cases that break naive redaction: frame boundaries, overlapping values, the end
 * of the stream, and multi-byte text — and against a reference implementation over random chunkings.
 *
 * <p>The values here are test fixtures, not secrets: this suite is about the algorithm, and the end-to-end
 * suites use runtime-generated values.
 */
@DisplayName("Streaming secret redaction")
class SecretRedactorTests {

    private static final String R = "[REDACTED]";

    @Test
    @DisplayName("a value split across two frames is still redacted, and counted once")
    void aValueSplitAcrossFramesIsRedacted() {
        SecretRedactor redactor = SecretRedactor.over(List.of(bytes("abc12345xyz")));
        String out = run(redactor, "before abc12", "345xyz after");
        assertThat(out).isEqualTo("before " + R + " after");
        assertThat(redactor.matches()).isEqualTo(1);
    }

    @Test
    @DisplayName("every possible split point of a value is covered, with one byte per frame as the extreme")
    void everySplitPointIsCovered() {
        String value = "k4$-Secret_Value";
        String text = "lead " + value + " mid " + value + " end";
        for (int first = 1; first < text.length(); first++) {
            for (int second = first; second < text.length(); second++) {
                String out = run(SecretRedactor.over(List.of(bytes(value))),
                        text.substring(0, first), text.substring(first, second), text.substring(second));
                assertThat(out).isEqualTo("lead " + R + " mid " + R + " end");
            }
        }
        String[] singleBytes = text.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
        assertThat(run(SecretRedactor.over(List.of(bytes(value))), singleBytes))
                .isEqualTo("lead " + R + " mid " + R + " end");
    }

    @Test
    @DisplayName("overlapping values never leave the longer one's tail behind")
    void overlappingValuesAreRedactedAsOneRun() {
        List<byte[]> values = List.of(bytes("abc"), bytes("abcdef"));
        assertThat(run(SecretRedactor.over(values), "xxabcdefyy")).isEqualTo("xx" + R + "yy");
        assertThat(run(SecretRedactor.over(values), "xxabcdef")).isEqualTo("xx" + R);
        assertThat(run(SecretRedactor.over(values), "abcabc")).isEqualTo(R);
        // Values that overlap at an offset, neither containing the other.
        assertThat(run(SecretRedactor.over(List.of(bytes("abc"), bytes("bcd"))), "zabcdz")).isEqualTo("z" + R + "z");
        // A shorter value inside a longer one registered FIRST in the other order.
        assertThat(run(SecretRedactor.over(List.of(bytes("abcdef"), bytes("cd"))), "..abcdef..cd.."))
                .isEqualTo(".." + R + ".." + R + "..");
    }

    @Test
    @DisplayName("a value ending exactly at the end of the stream is redacted, and a held-back prefix is released")
    void theEndOfTheStreamIsHandled() {
        SecretRedactor ending = SecretRedactor.over(List.of(bytes("abc12345xyz")));
        assertThat(run(ending, "hello abc12345xyz")).isEqualTo("hello " + R);

        // An incomplete occurrence at the end is NOT the value -- it is a prefix of it, and it is emitted.
        // This is the exact-match guarantee, and its limit: a feature that prints a prefix prints a prefix.
        SecretRedactor partial = SecretRedactor.over(List.of(bytes("abc12345xyz")));
        assertThat(run(partial, "hello abc12")).isEqualTo("hello abc12");
        assertThat(partial.matches()).isZero();
    }

    @Test
    @DisplayName("nothing is emitted that a later byte could still turn into part of a value")
    void heldBackBytesAreNotEmittedEarly() {
        SecretRedactor redactor = SecretRedactor.over(List.of(bytes("abc12345xyz")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        redactor.write(bytes("abc12345xy"), 0, 10, out::write);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("ten bytes of an eleven-byte value, and not one of them released")
                .isEmpty();
        redactor.write(bytes("z"), 0, 1, out::write);
        redactor.finish(out::write);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(R);
    }

    @Test
    @DisplayName("multiline, CRLF, Unicode and PEM values are matched on their exact bytes, split anywhere")
    void exactBytesOfTextValuesAreMatched() {
        String pem = "-----BEGIN PRIVATE KEY-----\r\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\r\n-----END PRIVATE KEY-----\r\n";
        String unicode = "pässwörd-中文-𝄞";
        String lf = "line one\nline two\n";
        List<byte[]> values = List.of(bytes(pem), bytes(unicode), bytes(lf));
        String text = "A " + pem + " B " + unicode + " C " + lf + " D";
        byte[] all = bytes(text);
        for (int cut = 1; cut < all.length; cut++) {
            SecretRedactor redactor = SecretRedactor.over(values);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            redactor.write(all, 0, cut, out::write);
            redactor.write(all, cut, all.length - cut, out::write);
            redactor.finish(out::write);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("A " + R + " B " + R + " C " + R + " D");
        }
        // And the same text with the PEM's CRLF turned into LF is a DIFFERENT byte sequence: not redacted.
        // Normalisation is not something this does, in either direction.
        String normalised = pem.replace("\r\n", "\n");
        assertThat(run(SecretRedactor.over(values), normalised)).isEqualTo(normalised);
    }

    @Test
    @DisplayName("with nothing registered, every byte passes through as it arrives")
    void noValuesMeansNoHoldBack() {
        SecretRedactor none = SecretRedactor.none();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        none.write(bytes("abc"), 0, 3, out::write);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    @Timeout(10)
    @DisplayName("a tenant who knows its own secret cannot make redaction quadratic")
    void redactionIsLinear() {
        byte[] value = new byte[8192];
        Arrays.fill(value, (byte) 'a');
        byte[] flood = new byte[4 * 1024 * 1024];
        Arrays.fill(flood, (byte) 'a');
        SecretRedactor redactor = SecretRedactor.over(List.of(value, Arrays.copyOf(value, 4096), bytes("a")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < flood.length; offset += 65536) {
            redactor.write(flood, offset, 65536, out::write);
        }
        redactor.finish(out::write);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(R);
    }

    @Test
    @DisplayName("matches a reference implementation over random values, text and chunking")
    void agreesWithAReferenceImplementation() {
        Random random = new Random(22);
        String alphabet = "abcAB12\n\r é";
        for (int round = 0; round < 3000; round++) {
            List<byte[]> values = new ArrayList<>();
            for (int count = 1 + random.nextInt(3); count > 0; count--) {
                values.add(bytes(randomText(random, alphabet, 1 + random.nextInt(6))));
            }
            byte[] text = bytes(randomText(random, alphabet, random.nextInt(60)));
            String expected = reference(text, values);

            SecretRedactor redactor = SecretRedactor.over(values);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < text.length) {
                int length = 1 + random.nextInt(Math.max(1, text.length - offset));
                redactor.write(text, offset, length, out::write);
                offset += length;
            }
            redactor.finish(out::write);
            assertThat(out.toString(StandardCharsets.ISO_8859_1))
                    .as("round %d", round)
                    .isEqualTo(expected);
        }
    }

    /**
     * The definition, computed the slow way over the whole input: every byte inside any occurrence of any
     * value is covered, and each maximal run of covered bytes becomes one replacement.
     */
    private static String reference(byte[] text, List<byte[]> values) {
        boolean[] covered = new boolean[text.length];
        for (byte[] value : values) {
            for (int start = 0; start + value.length <= text.length; start++) {
                if (Arrays.equals(text, start, start + value.length, value, 0, value.length)) {
                    Arrays.fill(covered, start, start + value.length, true);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < text.length; index++) {
            if (covered[index]) {
                if (index == 0 || !covered[index - 1]) {
                    out.append(R);
                }
            } else {
                out.append((char) (text[index] & 0xff));
            }
        }
        return out.toString();
    }

    private static String randomText(Random random, String alphabet, int length) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < length; index++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private static String run(SecretRedactor redactor, String... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String chunk : chunks) {
            byte[] bytes = bytes(chunk);
            redactor.write(bytes, 0, bytes.length, out::write);
        }
        redactor.finish(out::write);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
