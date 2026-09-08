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
 * Real Karate 2.1.2, executing real tenant features, through the real delivery path.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>Every earlier slice measured the boundary with platform-written probes. This one runs the product: a
 * bundle is verified, framed, delivered over stdin, written by the bootstrap, frozen, and handed to a JVM that
 * starts Karate and runs whatever the tenant wrote. Nothing here is simulated, and nothing here is a stand-in
 * for the engine — an engine test that does not start the engine is a test of the harness.
 *
 * <h2>Why it insists on saying which Karate ran</h2>
 *
 * <p>An adapter that printed {@code PASSED} without loading Karate would satisfy an outcome assertion
 * perfectly. So the version banner is asserted alongside the verdict: the evidence has to name the engine it
 * came from, or it is evidence of the protocol and not of the execution.
 *
 * <h2>Why it runs under the mediating runtime, and cannot run under the baseline one</h2>
 *
 * <p>The engine executes on a source filesystem the bootstrap populates and then closes behind itself, and
 * that closing remount is refused under the baseline runtime — measured, and recorded in
 * docs/architecture/mediated-source-filesystem-evaluation.md as {@code bootstrap_failure=FREEZE} for the
 * identical request under runc. So there is no baseline-runtime version of this suite to write.
 *
 * <p>It was first written as one, and the way that failed is worth keeping: it was green on macOS, where
 * Docker Desktop's VM carries no AppArmor policy and the remount therefore succeeds, and ten of its eleven
 * tests failed on the first Linux runner that saw it. The bootstrap reported FREEZE and exited zero, no JVM
 * ever started, and those ten failed on the absence of an engine rather than on anything an engine did. The
 * eleventh passed, because it asserts an ABSENT verdict and an absent engine produces one — which is the
 * sharpest illustration in the suite of why a green test is not evidence on its own. <strong>A green local
 * build proves nothing about this suite</strong>: only the {@code karate-execution-gate} job does, and only
 * because it installs {@code runsc}.
 */
@DisplayName("Secret-free Karate execution")
class KarateExecutionTests {

    private final String generation = "karate-exec-" + UUID.randomUUID();

    // ------------------------------------------------------------------ the success chain

    @Test
    @Timeout(600)
    @DisplayName("real Karate runs the authorized feature and the platform reads a PASSED verdict")
    void realKarateRunsTheAuthorizedFeature() {
        SandboxOutcome outcome = execute(Map.of(
                "features/passing.feature",
                """
                Feature: a tenant's passing suite
                  Scenario: an assertion that holds
                    * def sum = 1 + 1
                    * match sum == 2
                """));

        // Recorded BEFORE the assertions, so a run that named the wrong engine leaves the wrong name where a
        // reader can see it rather than leaving nothing at all.
        record(outcome);

        assertThat(outcome.failure()).as("%s", outcome.observations()).isEmpty();
        // ANTI-VACUITY. Without this the suite would pass against an adapter that printed a verdict and never
        // loaded an engine, which is the single most likely way this evidence could be worthless.
        assertThat(outcome.observations())
                .as("the run must be able to name the engine that produced it")
                .containsEntry("kaas.engine", "karate 2.1.2");
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
    }

    @Test
    @Timeout(600)
    @DisplayName("a failing assertion is the tenant's result, not the platform's error")
    void aFailingAssertionIsATenantResult() {
        SandboxOutcome outcome = execute(Map.of(
                "features/failing.feature",
                """
                Feature: a tenant's failing suite
                  Scenario: an assertion that does not hold
                    * match 1 == 2
                """));

        assertThat(outcome.failure()).as("%s", outcome.observations()).isEmpty();
        // The distinction the whole two-outcome model exists for. A tenant whose test fails has had a
        // successful execution; reporting that as an engine error would make the platform look broken every
        // time a customer wrote a failing test.
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.FAILED);
    }

    @Test
    @Timeout(600)
    @DisplayName("every authorized feature runs, not merely the first one")
    void everyAuthorizedFeatureRuns() {
        // The first passes and the second fails. A runner that executed only the head of the list would
        // report PASSED, and no single-feature test could tell the difference.
        SandboxOutcome outcome = execute(new LinkedHashMap<>(Map.of(
                "features/a-first.feature",
                """
                Feature: first
                  Scenario: holds
                    * match 1 == 1
                """,
                "features/b-second.feature",
                """
                Feature: second
                  Scenario: does not hold
                    * match 1 == 2
                """)));

        assertThat(outcome.failure()).as("%s", outcome.observations()).isEmpty();
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.FAILED);
    }

    // ------------------------------------------------------------------ the hostile directions

    @Test
    @Timeout(600)
    @DisplayName("a feature that prints the result key cannot forge a pass")
    void aForgedResultIsRefusedRatherThanBelieved() {
        // The obvious attack on a text protocol: say the answer before the adapter does. It cannot be
        // prevented -- tenant code owns stdout -- so the platform refuses a stream that answered twice
        // instead of choosing between the answers.
        SandboxOutcome outcome = execute(Map.of(
                "features/forger.feature",
                """
                Feature: a tenant claiming its own result
                  Scenario: write the platform's protocol line to stdout and then fail
                    * def System = Java.type('java.lang.System')
                    * eval System.out.println('kaas.karate-result.v1=PASSED')
                    * match 1 == 2
                """));

        EngineOutcome engine = EngineOutcome.of(outcome);
        assertThat(outcome.duplicatedObservations())
                .as("the collector must have seen the key twice for the refusal to be possible")
                .contains(EngineOutcome.PROTOCOL);
        assertThat(engine.verdict())
                .as("a forged pass must become an infrastructure failure, never a pass")
                .isEqualTo(EngineOutcome.Verdict.MALFORMED);
        assertThat(engine.completed()).isFalse();
    }

    @Test
    @Timeout(600)
    @DisplayName("the engine runs with no environment at all, so no host variable can be read out of it")
    void theEngineReceivesNoEnvironment() {
        // Not "no secrets": NO VARIABLES. The build puts credential-shaped canaries in this JVM's own
        // environment precisely so that an inherited environment would be detectable here, and the bootstrap's
        // execve passes an empty envp. Asserting the count is zero is a stronger claim than asserting the
        // canaries are absent, and it is the one the design actually supports.
        SandboxOutcome outcome = execute(Map.of(
                "features/environment.feature",
                """
                Feature: what the engine can see of its host
                  Scenario: count the environment
                    * def System = Java.type('java.lang.System')
                    * def env = System.getenv()
                    * eval System.out.println('kaas.probe.env-names=' + env.keySet())
                    * match env.containsKey('AWS_SECRET_ACCESS_KEY') == false
                """));

        // MEASURED, not assumed. The engine's environment is exactly three names, and every one of them is
        // created after the boundary closes: two by the shell that performs the handover and one by the JVM
        // launcher. Nothing from the host, nothing from the image's own ENV, and nothing from the command.
        assertThat(outcome.observations().get("kaas.probe.env-names"))
                .as("an environment with a fourth name is an environment something reached into")
                .isEqualTo("[LD_LIBRARY_PATH, SHLVL, PWD]");
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
        assertThat(outcome.observations().values())
                .as("no value anywhere in the sandbox's output may carry the canary")
                .noneMatch(value -> value.contains("kaas-canary-must-not-cross-the-boundary"));
    }

    @Test
    @Timeout(600)
    @DisplayName("the frozen source refuses a write, and the same write elsewhere succeeds")
    void theSourceIsReadOnlyToTheEngine() {
        // THREE AXES, because two are not evidence. A catch block reports REFUSED for a read-only filesystem
        // and for a JavaScript TypeError equally well, so the refusal alone would be satisfied by a probe that
        // never reached a filesystem at all. The scratch write is the positive control that says the mechanism
        // works. And the mount's own options, read by the engine out of its own /proc, are what say WHICH
        // refusal happened.
        //
        // The options rather than the exception's text, deliberately. The earlier version of this asserted the
        // message contained "Read-only file system", which is what the baseline runtime's kernel says and is
        // NOT what the mediating runtime says: under runsc the same write returns a refusal carrying only the
        // path. An assertion on a diagnostic string is an assertion about which kernel wrote it; `ro` in
        // /proc/self/mountinfo is the control itself.
        SandboxOutcome outcome = execute(Map.of(
                "features/write-source.feature",
                """
                Feature: writing to the source that is running
                  Scenario: attempt a write beside the executing feature, and the same write on scratch
                    * def System = Java.type('java.lang.System')
                    * def Files = Java.type('java.nio.file.Files')
                    * def Path = Java.type('java.nio.file.Path')
                    * def bytes = Java.type('java.lang.String').valueOf('planted').getBytes()
                    * def attempt = function(target){ try { Files.write(Path.of(target), bytes); return 'WROTE' } catch (e) { return 'REFUSED:' + e } }
                    * def mountLine = function(target){ var lines = Files.readAllLines(Path.of('/proc/self/mountinfo')); var found = 'ABSENT'; for (var i = 0; i < lines.size(); i++) { var l = '' + lines.get(i); if (l.indexOf(' ' + target + ' ') > 0) { found = l } } return found }
                    * def onSource = attempt('/kaas/source/files/features/planted.feature')
                    * def onScratch = attempt('/tmp/kaas-engine/planted.feature')
                    * eval System.out.println('kaas.probe.source-write=' + onSource)
                    * eval System.out.println('kaas.probe.scratch-write=' + onScratch)
                    * eval System.out.println('kaas.probe.source-mount=' + mountLine('/kaas/source'))
                """));

        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);
        // The positive control first: if this is not WROTE, the probe never demonstrated it can write at all
        // and the refusal below means nothing.
        assertThat(outcome.observations())
                .as("the probe must be able to write somewhere, or its refusal is not a measurement")
                .containsEntry("kaas.probe.scratch-write", "WROTE");
        assertThat(outcome.observations().get("kaas.probe.source-write"))
                .as("the write beside the executing feature must be refused")
                .startsWith("REFUSED:");
        // And what refused it. The engine reads the flags of the filesystem it is running on, out of its own
        // /proc, so this is the freeze itself rather than a message about it.
        assertThat(mountOptions(outcome, "kaas.probe.source-mount"))
                .as("the source the engine runs on must be closed, seen from inside the engine: %s",
                        outcome.observations().get("kaas.probe.source-mount"))
                .contains("ro", "noexec", "nosuid");
    }

    @Test
    @Timeout(600)
    @DisplayName("the engine has no route off the host under the default deny-all policy")
    void theEngineHasNoRoute() {
        // Karate's HTTP client is the one capability a test tool is expected to have, and under DENY_ALL it
        // must have none. Reported as a tenant FAILED rather than as an engine error: the engine worked
        // perfectly, and what failed was the tenant's request.
        SandboxOutcome outcome = execute(Map.of(
                "features/network.feature",
                """
                Feature: reaching off the host
                  Scenario: an ordinary karate http call
                    Given url 'http://example.com'
                    When method get
                    Then status 200
                """));

        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.FAILED);
    }

    @Test
    @Timeout(600)
    @DisplayName("what tenant features can actually reach, enumerated rather than asked after one at a time")
    void theHostileCapabilitySurfaceIsWhatWasAdjudicated() {
        // ONE run, every question. Asking them separately would mean each probe measured a different sandbox,
        // and the interesting claims here are about a single process: that interop works AND the platform's
        // classes are absent from it, that scratch is writable AND not executable.
        SandboxOutcome outcome = execute(Map.of(
                "features/hostile.feature",
                """
                Feature: what tenant code can reach through the engine
                  Scenario: enumerate the surface
                    * def System = Java.type('java.lang.System')
                    * def say = function(k,v){ System.out.println('kaas.probe.' + k + '=' + v) }
                    * def attempt = function(f){ try { return f() } catch (e) { return 'REFUSED:' + e } }
                    * def Files = Java.type('java.nio.file.Files')
                    * def Path = Java.type('java.nio.file.Path')
                    * def PB = Java.type('java.lang.ProcessBuilder')
                    * def mountLine = function(target){ var lines = Files.readAllLines(Path.of('/proc/self/mountinfo')); var found = 'ABSENT'; for (var i = 0; i < lines.size(); i++) { var l = '' + lines.get(i); if (l.indexOf(' ' + target + ' ') > 0) { found = l } } return found }
                    * eval say('java-type', attempt(function(){ return Java.type('java.lang.ProcessBuilder') != null ? 'REACHABLE' : 'NULL' }))
                    * eval say('platform-launcher', attempt(function(){ return Java.type('com.kaas.runner.sandbox.DockerSandboxLauncher') != null ? 'REACHABLE' : 'NULL' }))
                    * eval say('process-spawn', attempt(function(){ return 'EXIT:' + new PB(['/bin/echo','hi']).start().waitFor() }))
                    * eval say('dns', attempt(function(){ return 'RESOLVED:' + Java.type('java.net.InetAddress').getByName('example.com').getHostAddress() }))
                    * eval say('socket-literal-ip', attempt(function(){ var SC = Java.type('java.nio.channels.SocketChannel'); var ISA = Java.type('java.net.InetSocketAddress'); SC.open().connect(new ISA('93.184.216.34', 80)); return 'CONNECTED' }))
                    * eval say('scratch-write', attempt(function(){ Files.write(Path.of('/tmp/kaas-engine/x.sh'), Java.type('java.lang.String').valueOf('#!/bin/sh').getBytes()); return 'WROTE' }))
                    * eval say('scratch-exec', attempt(function(){ return 'EXIT:' + new PB(['/bin/sh','-c','chmod +x /tmp/kaas-engine/x.sh && /tmp/kaas-engine/x.sh']).start().waitFor() }))
                    * eval say('remount', attempt(function(){ return 'EXIT:' + new PB(['/bin/sh','-c','mount -o remount,rw /kaas/source']).start().waitFor() }))
                    * eval say('post-remount-write', attempt(function(){ Files.write(Path.of('/kaas/source/files/planted.feature'), Java.type('java.lang.String').valueOf('x').getBytes()); return 'WROTE' }))
                    * eval say('post-remount-mount', mountLine('/kaas/source'))
                """));

        Map<String, String> probe = outcome.observations();
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.PASSED);

        // THE POSITIVE CONTROL FIRST. Java interop works, so every refusal below is a refusal rather than a
        // karate-js limitation. That distinction is not hypothetical: an earlier version of this probe
        // constructed a Socket with `new` and reported REFUSED because karate-js does not support that
        // constructor form — which would have been recorded as containment the platform does not have.
        assertThat(probe).containsEntry("kaas.probe.java-type", "REACHABLE");

        // THE CLASSPATH IS THE CONTROL. This is the single most load-bearing measurement in the slice: the
        // platform's own launcher is not merely unused by tenant code, it is not there to be used.
        assertThat(probe.get("kaas.probe.platform-launcher"))
                .as("no platform class may be resolvable from tenant source")
                .startsWith("REFUSED:")
                .contains("class not found");

        // ACCEPTED, NOT CONTAINED. ADR-032 allowed process spawning: the PID ceiling bounds it and it dies
        // with the sandbox. Recorded here so the acceptance stays visible rather than being rediscovered.
        assertThat(probe).containsEntry("kaas.probe.process-spawn", "EXIT:0");
        assertThat(probe).containsEntry("kaas.probe.scratch-write", "WROTE");

        // DENIED BY THE SANDBOX. 126 is the shell's "found but not executable": noexec on the scratch
        // filesystem, measured through the product rather than through a platform probe.
        assertThat(probe).containsEntry("kaas.probe.scratch-exec", "EXIT:126");

        // DENIED BY TOPOLOGY. The literal address matters -- a hostname would fail at DNS and prove only that
        // there is no resolver, which is a weaker claim than there being no route.
        assertThat(probe.get("kaas.probe.dns")).startsWith("REFUSED:");
        assertThat(probe.get("kaas.probe.socket-literal-ip"))
                .as("no route, established without relying on name resolution")
                .startsWith("REFUSED:");

        // THE FREEZE HOLDS. The remount fails, the write fails after it, and the mount is still `ro` when the
        // dust settles -- all three, because a remount that failed for some unrelated reason would leave the
        // second question unanswered, and a refusal is a message while the mount options are the control.
        assertThat(probe).containsEntry("kaas.probe.remount", "EXIT:1");
        assertThat(probe.get("kaas.probe.post-remount-write")).startsWith("REFUSED:");
        assertThat(mountOptions(outcome, "kaas.probe.post-remount-mount"))
                .as("the source must still be closed after tenant code tried to open it: %s",
                        probe.get("kaas.probe.post-remount-mount"))
                .contains("ro", "noexec", "nosuid");
    }

    // ------------------------------------------------------------------ the ways a run can end badly

    @Test
    @Timeout(600)
    @DisplayName("a feature that never returns is killed, and killing it is not a pass")
    void anEndlessFeatureIsKilledAndReportsNoVerdict() {
        SandboxOutcome outcome = execute(Map.of(
                "features/endless.feature",
                """
                Feature: a suite that does not end
                  Scenario: spin
                    * def spin = function(){ while (true) { } }
                    * eval spin()
                """));

        assertThat(outcome.failure())
                .as("the wall-clock deadline is what ends this, and it must say so")
                .contains(SandboxFailure.SANDBOX_TIMEOUT);
        // The important half. A killed engine reported nothing, and nothing must never resolve to PASSED.
        EngineOutcome engine = EngineOutcome.of(outcome);
        assertThat(engine.verdict()).isEqualTo(EngineOutcome.Verdict.ABSENT);
        assertThat(engine.completed()).isFalse();
    }

    @Test
    @Timeout(600)
    @DisplayName("a feature that exits the JVM early cannot turn a zero exit status into a pass")
    void anEarlyExitIsNotAPass() {
        // The sharpest version of the absent-evidence rule. System.exit(0) leaves a container that exited
        // cleanly, having run no assertion and printed no verdict. Anything deriving an outcome from the exit
        // status reports PASSED here, which is why the runner derives it from the protocol line instead.
        SandboxOutcome outcome = execute(Map.of(
                "features/early-exit.feature",
                """
                Feature: leaving before the verdict
                  Scenario: exit cleanly mid-suite
                    * def System = Java.type('java.lang.System')
                    * eval System.exit(0)
                    * match 1 == 2
                """));

        assertThat(outcome.exitCode()).as("the container really did exit zero").contains(0);
        assertThat(EngineOutcome.of(outcome).verdict()).isEqualTo(EngineOutcome.Verdict.ABSENT);
    }

    @Test
    @Timeout(600)
    @DisplayName("a feature that floods stdout is truncated, and the flood does not become a verdict")
    void afloodedStreamIsBoundedAndNotBelieved() {
        // Output is bounded in the launcher's memory, so a hostile suite cannot exhaust the runner by
        // printing. The consequence that matters here is what the truncation does to the result: the
        // adapter's verdict comes last, so a flood removes it -- and removing it must mean ABSENT.
        SandboxOutcome outcome = execute(Map.of(
                "features/flood.feature",
                """
                Feature: filling the platform's buffer
                  Scenario: print far past the ceiling
                    * def System = Java.type('java.lang.System')
                    * def line = Java.type('java.lang.String').valueOf('f').repeat(4000)
                    * def flood = function(){ for (var i = 0; i < 200; i++) { System.out.println('kaas.probe.flood' + i + '=' + line) } }
                    * eval flood()
                """));

        assertThat(outcome.outputTruncated())
                .as("200 lines of 4000 characters must exceed the collector's ceiling")
                .isTrue();
        assertThat(EngineOutcome.of(outcome).completed())
                .as("a truncated stream is not one the platform can read a verdict out of")
                .isFalse();
    }

    // ------------------------------------------------------------------ reading what the engine reported

    /**
     * Writes the engine's own identity and verdict where the gate can read them back.
     *
     * <p>The same arrangement the mediated source-delivery suite uses, and here it closes a hole rather than
     * merely documenting one. The gate's anti-vacuity check used to grep the JUnit XML for
     * {@code karate 2.1.2} — a string that only appears there when the assertion for it FAILS. So the check
     * could be satisfied by a broken run and never by a working one, and nothing noticed until the suite
     * passed for the first time and the gate went red on a green build.
     *
     * <p>Both lines come from {@code outcome}, so they are what the sandbox reported rather than what this
     * test expected. A constant here would make the gate assert the test's own opinion.
     */
    private static void record(SandboxOutcome outcome) {
        String directory = System.getenv("RUNNER_TEMP");
        if (directory == null || directory.isBlank()) {
            return; // Off CI there is no gate to read it.
        }
        String evidence = "engine_identity=" + outcome.observations().getOrDefault("kaas.engine", "ABSENT")
                + "\nengine_verdict=" + EngineOutcome.of(outcome).verdict() + "\n";
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(directory, "karate-execution-evidence.txt"),
                    evidence,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException unwritable) {
            throw new java.io.UncheckedIOException(unwritable);
        }
    }

    /**
     * The per-mount options of a {@code /proc/self/mountinfo} line the engine reported.
     *
     * <p>Parsed here rather than in the feature, because the feature is tenant-authored code running in a
     * hostile process: what it prints is untrusted data, and untrusted data is split apart on the trusted side
     * of the boundary. Field five is the per-mount option list, which is where {@code ro} lives; the
     * superblock options after the separator are a different claim about a different object.
     *
     * <p>An absent or unparseable line is returned as an empty list rather than as a skipped assertion. A
     * mount the engine could not see is a mount nothing measured, and that must fail.
     */
    private static List<String> mountOptions(SandboxOutcome outcome, String key) {
        String line = outcome.observations().get(key);
        if (line == null) {
            return List.of();
        }
        String[] fields = line.split(" ");
        // ID, parent, major:minor, root, mount point, options -- six fields before any optional one.
        if (fields.length < 6) {
            return List.of();
        }
        return List.of(fields[5].split(","));
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Runs a bundle end to end: verify, frame, deliver, freeze, execute.
     *
     * <p>Deliberately the production path rather than a mounted directory. A bind mount would test Karate and
     * nothing else; what needs testing is that an engine can run on a filesystem the bootstrap built and
     * closed, because that filesystem is the thing four slices of adjudication are about.
     */
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
                // GVISOR, and not a preference. The bootstrap's freeze is a `mount`, and the baseline runtime
                // refuses it: without this every test below measures a container that never started an engine.
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
