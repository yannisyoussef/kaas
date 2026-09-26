package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.kaas.runner.execution.EngineOutcome;
import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceFrame;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * Secret-bearing Karate inside the real sandbox: the channel, the redaction boundary, and every surface a secret
 * could leak to, measured from inside the engine and from the daemon's side at the same time.
 *
 * <h2>What makes these non-vacuous</h2>
 *
 * <p>A transcript with no secret in it proves nothing on its own — the feature might never have printed one.
 * So every redaction test asserts BOTH halves, from trusted instrumentation: the redactor counted a raw match on
 * that stream (the hazard happened, and the engine had the exact bytes), AND the kept output does not contain the
 * value (the redaction happened). Every leakage check that reads something from inside the sandbox is paired with
 * a control the feature must also report, so a probe that silently failed to read cannot pass as "not found".
 *
 * <p>Values are generated at runtime, never written in this file, and never passed to an assertion that would
 * print them: containment is computed in-process into a boolean.
 *
 * <p><strong>Under the mediating runtime only</strong>, like every suite that delivers tenant source. The local
 * override exists for development; the gate reads the daemon-reported runtime from this suite's evidence and
 * requires runsc.
 */
@DisplayName("Secret-bearing Karate execution")
class SecretBearingKarateExecutionTests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String generation = "karate-secret-" + UUID.randomUUID();

    /**
     * A fresh evidence file per run of the suite. Tests append to it, and the gate requires every line for a key
     * to agree, so a stale line from an earlier run would be a contradiction rather than a silent pass.
     */
    @org.junit.jupiter.api.BeforeAll
    static void freshEvidence() throws java.io.IOException {
        java.nio.file.Files.deleteIfExists(SandboxEvidence.directory().resolve("secret-execution-evidence.txt"));
        SandboxEvidence.append("secret-execution-evidence.txt",
                "sentinel_nonce_applied=" + (System.getProperty("kaas.test.sentinel-nonce") != null) + "\n");
    }

    @Test
    @Timeout(600)
    @DisplayName("a secret reaches Karate through kaas.secrets, is printed raw on both streams, and is redacted from both")
    void aPrintedSecretIsSeenRawAndKeptRedacted() {
        String secret = generated();
        SandboxOutcome outcome = execute(Map.of("API_TOKEN", secret), """
                Feature: a tenant that prints its secret on purpose
                  Scenario: every way a feature can put it on an output stream
                    * def System = Java.type('java.lang.System')
                    * def token = kaas.secrets.API_TOKEN
                    * eval System.out.println('stdout-line:' + token + ':end')
                    * eval System.err.println('stderr-line:' + token + ':end')
                    * eval System.out.println('kaas.probe.echo=' + token)
                    # One character at a time with a flush after each, so the value reaches the daemon split
                    # across as many frames as it has characters.
                    * eval for (var i = 0; i < token.length; i++) { System.out.print(token.charAt(i)); System.out.flush(); }
                    * eval System.out.println('')
                    * match token == kaas.secrets.API_TOKEN
                """);

        EngineOutcome engine = EngineOutcome.of(outcome);
        assertThat(engine.verdict()).as("%s", engine.detail()).isEqualTo(EngineOutcome.Verdict.PASSED);

        // THE HAZARD HAPPENED. The trusted collector saw the exact bytes, raw, on each stream: the engine held
        // the value, and tenant code put it where platform-owned output capture would receive it.
        assertThat(outcome.redaction().stdoutMatches()).as("raw_stdout_secret_observed").isGreaterThanOrEqualTo(3);
        assertThat(outcome.redaction().stderrMatches()).as("raw_stderr_secret_observed").isGreaterThanOrEqualTo(1);
        // THE REDACTION HAPPENED. Nothing the platform kept contains it.
        assertThat(contains(outcome.redaction().stdout(), secret)).as("stdout kept raw").isFalse();
        assertThat(contains(outcome.redaction().stderr(), secret)).as("stderr kept raw").isFalse();
        assertThat(contains(outcome.observations().toString(), secret)).as("observations kept raw").isFalse();
        assertThat(outcome.redaction().stdout()).contains("stdout-line:[REDACTED]:end");
        assertThat(outcome.redaction().stderr()).contains("stderr-line:[REDACTED]:end");
        assertThat(outcome.observations()).containsEntry("kaas.probe.echo", "[REDACTED]");

        SandboxEvidence.append("secret-execution-evidence.txt",
                "raw_stdout_secret_observed=" + (outcome.redaction().stdoutMatches() > 0) + "\n"
                        + "raw_stderr_secret_observed=" + (outcome.redaction().stderrMatches() > 0) + "\n"
                        + "persisted_raw_secret=" + (contains(outcome.redaction().stdout(), secret)
                                || contains(outcome.redaction().stderr(), secret)) + "\n"
                        + "engine_identity=" + outcome.protocol().single(ProtocolScanner.ENGINE_KEY) + "\n"
                        + "engine_verdict=" + engine.verdict() + "\n"
                        + "runtime=" + outcome.assignedRuntime() + "\n");
    }

    @Test
    @Timeout(600)
    @DisplayName("while the secret-bearing sandbox is alive, the daemon holds no log of it and no metadata carries the secret")
    void theDaemonKeepsNothing() throws Exception {
        String secret = generated();
        AtomicReference<String> metadata = new AtomicReference<>();
        AtomicReference<String> logDriver = new AtomicReference<>();
        AtomicReference<String> logPath = new AtomicReference<>();
        AtomicReference<Boolean> logsReadable = new AtomicReference<>();

        // A watcher on the daemon's side of the boundary, inspecting the container WHILE the feature has
        // already printed the secret and is still running. Everything the daemon knows about the container is
        // captured as the daemon reports it.
        Thread watcher = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + 240_000_000_000L;
                while (System.nanoTime() < deadline && metadata.get() == null) {
                    for (Container container : SandboxTestSupport.docker().listContainersCmd()
                            .withLabelFilter(Map.of("kaas.launcher.generation", generation))
                            .exec()) {
                        InspectContainerResponse inspected =
                                SandboxTestSupport.docker().inspectContainerCmd(container.getId()).exec();
                        if (!Boolean.TRUE.equals(inspected.getState().getRunning())) {
                            continue;
                        }
                        Thread.sleep(6_000); // well after the feature's print, well before its sleep ends
                        inspected = SandboxTestSupport.docker().inspectContainerCmd(container.getId()).exec();
                        logDriver.set(inspected.getHostConfig().getLogConfig().getType().getType());
                        logPath.set(String.valueOf(inspected.getLogPath()));
                        logsReadable.set(logsReadable(container.getId()));
                        metadata.set(JsonMapper.builder().build().writeValueAsString(inspected));
                    }
                    Thread.sleep(200);
                }
            } catch (Exception failed) {
                metadata.compareAndSet(null, "WATCHER_FAILED:" + failed.getClass().getName());
            }
        }, "daemon-watcher");
        watcher.setDaemon(true);
        watcher.start();

        SandboxOutcome outcome = execute(Map.of("API_TOKEN", secret), """
                Feature: a tenant that prints its secret and keeps running
                  Scenario: print, then stay alive while the daemon is inspected
                    * def System = Java.type('java.lang.System')
                    * eval System.out.println('printed:' + kaas.secrets.API_TOKEN)
                    * eval System.err.println('printed:' + kaas.secrets.API_TOKEN)
                    * eval Java.type('java.lang.Thread').sleep(15000)
                """);
        watcher.join(60_000);

        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
        assertThat(outcome.redaction().stdoutMatches()).as("the hazard happened on stdout").isPositive();
        assertThat(metadata.get()).as("the watcher inspected the live container").isNotNull().doesNotStartWith("WATCHER_FAILED");
        assertThat(logDriver.get()).as("docker_log_driver").isEqualTo("none");
        assertThat(logPath.get()).as("no host log file exists for this container").isIn("", "null");
        assertThat(logsReadable.get()).as("the daemon has no stored log to give back").isFalse();
        assertThat(contains(metadata.get(), secret)).as("secret_in_container_metadata").isFalse();

        SandboxEvidence.append("secret-execution-evidence.txt",
                "docker_log_driver=" + logDriver.get() + "\n"
                        + "docker_persistent_secret=" + logsReadable.get() + "\n"
                        + "secret_in_container_metadata=" + contains(metadata.get(), secret) + "\n");
    }

    @Test
    @Timeout(600)
    @DisplayName("the secret is in no environment, command line, system property or open descriptor of the engine")
    void theEngineProcessCarriesTheSecretNowhereElse() {
        String secret = generated();
        SandboxOutcome outcome = execute(Map.of("API_TOKEN", secret), """
                Feature: a tenant looking for its secret everywhere except where it was given
                  Scenario: the process's own surfaces
                    * def System = Java.type('java.lang.System')
                    * def Files = Java.type('java.nio.file.Files')
                    * def Path = Java.type('java.nio.file.Path')
                    * def Latin1 = Java.type('java.nio.charset.StandardCharsets').ISO_8859_1
                    * def token = kaas.secrets.API_TOKEN
                    * def environ = Files.readString(Path.of('/proc/self/environ'), Latin1)
                    * def cmdline = Files.readString(Path.of('/proc/self/cmdline'), Latin1)
                    * def names = System.getProperties().stringPropertyNames().stream().toList()
                    * def properties = names.stream().map(k => k + '=' + System.getProperty(k)).toList().toString()
                    * def limits = Files.readString(Path.of('/proc/self/limits'), Latin1)
                    # Controls: each surface was really read. A probe that failed to read would find nothing,
                    # and "nothing found" must not be how a surface passes.
                    * eval System.out.println('kaas.probe.environ-read=' + (environ.length > 0))
                    * eval System.out.println('kaas.probe.cmdline-read=' + (cmdline.indexOf('KaasKarateAdapter') >= 0))
                    * eval System.out.println('kaas.probe.properties-read=' + (properties.indexOf('java.version=') >= 0))
                    * eval System.out.println('kaas.probe.secret-in-environ=' + (environ.indexOf(token) >= 0))
                    * eval System.out.println('kaas.probe.secret-in-cmdline=' + (cmdline.indexOf(token) >= 0))
                    * eval System.out.println('kaas.probe.secret-in-properties=' + (properties.indexOf(token) >= 0))
                    * eval System.out.println('kaas.probe.heapdump-off=' + (cmdline.indexOf('-XX:-HeapDumpOnOutOfMemoryError') >= 0))
                    * eval System.out.println('kaas.probe.coredump-off=' + (cmdline.indexOf('-XX:-CreateCoredumpOnCrash') >= 0))
                    * eval System.out.println('kaas.probe.exit-on-oom=' + (cmdline.indexOf('-XX:+ExitOnOutOfMemoryError') >= 0))
                    * eval System.out.println('kaas.probe.error-file=' + (cmdline.indexOf('-XX:ErrorFile=/tmp/hs_err.log') >= 0))
                    * def coreLine = limits.split('\\n').filter(l => l.startsWith('Max core file size'))
                    * eval System.out.println('kaas.probe.core-limit=' + coreLine[0].replace(/\\s+/g, ' '))
                    # Descriptor 0 after the adapter closed it, and every other descriptor this JVM holds.
                    * def fds = Files.list(Path.of('/proc/self/fd')).toList()
                    * def targets = fds.stream().map(p => p.getFileName().toString() + '->' + Files.readSymbolicLink(p).toString()).toList()
                    * eval System.out.println('kaas.probe.fds=' + targets.toString())
                    * def stdinByte = System.in.read()
                    * eval System.out.println('kaas.probe.stdin-read=' + stdinByte)
                """);

        assertThat(EngineOutcome.of(outcome).verdict()).as("%s", outcome.observations()).isEqualTo(EngineOutcome.Verdict.PASSED);
        var seen = outcome.observations();
        assertThat(seen).containsEntry("kaas.probe.environ-read", "true")
                .containsEntry("kaas.probe.cmdline-read", "true")
                .containsEntry("kaas.probe.properties-read", "true");
        assertThat(seen).containsEntry("kaas.probe.secret-in-environ", "false")
                .containsEntry("kaas.probe.secret-in-cmdline", "false")
                .containsEntry("kaas.probe.secret-in-properties", "false");
        assertThat(seen).containsEntry("kaas.probe.heapdump-off", "true")
                .containsEntry("kaas.probe.coredump-off", "true")
                .containsEntry("kaas.probe.exit-on-oom", "true")
                .containsEntry("kaas.probe.error-file", "true");
        assertThat(seen.get("kaas.probe.core-limit")).as("core ulimit").startsWith("Max core file size 0 0");
        // System.in reads end-of-stream: nothing of the frame, or of anything after it, is readable.
        assertThat(seen).containsEntry("kaas.probe.stdin-read", "-1");
        String fds = seen.get("kaas.probe.fds");
        assertThat(fds).as("descriptors").isNotNull();
        assertThat(fds).as("descriptor 0 no longer refers to the frame's pipe").doesNotContain("0->pipe:");
        assertThat(pipesOtherThanStdoutAndStderr(fds)).as("no secret pipe or other channel survives").isEmpty();
        assertThat(fds).as("no socket is open at this point").doesNotContain("socket:");

        SandboxEvidence.append("secret-execution-evidence.txt",
                "secret_in_environment=" + seen.get("kaas.probe.secret-in-environ") + "\n"
                        + "secret_in_cmdline=" + seen.get("kaas.probe.secret-in-cmdline") + "\n"
                        + "secret_in_system_properties=" + seen.get("kaas.probe.secret-in-properties") + "\n"
                        + "engine_stdin_after_frame=" + seen.get("kaas.probe.stdin-read") + "\n");
    }

    @Test
    @Timeout(600)
    @DisplayName("an assertion failure and an exception quoting the secret are a FAILED test with nothing raw kept")
    void failureOutputIsRedactedAndIsATenantResult() {
        String secret = generated();
        SandboxOutcome outcome = execute(Map.of("API_TOKEN", secret), """
                Feature: failures that quote the secret
                  Scenario: an assertion whose message carries the value
                    * match kaas.secrets.API_TOKEN == 'not-the-secret'
                  Scenario: an exception whose message is the value
                    * def Failure = Java.type('java.lang.RuntimeException')
                    * def failure = new Failure(kaas.secrets.API_TOKEN)
                    * eval Java.type('java.lang.System').err.println(failure.toString())
                    * eval failure.printStackTrace()
                    * eval karate.fail(failure.getMessage())
                """);

        // A tenant's failing test is a completed run whose test outcome is FAILED -- not an infrastructure failure.
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.FAILED);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.redaction().stderrMatches()).as("the exception text reached stderr raw").isPositive();
        assertThat(contains(outcome.redaction().stdout(), secret)).isFalse();
        assertThat(contains(outcome.redaction().stderr(), secret)).isFalse();
    }

    @Test
    @Timeout(600)
    @DisplayName("a secret equal to a protocol word cannot change the verdict, and a forged line carrying a secret is refused")
    void theProtocolIsImmuneToTheSecret() {
        SandboxOutcome passedWord = execute(Map.of("WORD", "PASSED"), """
                Feature: a secret that happens to be the verdict word
                  Scenario: nothing unusual
                    * match kaas.secrets.WORD == 'PASSED'
                """);
        assertThat(EngineOutcome.of(passedWord).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
        assertThat(passedWord.redaction().stdout()).contains("kaas.karate-result.v1=[REDACTED]");

        String secret = generated();
        SandboxOutcome forged = execute(Map.of("API_TOKEN", secret), """
                Feature: a forged result line carrying the secret
                  Scenario: print a protocol line whose value is the secret
                    * eval Java.type('java.lang.System').out.println('kaas.karate-result.v1=' + kaas.secrets.API_TOKEN)
                """);
        EngineOutcome engine = EngineOutcome.of(forged);
        assertThat(engine.verdict()).isEqualTo(EngineOutcome.Verdict.MALFORMED);
        assertThat(contains(String.valueOf(engine.detail()), secret)).isFalse();
        assertThat(contains(forged.protocol().toString(), secret)).isFalse();
        assertThat(contains(forged.redaction().stdout(), secret)).isFalse();
    }

    @Test
    @Timeout(600)
    @DisplayName("a value longer than the frame boundaries and several values at once are delivered exactly")
    void severalValuesArriveExactly() {
        String pem = "-----BEGIN PRIVATE KEY-----\r\n" + generated() + "\r\n" + generated() + "\r\n-----END PRIVATE KEY-----\r\n";
        String unicode = "pässwörd-" + generated() + "-中文";
        SandboxOutcome outcome = execute(new LinkedHashMap<>(Map.of("PEM", pem, "UNICODE", unicode)), """
                Feature: several secrets
                  Scenario: print each one raw
                    * def System = Java.type('java.lang.System')
                    * eval System.out.println('pem=' + kaas.secrets.PEM)
                    * eval System.out.println('unicode=' + kaas.secrets.UNICODE)
                    * match karate.sizeOf(kaas.secrets) == 2
                """);
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
        // Two distinct matches: the exact PEM bytes, CRLF and all, and the exact multi-byte UTF-8 value.
        assertThat(outcome.redaction().stdoutMatches()).isGreaterThanOrEqualTo(2);
        assertThat(contains(outcome.redaction().stdout(), pem)).isFalse();
        assertThat(contains(outcome.redaction().stdout(), unicode)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> pipesOtherThanStdoutAndStderr(String fds) {
        List<String> others = new ArrayList<>();
        for (String entry : fds.replace("[", "").replace("]", "").split(",")) {
            String trimmed = entry.strip();
            if (trimmed.contains("pipe:") && !trimmed.startsWith("1->") && !trimmed.startsWith("2->")) {
                others.add(trimmed);
            }
        }
        return others;
    }

    private static boolean logsReadable(String containerId) {
        try {
            var collected = new ByteArrayOutputStream();
            SandboxTestSupport.docker().logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(false)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            collected.writeBytes(frame.getPayload());
                        }
                    })
                    .awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS);
            return collected.size() > 0;
        } catch (RuntimeException | InterruptedException refused) {
            // "configured logging driver does not support reading": there is no store to read from.
            return false;
        }
    }

    /** A value generated here, never written in source, carrying the gate's nonce when there is one. */
    static String generated() {
        byte[] entropy = new byte[18];
        RANDOM.nextBytes(entropy);
        String nonce = System.getProperty("kaas.test.sentinel-nonce", "local");
        return "kaas-sentinel-" + nonce + "-" + HexFormat.of().formatHex(entropy);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    /** Runs one feature with the given secrets: verify, frame, deliver, freeze, drop, engine frame, execute. */
    private SandboxOutcome execute(Map<String, String> secrets, String feature) {
        byte[] content = feature.getBytes(StandardCharsets.UTF_8);
        List<SourceBundle.ExpectedEntry> expected =
                List.of(new SourceBundle.ExpectedEntry("features/secret.feature", SourceBundle.sha256(content)));
        SourceBundle bundle = SourceBundle.verified(
                archiveOf(Map.of("features/secret.feature", content)), expected, SourceBundle.bundleDigest(expected));
        List<EngineInput.Secret> values = new ArrayList<>();
        secrets.forEach((key, value) -> values.add(new EngineInput.Secret(key, value.getBytes(StandardCharsets.UTF_8))));
        try (EngineInput input = EngineInput.of(values, null)) {
            var profile = SandboxSecurityProfile.withSource(
                    SandboxSecurityProfile.version1(
                            SandboxTestSupport.karateEngineImage(), SandboxTestSupport.mediatedRuntime()),
                    new SandboxSecurityProfile.SourceDelivery(
                            SourceFrame.of(bundle), SourceBundleContract.SOURCE_FILESYSTEM_BYTES, input));
            return SandboxTestSupport.launcher(profile, generation)
                    .run(new SandboxLaunchRequest(SyntheticProbe.KARATE_ENGINE, profile.version(), UUID.randomUUID()));
        }
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
