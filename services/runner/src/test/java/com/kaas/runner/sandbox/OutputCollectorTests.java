package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The collector's pipeline order, which is its security property: protocol on the raw branch, redaction before
 * the ceiling, decoding after redaction, and two streams that never share state.
 */
@DisplayName("Trusted output collection")
class OutputCollectorTests {

    private static final String VALUE = "S3cr3t-Value-0123456789";

    @Test
    @DisplayName("redaction happens before the ceiling, so a value crossing it leaves no prefix behind")
    void redactionPrecedesTruncation() {
        // Twenty bytes of room. The value begins at byte 18. Truncating first and redacting after would keep
        // "S3" -- a prefix no longer long enough to match, and so never redacted.
        OutputCollector collector = new OutputCollector(20, List.of(bytes(VALUE)));
        collector.onNext(stdout("0123456789abcdefgh" + VALUE + " and more\n"));
        collector.finish();

        assertThat(collector.truncated()).isTrue();
        assertThat(collector.retainedBytes()).isEqualTo(20);
        String kept = collector.redaction().stdout();
        assertThat(kept).startsWith("0123456789abcdefgh[R");
        assertThat(kept).doesNotContain("S3");
        assertThat(collector.redaction().stdoutMatches())
                .as("the value was seen before the ceiling, which is why it is not in the kept bytes")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the ceiling keeps what fits of a frame rather than dropping the whole frame")
    void theCeilingKeepsWhatFits() {
        OutputCollector collector = new OutputCollector(10, List.of());
        collector.onNext(stdout("abcdefghijklmnop\n"));
        collector.finish();
        assertThat(collector.retainedBytes()).isEqualTo(10);
        assertThat(collector.redaction().stdout()).isEqualTo("abcdefghij\n");
    }

    @Test
    @DisplayName("stdout and stderr are redacted independently, each across its own frame boundaries")
    void bothStreamsAreRedactedIndependently() {
        OutputCollector collector = new OutputCollector(4096, List.of(bytes(VALUE)));
        String head = VALUE.substring(0, 7);
        String tail = VALUE.substring(7);
        // Interleaved: each stream's value is split, and the other stream's frame sits between the halves. A
        // shared matcher would see the halves separated by foreign bytes and miss both.
        collector.onNext(stdout("out:" + head));
        collector.onNext(stderr("err:" + head));
        collector.onNext(stdout(tail + ":out\n"));
        collector.onNext(stderr(tail + ":err\n"));
        collector.finish();

        var redaction = collector.redaction();
        assertThat(redaction.stdout()).isEqualTo("out:[REDACTED]:out\n");
        assertThat(redaction.stderr()).isEqualTo("err:[REDACTED]:err\n");
        assertThat(redaction.stdoutMatches()).isEqualTo(1);
        assertThat(redaction.stderrMatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("a code point split across frames is decoded, not replaced")
    void multiByteTextSurvivesFrameBoundaries() {
        OutputCollector collector = new OutputCollector(4096, List.of());
        byte[] text = bytes("clé=vérité\n");
        // Cut inside the two-byte é of "clé".
        int cut = "cl".getBytes(StandardCharsets.UTF_8).length + 1;
        collector.onNext(new Frame(StreamType.STDOUT, java.util.Arrays.copyOfRange(text, 0, cut)));
        collector.onNext(new Frame(StreamType.STDOUT, java.util.Arrays.copyOfRange(text, cut, text.length)));
        collector.finish();
        assertThat(collector.observations()).containsEntry("clé", "vérité");
    }

    @Test
    @DisplayName("a final line with no newline is still recorded")
    void aFinalUnterminatedLineIsKept() {
        OutputCollector collector = new OutputCollector(4096, List.of());
        collector.onNext(stdout("first=1\nlast=2"));
        collector.finish();
        assertThat(collector.observations()).containsEntry("first", "1").containsEntry("last", "2");
    }

    @Test
    @DisplayName("a secret equal to a protocol word cannot change the verdict, and the transcript still hides it")
    void theProtocolIsReadRawAndTheTranscriptRedacted() {
        OutputCollector collector = new OutputCollector(4096, List.of(bytes("PASSED")));
        collector.onNext(stdout("kaas.engine=karate 2.1.2\nkaas.secrets=CONSUMED\nkaas.karate-result.v1=PASSED\n"));
        collector.finish();

        assertThat(collector.protocol().single(ProtocolScanner.RESULT_KEY)).isEqualTo("PASSED");
        assertThat(collector.redaction().stdout()).contains("kaas.karate-result.v1=[REDACTED]");
        assertThat(collector.observations().get("kaas.karate-result.v1")).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("a malformed protocol line carrying a secret is counted and never kept")
    void aMalformedProtocolLineKeepsNothing() {
        OutputCollector collector = new OutputCollector(4096, List.of(bytes(VALUE)));
        collector.onNext(stdout("kaas.karate-result.v1=" + VALUE + "\n"));
        collector.finish();

        ProtocolScanner.Observed protocol = collector.protocol();
        assertThat(protocol.count(ProtocolScanner.RESULT_KEY)).isEqualTo(1);
        assertThat(protocol.single(ProtocolScanner.RESULT_KEY)).isNull();
        assertThat(protocol.toString()).doesNotContain(VALUE);
        assertThat(collector.redaction().stdout()).doesNotContain(VALUE);
        assertThat(collector.observations().toString()).doesNotContain(VALUE);
    }

    @Test
    @DisplayName("sanitising cannot reassemble a value the first pass saw in pieces")
    void sanitisingNeverRejoinsASecret() {
        // The byte-exact first pass sees neither of these as the value: a zero-width space and a control
        // character sit inside it. Sanitising deletes both -- and without a second pass, that deletion is what
        // would put the exact value into the transcript and the observations.
        String split = VALUE.substring(0, 6) + "​" + VALUE.substring(6);
        String controlled = VALUE.substring(0, 9) + "\u0001" + VALUE.substring(9);
        OutputCollector collector = new OutputCollector(4096, List.of(bytes(VALUE)));
        collector.onNext(stdout("token=" + split + "\n"));
        collector.onNext(stderr("plain " + controlled + " end\n"));
        collector.finish();

        assertThat(collector.redaction().stdoutMatches()).as("the first pass really did not see it").isZero();
        assertThat(collector.redaction().stdout()).doesNotContain(VALUE).contains("token=[REDACTED]");
        assertThat(collector.redaction().stderr()).doesNotContain(VALUE).contains("plain [REDACTED] end");
        assertThat(collector.observations().toString()).doesNotContain(VALUE);
        assertThat(collector.observations()).containsEntry("token", "[REDACTED]");
    }

    @Test
    @DisplayName("a forged protocol line on stderr still makes the verdict ambiguous")
    void aForgeryOnEitherStreamCounts() {
        OutputCollector collector = new OutputCollector(4096, List.of());
        collector.onNext(stderr("kaas.karate-result.v1=PASSED\n"));
        collector.onNext(stdout("kaas.karate-result.v1=FAILED\n"));
        collector.finish();
        assertThat(collector.protocol().count(ProtocolScanner.RESULT_KEY)).isEqualTo(2);
        assertThat(collector.protocol().single(ProtocolScanner.RESULT_KEY)).isNull();
    }

    private static Frame stdout(String text) {
        return new Frame(StreamType.STDOUT, bytes(text));
    }

    private static Frame stderr(String text) {
        return new Frame(StreamType.STDERR, bytes(text));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
