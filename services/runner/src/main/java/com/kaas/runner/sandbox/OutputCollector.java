package com.kaas.runner.sandbox;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The trusted side of a sandbox's output: every byte it prints passes through here, and only what this keeps
 * leaves the runner's memory.
 *
 * <h2>The pipeline, in order</h2>
 *
 * <pre>
 *   Docker frame payload (raw bytes, one stream)
 *      ├── ProtocolScanner      raw, trusted memory; keeps counts and vocabulary words only
 *      └── SecretRedactor       per stream, exact bytes, across frame boundaries
 *             → output ceiling  counted on REDACTED bytes, so truncation cannot cut a secret in half
 *             → UTF-8 decoding  incremental, so a code point split across frames survives
 *             → line assembly   per stream, including a final line with no newline
 *             → sanitisation    control and format characters stripped
 *             → redaction again  over the sanitised line: stripping can JOIN what the first pass saw apart
 *             → observations (key=value) and the redacted transcript
 * </pre>
 *
 * <p>The order is the security property. Redaction before the ceiling means a secret that crosses the ceiling
 * is replaced before anything is counted, rather than truncated into a prefix that no longer matches. Redaction
 * before decoding means it operates on the exact bytes the secret was delivered as, not on a lossy
 * re-encoding. And the protocol is read on its own raw branch, so a secret that happens to equal a protocol
 * word cannot change what the engine's verdict means.
 *
 * <p>Stdout and stderr are independent all the way through: separate protocol scanners, separate redactors
 * with separate overlap state, separate decoders and separate line buffers. Docker frame boundaries are not
 * line boundaries and a frame from one stream is not a continuation of the other.
 *
 * <p>Every method is synchronized: the callback thread delivers frames while the launcher may, on a timeout
 * path, be building its outcome.
 */
final class OutputCollector extends ResultCallback.Adapter<Frame> {

    private final int maximumBytes;
    private final Channel stdout;
    private final Channel stderr;

    /**
     * The collector's own copies of the values, for the second pass. Sanitising deletes characters, and a
     * deletion can reassemble a value the byte-exact first pass saw in two pieces -- {@code ab<U+200B>cd}
     * becomes {@code abcd}. Without a second pass the platform's own clean-up would be what reconstructed the
     * secret. Cleared when the streams end.
     */
    private final List<byte[]> secrets;

    private final Map<String, String> observations = new LinkedHashMap<>();
    private final Map<String, Integer> occurrences = new LinkedHashMap<>();

    private int emitted;
    private boolean truncated;
    private boolean finished;

    /**
     * @param secrets the exact values to redact, owned by the caller; copied into each stream's redactor
     */
    OutputCollector(int maximumBytes, List<byte[]> secrets) {
        this.maximumBytes = maximumBytes;
        this.secrets = secrets.stream().map(byte[]::clone).toList();
        this.stdout = new Channel(secrets);
        this.stderr = new Channel(secrets);
    }

    @Override
    public synchronized void onNext(Frame frame) {
        if (truncated || finished || frame == null || frame.getPayload() == null) {
            return;
        }
        Channel channel = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
        byte[] payload = frame.getPayload();
        channel.protocol.write(payload, 0, payload.length);
        channel.redactor.write(payload, 0, payload.length, (bytes, offset, length) -> admit(channel, bytes, offset, length));
    }

    @Override
    public void onComplete() {
        finish();
        super.onComplete();
    }

    /**
     * Ends both streams: the held-back tail is now final, a trailing line is a line, a trailing partial code
     * point is a replacement character. Idempotent, and called on every path that reads the result, including
     * the ones on which the daemon never completed the stream.
     */
    synchronized void finish() {
        if (finished) {
            return;
        }
        for (Channel channel : List.of(stdout, stderr)) {
            channel.protocol.finish();
            if (!truncated) {
                channel.redactor.finish((bytes, offset, length) -> admit(channel, bytes, offset, length));
            }
            channel.endOfInput(this);
            channel.redactor.close();
        }
        secrets.forEach(value -> java.util.Arrays.fill(value, (byte) 0));
        finished = true;
    }

    /** Redacted bytes, charged to the ceiling, then decoded into lines. */
    private void admit(Channel channel, byte[] bytes, int offset, int length) {
        if (truncated) {
            return;
        }
        int room = maximumBytes - emitted;
        if (length > room) {
            // The ceiling is on what the platform KEEPS, and it is reached here. What fits is kept -- whole
            // frames are no longer thrown away -- and everything after it is not. Nothing unredacted is in
            // front of this point, so there is no secret prefix for the cut to expose.
            if (room > 0) {
                emitted += room;
                channel.decode(bytes, offset, room, this);
            }
            truncated = true;
            return;
        }
        emitted += length;
        channel.decode(bytes, offset, length, this);
    }

    private void record(Channel channel, String line) {
        // Control characters are stripped here, at the boundary, rather than wherever this is eventually
        // rendered. Terminal escape sequences in untrusted output are an attack on whoever reads the logs.
        // Then redacted again, because the stripping may have joined a value; and only then split, so the key
        // and the value are cut from text that has been through both passes.
        String kept = redactAgain(sanitize(line));
        channel.transcript.append(kept).append('\n');
        int equals = kept.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = kept.substring(0, equals).trim();
        String value = kept.substring(equals + 1).trim();
        // HOW MANY TIMES A KEY WAS SEEN, not only what it last said, for the reason KAAS-21 recorded: a map
        // keeps the last value and forgets there was another.
        occurrences.merge(key, 1, Integer::sum);
        observations.put(key, value);
    }

    /** The second, whole-line pass: exact bytes again, over text that sanitising may have rejoined. */
    private String redactAgain(String sanitized) {
        if (secrets.isEmpty()) {
            return sanitized;
        }
        byte[] bytes = sanitized.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length);
        SecretRedactor second = SecretRedactor.over(secrets);
        try {
            second.write(bytes, 0, bytes.length, out::write);
            second.finish(out::write);
        } finally {
            second.close();
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String sanitize(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints()
                // Control characters go, and so do the format characters that survive them: a right-to-left
                // override or a zero-width joiner can reorder how an evidence line reads without changing a byte
                // of its meaning.
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .filter(codePoint -> Character.getType(codePoint) != Character.LINE_SEPARATOR)
                .filter(codePoint -> Character.getType(codePoint) != Character.PARAGRAPH_SEPARATOR)
                .forEach(safe::appendCodePoint);
        return safe.toString().trim();
    }

    synchronized Map<String, String> observations() {
        return Map.copyOf(observations);
    }

    synchronized Set<String> duplicated() {
        Set<String> repeated = new LinkedHashSet<>();
        occurrences.forEach((key, count) -> {
            if (count > 1) {
                repeated.add(key);
            }
        });
        return repeated;
    }

    synchronized boolean truncated() {
        return truncated;
    }

    /** How many redacted bytes were kept: the ceiling is checked against this, not against a flag. */
    synchronized int retainedBytes() {
        return emitted;
    }

    synchronized ProtocolScanner.Observed protocol() {
        return stdout.protocol.observed().plus(stderr.protocol.observed());
    }

    synchronized SandboxOutcome.Redaction redaction() {
        return new SandboxOutcome.Redaction(
                stdout.redactor.matches(), stderr.redactor.matches(),
                stdout.transcript.toString(), stderr.transcript.toString());
    }

    /** One stream's state, from raw bytes to lines. */
    private static final class Channel {
        final ProtocolScanner protocol = new ProtocolScanner();
        final SecretRedactor redactor;
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        final StringBuilder pending = new StringBuilder();
        final StringBuilder transcript = new StringBuilder();
        /** The incomplete tail of a code point, carried to the next chunk: at most three bytes. */
        byte[] carry = new byte[0];

        Channel(List<byte[]> secrets) {
            this.redactor = secrets.isEmpty() ? SecretRedactor.none() : SecretRedactor.over(secrets);
        }

        void decode(byte[] bytes, int offset, int length, OutputCollector owner) {
            ByteBuffer input = ByteBuffer.allocate(carry.length + length);
            input.put(carry).put(bytes, offset, length).flip();
            CharBuffer output = CharBuffer.allocate(input.remaining() + 1);
            decoder.decode(input, output, false);
            carry = new byte[input.remaining()];
            input.get(carry);
            output.flip();
            pending.append(output);
            drain(owner);
        }

        void endOfInput(OutputCollector owner) {
            ByteBuffer input = ByteBuffer.wrap(carry);
            CharBuffer output = CharBuffer.allocate(carry.length + 2);
            decoder.decode(input, output, true);
            decoder.flush(output);
            carry = new byte[0];
            output.flip();
            pending.append(output);
            drain(owner);
            if (!pending.isEmpty()) {
                // A final line with no newline is still a line. Dropping it was a defect: a stream whose last
                // word had no terminator lost that word.
                owner.record(this, pending.toString());
                pending.setLength(0);
            }
        }

        private void drain(OutputCollector owner) {
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                owner.record(this, pending.substring(0, newline));
                pending.delete(0, newline + 1);
            }
        }
    }
}
