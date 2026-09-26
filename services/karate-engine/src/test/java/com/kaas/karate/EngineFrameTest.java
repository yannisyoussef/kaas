package com.kaas.karate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adapter's side of the engine frame, and the one mechanism by which a secret reaches tenant code: a
 * programmatic Karate global.
 *
 * <p>Runs real Karate 2.1.2 in this JVM with no network and no sandbox: what is measured here is that the
 * binding API does what the adapter relies on, which the in-sandbox suites then exercise for real.
 */
class EngineFrameTest {

    @Test
    void aSecretIsReadableFromTheKaasGlobalAndFromNowhereElse(@TempDir Path work) throws Exception {
        String value = "generated-" + HexFormat.of().formatHex(SecureRandom.getSeed(16)) + "\r\nsecond line é";
        byte[] frame = frame(Map.of("API_TOKEN", value), null, null);
        var parsed = KaasKarateAdapter.EngineFrame.read(new ByteArrayInputStream(frame));

        Path feature = work.resolve("uses-secret.feature");
        Files.writeString(feature, """
                Feature: a feature that uses its secret
                  Scenario: the value is exactly what was delivered
                    * def token = kaas.secrets.API_TOKEN
                    * match token == expected
                    * match karate.properties['API_TOKEN'] == '#notpresent'
                    * def System = Java.type('java.lang.System')
                    * match System.getenv('API_TOKEN') == null
                """);
        SuiteResult result = Runner.path(List.of(feature.toString()))
                .global("kaas", parsed.globals())
                .global("expected", value)
                .outputHtmlReport(false)
                .outputJsonLines(false)
                .outputJunitXml(false)
                .outputCucumberJson(false)
                .backupOutputDir(false)
                .workingDir(work)
                .outputDir(work.resolve("out"))
                .parallel(1);
        assertThat(result.isFailed()).as("the feature read the exact value through kaas.secrets").isFalse();
        assertThat(result.getScenarioPassedCount()).as("and the scenario actually ran").isEqualTo(1);

        // The control: the same feature against a different expectation fails. Without it a binding that
        // delivered nothing, and a match that compared nothing, would look identical to the pass above.
        SuiteResult control = Runner.path(List.of(feature.toString()))
                .global("kaas", parsed.globals())
                .global("expected", value + "x")
                .outputHtmlReport(false).outputJsonLines(false).outputJunitXml(false).outputCucumberJson(false)
                .backupOutputDir(false).workingDir(work).outputDir(work.resolve("out-control"))
                .parallel(1);
        assertThat(control.getScenarioFailedCount()).isEqualTo(1);
    }

    @Test
    void theEgressEndpointBecomesTheProxyConfigurationAndNothingElse(@TempDir Path work) throws Exception {
        var parsed = KaasKarateAdapter.EngineFrame.read(
                new ByteArrayInputStream(frame(Map.of(), "11.0.0.3", "kaas_egr_" + "x".repeat(43))));
        assertThat(parsed.proxyUri()).isEqualTo("http://11.0.0.3:3128");
        @SuppressWarnings("unchecked")
        Map<String, Object> egress = (Map<String, Object>) parsed.globals().get("egress");
        assertThat(egress).containsEntry("uri", "http://11.0.0.3:3128");
        assertThat(parsed.toString()).doesNotContain("kaas_egr_");
        // The platform karate-config.js runs, reads kaas.egress, and configures the proxy without error.
        Path feature = work.resolve("configured.feature");
        Files.writeString(feature, "Feature: f\n  Scenario: s\n    * match kaas.egress.uri == 'http://11.0.0.3:3128'\n");
        SuiteResult result = Runner.path(List.of(feature.toString()))
                .global("kaas", parsed.globals())
                .outputHtmlReport(false).outputJsonLines(false).outputJunitXml(false).outputCucumberJson(false)
                .backupOutputDir(false).workingDir(work).outputDir(work.resolve("out"))
                .parallel(1);
        assertThat(result.isFailed()).isFalse();
        assertThat(result.getScenarioPassedCount()).isEqualTo(1);
    }

    @Test
    void aSecretFreeFrameIsAnEmptySecretMapAndNoEgress() throws Exception {
        var parsed = KaasKarateAdapter.EngineFrame.read(new ByteArrayInputStream(frame(Map.of(), null, null)));
        assertThat(parsed.secrets()).isEmpty();
        assertThat(parsed.globals()).containsOnlyKeys("secrets");
    }

    @Test
    void anyDeviationFromTheFrameIsRefusedWhole() {
        byte[] valid = frame(Map.of("K", "v"), null, null);
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThatThrownBy(() -> KaasKarateAdapter.EngineFrame.read(new ByteArrayInputStream(trailing)))
                .as("a byte after the trailer is not what the runner wrote")
                .isInstanceOf(IOException.class);
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertThatThrownBy(() -> KaasKarateAdapter.EngineFrame.read(new ByteArrayInputStream(truncated)))
                .isInstanceOf(IOException.class);
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] = 'X';
        assertThatThrownBy(() -> KaasKarateAdapter.EngineFrame.read(new ByteArrayInputStream(wrongMagic)))
                .isInstanceOf(IOException.class);
    }

    /** The frame as the runner's EngineInput writes it, built here from the contract. */
    private static byte[] frame(Map<String, String> secrets, String host, String token) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("KAASENG1".getBytes(StandardCharsets.US_ASCII));
        u32(out, secrets.size());
        secrets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            byte[] key = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            u16(out, key.length);
            out.writeBytes(key);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            u32(out, value.length);
            out.writeBytes(value);
        });
        if (host == null) {
            out.write(0);
        } else {
            out.write(1);
            u16(out, host.length());
            out.writeBytes(host.getBytes(StandardCharsets.US_ASCII));
            u16(out, 3128);
            u16(out, token.length());
            out.writeBytes(token.getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes("KAASEND1".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
