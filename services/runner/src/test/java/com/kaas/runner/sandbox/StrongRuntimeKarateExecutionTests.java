package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.execution.EngineOutcome;
import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceFrame;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The engine, under the runtime production actually uses.
 *
 * <h2>Why this is separate from {@link KarateExecutionTests}</h2>
 *
 * <p>That suite runs under the baseline container runtime, because that is what a development host has. This
 * one runs under {@code runsc}, which is what a deployment runs and which no local build can provide — Docker
 * Desktop offers no supported way to register a runtime in its embedded VM. Its evidence exists only in the
 * mandatory {@code strong-runtime-gate} job.
 *
 * <p>The questions are deliberately few. {@link HostileJvmContainmentTests} already measures what a JVM can
 * do under this runtime, and repeating that here would be measuring gVisor twice. What is NOT covered
 * anywhere else is whether the product — Karate, on a frozen source filesystem, reached through the real
 * bootstrap handover — runs at all under the mediating runtime, and whether the classpath control survives it.
 */
@DisplayName("Karate under the mediating runtime")
class StrongRuntimeKarateExecutionTests {

    private final String generation = "karate-gvisor-" + UUID.randomUUID();

    @Test
    @Timeout(900)
    @DisplayName("real Karate runs the authorized feature under runsc and reports a verdict the platform reads")
    void karateRunsUnderTheMediatingRuntime() {
        // The whole success chain, under the runtime that matters. A JVM starting under gVisor was established
        // in KAAS-20; that an engine loads, parses a feature, and completes a suite under it was not.
        SandboxOutcome outcome = execute(Map.of(
                "features/passing.feature",
                """
                Feature: a tenant's passing suite
                  Scenario: an assertion that holds
                    * match 1 == 1
                """));

        assertThat(outcome.failure()).as("%s", outcome.observations()).isEmpty();
        assertThat(outcome.observations())
                .as("the run must name the engine, or nothing proves Karate loaded")
                .containsEntry("kaas.engine", "karate 2.1.2");
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
    }

    @Test
    @Timeout(900)
    @DisplayName("a failing assertion is still a tenant result under runsc")
    void aFailingSuiteIsStillATenantResult() {
        // The negative half of the pair above. Without it, an adapter that reported PASSED unconditionally
        // would satisfy the success chain under this runtime just as it would under the other one.
        SandboxOutcome outcome = execute(Map.of(
                "features/failing.feature",
                """
                Feature: a tenant's failing suite
                  Scenario: an assertion that does not hold
                    * match 1 == 2
                """));

        assertThat(outcome.failure()).as("%s", outcome.observations()).isEmpty();
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.FAILED);
    }

    @Test
    @Timeout(900)
    @DisplayName("the classpath control holds under runsc: interop works and platform classes are absent")
    void theClasspathControlHoldsUnderTheMediatingRuntime() {
        // The conjunction is the claim. Interop REACHABLE is the positive control; without it, "class not
        // found" would be consistent with a JVM where Java.type did nothing at all under this runtime.
        SandboxOutcome outcome = execute(Map.of(
                "features/classpath.feature",
                """
                Feature: what is on the engine's classpath
                  Scenario: enumerate
                    * def System = Java.type('java.lang.System')
                    * def say = function(k,v){ System.out.println('kaas.probe.' + k + '=' + v) }
                    * def attempt = function(f){ try { return f() } catch (e) { return 'REFUSED:' + e } }
                    * eval say('java-type', attempt(function(){ return Java.type('java.lang.ProcessBuilder') != null ? 'REACHABLE' : 'NULL' }))
                    * eval say('platform-launcher', attempt(function(){ return Java.type('com.kaas.runner.sandbox.DockerSandboxLauncher') != null ? 'REACHABLE' : 'NULL' }))
                    * eval say('source-write', attempt(function(){ var F = Java.type('java.nio.file.Files'); var P = Java.type('java.nio.file.Path'); F.write(P.of('/kaas/source/files/planted.feature'), Java.type('java.lang.String').valueOf('x').getBytes()); return 'WROTE' }))
                """));

        Map<String, String> probe = outcome.observations();
        assertThat(probe).containsEntry("kaas.probe.java-type", "REACHABLE");
        assertThat(probe.get("kaas.probe.platform-launcher"))
                .as("no platform class may be resolvable from tenant source, under any runtime")
                .startsWith("REFUSED:")
                .contains("class not found");
        // The freeze, under the runtime whose remount semantics differ from the baseline's. ADR-031 accepted
        // a missing MS_NODEV here; read-only is the control that is NOT missing, and this is where it is
        // measured through the product.
        assertThat(probe.get("kaas.probe.source-write")).contains("Read-only file system");
    }

    private SandboxOutcome execute(Map<String, String> features) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<SourceBundle.ExpectedEntry> expected = new ArrayList<>();
        features.forEach((path, content) -> {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            entries.put(path, bytes);
            expected.add(new SourceBundle.ExpectedEntry(path, SourceBundle.sha256(bytes)));
        });
        SourceBundle bundle =
                SourceBundle.verified(archiveOf(entries), expected, SourceBundle.bundleDigest(expected));

        var profile = SandboxSecurityProfile.withSource(
                // GVISOR, which is the only difference from the baseline suite and the entire point of it.
                SandboxSecurityProfile.version1(
                        SandboxTestSupport.karateEngineImage(), ExecutionRuntimeType.GVISOR),
                new SandboxSecurityProfile.SourceDelivery(
                        SourceFrame.of(bundle), SourceBundleContract.SOURCE_FILESYSTEM_BYTES));

        return SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.KARATE_ENGINE, profile.version(), UUID.randomUUID()));
    }

    private static byte[] archiveOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (var entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.getValue().length);
                zipEntry.setCompressedSize(entry.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }
}
