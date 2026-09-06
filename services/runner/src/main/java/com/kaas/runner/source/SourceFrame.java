package com.kaas.runner.source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A verified bundle, framed for the one channel that carries tenant source into a sandbox.
 *
 * <h2>Why a frame and not a mount</h2>
 *
 * <p>KAAS-18 delivered source by mounting a host directory. Measured, that mount reaches a mediated sandbox
 * as a gofer-backed 9p filesystem carrying {@code ro} and nothing else — an executable file on it executed.
 * The filesystem that does enforce the flags is a sandbox-private tmpfs, and a tmpfs cannot be populated from
 * the host at all. So the bytes have to arrive through something else, and this is that something else.
 *
 * <p>The channel is standard input. Not a shell heredoc, not an argument, not an environment variable, not a
 * file the sandbox can name: a stream of bytes into a program that reads them. Tenant source never becomes
 * text that anything parses as syntax, which is the invariant this whole subsystem exists to keep.
 *
 * <h2>Why not just send the ZIP</h2>
 *
 * <p>The bundle arrives from the control plane as a ZIP, and it is verified as one — by the runner, against
 * the command. Handing that same archive to the bootstrap would mean putting a ZIP parser inside the most
 * privileged program in the sandbox, where an archive format's optional fields, compression methods and
 * central-directory quirks would all be attack surface at exactly the wrong moment.
 *
 * <p>So the frame is deliberately the dullest format that can carry the job: a magic word, a count, and for
 * each entry a length, a path, a digest and that many bytes. Nothing is optional, nothing is compressed,
 * nothing is indexed, and every length is read before the bytes it describes. The external contract with the
 * control plane is unchanged — the ZIP still crosses that boundary and is still verified there.
 */
public final class SourceFrame {

    /** Identifies the frame and the reader that understands it. An unknown magic is refused, not guessed at. */
    static final byte[] MAGIC = "KAASSRC1".getBytes(StandardCharsets.US_ASCII);

    /**
     * A trailer, so a truncated stream is a refusal rather than a short bundle.
     *
     * <p>The count is at the front and the bootstrap could simply stop when it has read that many entries.
     * That would accept a stream cut off mid-transfer as long as the last entry happened to complete, which
     * is exactly the case where a partial bundle looks whole.
     */
    static final byte[] TRAILER = "KAASEND1".getBytes(StandardCharsets.US_ASCII);

    private SourceFrame() {}

    /**
     * Frames a verified bundle.
     *
     * @param bundle the bundle the runner already checked against the command; nothing here re-decides what
     *     belongs in it, because that decision was made where the command was available
     */
    public static byte[] of(SourceBundle bundle) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        // The aggregate digest, hex only. The bootstrap writes it into the manifest so the in-sandbox verifier
        // can compare what it finds against what the command authorized, rather than against what the
        // bootstrap remembered.
        out.writeBytes(hex(bundle.bundleDigest()).getBytes(StandardCharsets.US_ASCII));
        writeInt(out, bundle.contents().size());
        for (Map.Entry<String, byte[]> entry : bundle.contents().entrySet()) {
            byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
            writeInt(out, path.length);
            out.writeBytes(path);
            out.writeBytes(hex(SourceBundle.sha256(entry.getValue())).getBytes(StandardCharsets.US_ASCII));
            writeLong(out, entry.getValue().length);
            out.writeBytes(entry.getValue());
        }
        out.writeBytes(TRAILER);
        return out.toByteArray();
    }

    /**
     * The bare hex of a digest this system exchanges as {@code sha256:<hex>}.
     *
     * <p>The prefix is stripped for the wire and put back by the bootstrap when it writes the manifest, so the
     * frame carries a fixed-width field and the manifest carries the form everything else in the system uses.
     * KAAS-18 lost half a day to three components disagreeing about whether the prefix was present; the
     * conversion happens here and in exactly one other place.
     */
    private static String hex(String prefixed) {
        if (!prefixed.startsWith("sha256:")) {
            throw new IllegalArgumentException("A digest must carry its algorithm prefix.");
        }
        return prefixed.substring("sha256:".length());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }
}
