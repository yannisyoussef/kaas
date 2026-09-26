package com.kaas.runner.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the engine adapter reads from its standard input after the source bootstrap has handed over: the run's
 * secret values, and where its egress proxy is.
 *
 * <h2>Why stdin, and why after the source</h2>
 *
 * <p>The container's standard input already carries the source frame, and the bootstrap reads it with exact,
 * unbuffered {@code read(2)} calls: it consumes precisely the source frame's bytes and not one more. So the
 * frame written immediately after it is still unread in the pipe when the bootstrap freezes the source
 * filesystem, drops every capability, and execs the adapter — and the adapter is the first reader of these
 * bytes. Plaintext is never in the address space of the privileged bootstrap, never in an environment
 * variable, never in argv, never in a system property, never in a file, and never in container metadata.
 *
 * <p>The adapter reads exactly this frame, requires the pipe to be empty after it, and closes standard input
 * before Karate starts. Tenant code runs after that, and finds no descriptor 0 to read.
 *
 * <pre>
 *   "KAASENG1"                           8 bytes
 *   secretCount                          u32 big-endian, 0..50
 *   secretCount times, keys ascending:
 *     keyLength u16, key (ASCII binding key), valueLength u32 (1..8192), value (UTF-8 bytes)
 *   egress                               u8: 0 none, 1 present
 *   if present:
 *     hostLength u16, host (ASCII), port u16, tokenLength u16, token (ASCII)
 *   "KAASEND1"                           8 bytes
 * </pre>
 *
 * <p>A secret-free run still gets a frame, with a count of zero: one path, so the adapter behaves identically
 * whether or not a run has secrets, and standard input is closed in both cases.
 *
 * <p>Holds its own copy of each value, for the redactor, and zeroes every copy it holds on {@link #close()}.
 * {@link #toString()} prints no value, key or token.
 */
public final class EngineInput implements AutoCloseable {
    static final byte[] MAGIC = "KAASENG1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] TRAILER = "KAASEND1".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:-]{1,253}$");

    /** The bounds the control plane enforces, restated here because the runner does not trust it to have. */
    public static final int MAX_SECRETS = 50;
    public static final int MAX_VALUE_BYTES = 8 * 1024;
    public static final int MAX_TOTAL_BYTES = 64 * 1024;

    private final byte[] frame;
    private final List<byte[]> redactable;
    private final int secretCount;

    private EngineInput(byte[] frame, List<byte[]> redactable, int secretCount) {
        this.frame = frame;
        this.redactable = redactable;
        this.secretCount = secretCount;
    }

    /** One binding key and its value. The value array is copied; the caller clears its own. */
    public record Secret(String key, byte[] value) {
        @Override
        public String toString() {
            return "Secret[key=" + key + ", value=<secret>]";
        }
    }

    /** Where the sandbox's egress proxy is, and the credential it accepts from this execution. */
    public record Egress(String host, int port, String token) {
        @Override
        public String toString() {
            return "Egress[host=" + host + ", port=" + port + ", token=<redacted>]";
        }
    }

    public static EngineInput secretFree() {
        return of(List.of(), null);
    }

    public static EngineInput of(List<Secret> secrets, Egress egress) {
        if (secrets.size() > MAX_SECRETS) {
            throw new IllegalArgumentException("Too many secrets for one engine.");
        }
        List<Secret> ordered = new ArrayList<>(secrets);
        ordered.sort(Comparator.comparing(Secret::key));
        long total = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Secret secret = ordered.get(index);
            if (!KEY.matcher(secret.key()).matches()) {
                throw new IllegalArgumentException("A secret binding key is malformed.");
            }
            if (index > 0 && ordered.get(index - 1).key().equals(secret.key())) {
                throw new IllegalArgumentException("A secret binding key appears twice.");
            }
            if (secret.value().length == 0 || secret.value().length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("A secret value is out of bounds.");
            }
            total += secret.value().length;
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("The secret set is out of bounds.");
        }
        // Everything is validated before a byte is written, so no path leaves a half-built frame or a clone
        // of a value behind uncleared.
        if (egress != null && (!HOST.matcher(egress.host()).matches() || egress.port() < 1 || egress.port() > 65535
                || egress.token() == null || egress.token().isEmpty() || egress.token().length() > 256)) {
            throw new IllegalArgumentException("The egress endpoint is malformed.");
        }
        // Sized for the largest frame these bounds allow, so the buffer never grows: a grown buffer leaves the
        // old array, values and all, behind where nothing can clear it.
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) total + ordered.size() * (2 + 128 + 4) + MAGIC.length + 4 + 1 + (2 + 253 + 2 + 2 + 256)
                        + TRAILER.length);
        out.writeBytes(MAGIC);
        u32(out, ordered.size());
        List<byte[]> redactable = new ArrayList<>();
        for (Secret secret : ordered) {
            byte[] key = secret.key().getBytes(StandardCharsets.US_ASCII);
            u16(out, key.length);
            out.writeBytes(key);
            u32(out, secret.value().length);
            out.writeBytes(secret.value());
            redactable.add(secret.value().clone());
        }
        if (egress == null) {
            out.write(0);
        } else {
            out.write(1);
            byte[] host = egress.host().getBytes(StandardCharsets.US_ASCII);
            u16(out, host.length);
            out.writeBytes(host);
            u16(out, egress.port());
            byte[] token = egress.token().getBytes(StandardCharsets.US_ASCII);
            u16(out, token.length);
            out.writeBytes(token);
            // The execution's own egress credential reaches tenant code by design -- the proxy is how an
            // allowlist execution talks to anything -- but it is still a credential, so it is redacted from the
            // platform's copy of the output like a secret is.
            redactable.add(token.clone());
            // And in the form the platform's own karate-config.js makes it travel in: Proxy-Authorization
            // Basic base64("kaas:" + token). The platform chose that encoding, so the platform redacts it; an
            // HTTP client that logs its request headers would otherwise print the credential in a form the
            // exact-bytes redactor was never told about.
            redactable.add(java.util.Base64.getEncoder().encode(
                    ("kaas:" + egress.token()).getBytes(StandardCharsets.US_ASCII)));
        }
        out.writeBytes(TRAILER);
        byte[] frame = out.toByteArray();
        out.reset();
        out.writeBytes(new byte[frame.length]);
        return new EngineInput(frame, List.copyOf(redactable), ordered.size());
    }

    /** The frame, for the one write that sends it. Not a copy: the caller must not retain it. */
    byte[] frame() {
        return frame;
    }

    /** The exact byte sequences the output redactor must remove. */
    List<byte[]> redactable() {
        return redactable;
    }

    public int secretCount() {
        return secretCount;
    }

    @Override
    public void close() {
        Arrays.fill(frame, (byte) 0);
        redactable.forEach(value -> Arrays.fill(value, (byte) 0));
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

    @Override
    public String toString() {
        return "EngineInput[secrets=" + secretCount + "]";
    }
}
