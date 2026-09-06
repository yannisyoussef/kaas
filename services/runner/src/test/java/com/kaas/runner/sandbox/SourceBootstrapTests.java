package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the bootstrap refuses, measured by running it.
 *
 * <h2>Why these run on any runtime</h2>
 *
 * <p>Everything here happens before the freeze. The bootstrap reads the frame, checks it, and writes files;
 * only then does it try to close the filesystem, and only that last step needs the mediating runtime. So a
 * malformed, traversing, oversized or truncated frame is refused identically everywhere — which means these
 * checks can be tested on a development machine and in the ordinary gate, rather than only in the one job
 * that has gVisor.
 *
 * <p>That matters more than convenience. The bootstrap is the most privileged program in the sandbox and it
 * is the only one that reads a stream, so its parser is the part most worth exercising, and a parser whose
 * tests can only run in one job is a parser that mostly is not tested.
 *
 * <h2>What is asserted</h2>
 *
 * <p>A refusal category and nothing else. The bootstrap reports a platform-defined word; it never reports a
 * path, a length, or a byte of what it was reading, because a message that distinguished one tenant's bundle
 * from another's would be tenant content in a container log.
 */
@DisplayName("Source bootstrap")
class SourceBootstrapTests {

    private final String generation = "bootstrap-" + UUID.randomUUID();

    @AfterEach
    void nothingSurvives() {
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of("kaas.launcher.generation", generation))
                        .exec())
                .isEmpty();
    }

    @Test
    @Timeout(300)
    @DisplayName("a well-formed frame is accepted, so every refusal below is about what it refused")
    void aWellFormedFrameIsAccepted() {
        // ANTI-VACUITY FIRST. Each case below asserts a refusal, and a bootstrap that refused everything
        // would satisfy all of them. This is the same frame shape with nothing wrong with it.
        var observations = run(frame(entry("features/a.feature", "Feature: a\n"), true, true));

        assertThat(observations)
                .as("what the bootstrap reported: %s", observations)
                .doesNotContainKey("bootstrap_failure");
    }

    @Test
    @Timeout(300)
    @DisplayName("a traversing path is refused, and the check that refuses it is actually reached")
    void aTraversingPathIsRefused() {
        // THE GAP A MUTATION FOUND. Removing the path check from the bootstrap changed nothing any test
        // could see: the control plane and the runner both check these paths first, so nothing in the suite
        // ever handed the bootstrap a bad one.
        //
        // That is precisely the reason the check exists. It is the last place before a filesystem call, and
        // it defends against a defect in the two layers above it — so the only way to test it is to be that
        // defect on purpose.
        assertThat(run(frame(entry("../escape.feature", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
        assertThat(run(frame(entry("a/../../escape", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
        assertThat(run(frame(entry("/absolute", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
        assertThat(run(frame(entry("back\\slash", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
        assertThat(run(frame(entry("trailing/", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
        assertThat(run(frame(entry("controlchar", "x"), true, true)))
                .containsEntry("bootstrap_failure", "UNSAFE_PATH");
    }

    @Test
    @Timeout(300)
    @DisplayName("a frame that is not this format is refused rather than interpreted")
    void aForeignFrameIsRefused() {
        assertThat(run(frame(entry("features/a.feature", "x"), false, true)))
                .containsEntry("bootstrap_failure", "MALFORMED");
    }

    @Test
    @Timeout(300)
    @DisplayName("a truncated stream is a refusal, not a short bundle")
    void aTruncatedFrameIsRefused() {
        // The trailer exists for this. Without it a stream cut off after the last complete entry would look
        // like a whole bundle, which is exactly the case where a partial delivery is most convincing.
        byte[] whole = frame(entry("features/a.feature", "Feature: a\n"), true, true);
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 4);

        assertThat(run(cut)).containsEntry("bootstrap_failure", "TRUNCATED");
        // And the same frame missing only its trailer, where every entry did arrive intact.
        byte[] noTrailer = frame(entry("features/a.feature", "Feature: a\n"), true, false);
        assertThat(run(noTrailer)).containsEntry("bootstrap_failure", "TRUNCATED");
    }

    @Test
    @Timeout(300)
    @DisplayName("an entry larger than the contract permits is refused before it is written")
    void anOversizedEntryIsRefused() {
        byte[] over = new byte[(int) com.kaas.runner.source.SourceBundleContract.MAX_ENTRY_BYTES + 1];
        java.util.Arrays.fill(over, (byte) 'x');
        assertThat(run(frame(entry("features/a.feature", over), true, true)))
                .containsEntry("bootstrap_failure", "TOO_LARGE");
    }

    @Test
    @Timeout(300)
    @DisplayName("an empty frame is refused rather than producing an empty source filesystem")
    void anEmptyFrameIsRefused() {
        // A bundle with no entries would verify trivially and mount nothing, which is a silently empty
        // execution rather than a failed one.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("KAASSRC1".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("0".repeat(64).getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 0);
        out.writeBytes("KAASEND1".getBytes(StandardCharsets.US_ASCII));

        assertThat(run(out.toByteArray())).containsEntry("bootstrap_failure", "TOO_LARGE");
    }

    /** Runs the bootstrap with a frame and returns what it reported. */
    private Map<String, String> run(byte[] frame) {
        var profile = SandboxSecurityProfile.withSource(
                SandboxTestSupport.profile(),
                new SandboxSecurityProfile.SourceDelivery(
                        frame, com.kaas.runner.source.SourceBundleContract.SOURCE_FILESYSTEM_BYTES));
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.WORKLOAD_SOURCE_VERIFY, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        return outcome.observations();
    }

    private record Entry(String path, byte[] content) {}

    private static Entry entry(String path, String content) {
        return new Entry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry entry(String path, byte[] content) {
        return new Entry(path, content);
    }

    /** Builds a frame by hand, so the shapes a well-behaved runner never produces can be tested. */
    private static byte[] frame(Entry entry, boolean correctMagic, boolean withTrailer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((correctMagic ? "KAASSRC1" : "NOTOURS1").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("0".repeat(64).getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 1);
        byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
        writeInt(out, path.length);
        out.writeBytes(path);
        out.writeBytes(sha256Hex(entry.content()).getBytes(StandardCharsets.US_ASCII));
        writeLong(out, entry.content().length);
        out.writeBytes(entry.content());
        if (withTrailer) {
            out.writeBytes("KAASEND1".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((value >>> shift) & 0xff);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }
}
