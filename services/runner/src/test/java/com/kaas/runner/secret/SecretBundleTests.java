package com.kaas.runner.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.sandbox.EngineInput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The runner's own reading of a secret bundle: exactly the command's set, or nothing. */
@DisplayName("Secret bundle parsing")
class SecretBundleTests {

    @Test
    @DisplayName("a bundle with exactly the authorized keys is accepted, byte for byte")
    void theExactSetIsAccepted() throws Exception {
        byte[] frame = frame(List.of(entry("ALPHA", "one"), entry("BETA", "two\r\nlines\n")), true);
        List<EngineInput.Secret> secrets = SecretBundle.parse(frame, Set.of("ALPHA", "BETA"));

        assertThat(secrets).extracting(EngineInput.Secret::key).containsExactly("ALPHA", "BETA");
        assertThat(new String(secrets.get(1).value(), StandardCharsets.UTF_8)).isEqualTo("two\r\nlines\n");
        assertThat(frame).as("the received frame is cleared once parsed").containsOnly((byte) 0);
        assertThat(secrets.get(0).toString()).doesNotContain("one");
    }

    @Test
    @DisplayName("an extra, a missing, or a different key refuses the whole bundle")
    void anyOtherSetIsRefused() {
        assertRejected(frame(List.of(entry("ALPHA", "a"), entry("EXTRA", "b")), true), Set.of("ALPHA"),
                SecretBundle.Rejection.UNEXPECTED_SET);
        assertRejected(frame(List.of(entry("ALPHA", "a")), true), Set.of("ALPHA", "BETA"),
                SecretBundle.Rejection.UNEXPECTED_SET);
        assertRejected(frame(List.of(entry("OTHER", "a")), true), Set.of("ALPHA"),
                SecretBundle.Rejection.UNEXPECTED_SET);
    }

    @Test
    @DisplayName("duplicate or unsorted keys, a missing trailer, and trailing bytes are all malformed")
    void structureIsStrict() {
        assertRejected(frame(List.of(entry("ALPHA", "a"), entry("ALPHA", "b")), true), Set.of("ALPHA"),
                SecretBundle.Rejection.MALFORMED);
        assertRejected(frame(List.of(entry("BETA", "a"), entry("ALPHA", "b")), true), Set.of("ALPHA", "BETA"),
                SecretBundle.Rejection.MALFORMED);
        assertRejected(frame(List.of(entry("ALPHA", "a")), false), Set.of("ALPHA"), SecretBundle.Rejection.MALFORMED);
        byte[] valid = frame(List.of(entry("ALPHA", "a")), true);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertRejected(trailing, Set.of("ALPHA"), SecretBundle.Rejection.MALFORMED);
        assertRejected(Arrays.copyOf(valid, valid.length - 3), Set.of("ALPHA"), SecretBundle.Rejection.MALFORMED);
    }

    @Test
    @DisplayName("a value over the bound, or not UTF-8, is refused rather than delivered differently")
    void valuesAreBoundedAndStrictlyUtf8() {
        byte[] large = new byte[EngineInput.MAX_VALUE_BYTES + 1];
        Arrays.fill(large, (byte) 'x');
        assertRejected(frame(List.of(new Entry("ALPHA", large)), true), Set.of("ALPHA"),
                SecretBundle.Rejection.TOO_LARGE);
        assertRejected(frame(List.of(new Entry("ALPHA", new byte[] {(byte) 0xc3, 0x28})), true), Set.of("ALPHA"),
                SecretBundle.Rejection.NOT_UTF8);
    }

    private static void assertRejected(byte[] frame, Set<String> keys, SecretBundle.Rejection reason) {
        assertThatThrownBy(() -> SecretBundle.parse(frame, keys))
                .isInstanceOf(SecretBundle.Rejected.class)
                .extracting(refused -> ((SecretBundle.Rejected) refused).reason())
                .isEqualTo(reason);
    }

    private record Entry(String key, byte[] value) {}

    private static Entry entry(String key, String value) {
        return new Entry(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Built here, from the contract, independently of the control plane's encoder. */
    private static byte[] frame(List<Entry> entries, boolean trailer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("KAASSEC1".getBytes(StandardCharsets.US_ASCII));
        u32(out, entries.size());
        for (Entry entry : entries) {
            byte[] key = entry.key().getBytes(StandardCharsets.US_ASCII);
            out.write(key.length >>> 8);
            out.write(key.length & 0xff);
            out.writeBytes(key);
            u32(out, entry.value().length);
            out.writeBytes(entry.value());
        }
        if (trailer) {
            out.writeBytes("KAASEND1".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
