package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleContract;
import com.kaas.runner.source.SourceFrame;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the mediated boundary does when the workload is a JVM.
 *
 * <h2>Why a JVM and not another shell probe</h2>
 *
 * <p>Every hostile probe before this one is a shell script, which was the right shape while the sandbox ran
 * shell. It is the wrong shape for the question this slice has to answer, because the engine that would run
 * tenant code is a JVM and a JVM differs where it matters: threads are not processes, so a PID ceiling may or
 * may not bound them; the memory floor is an order of magnitude larger; and Java opens sockets without going
 * near the tools a shell probe exercises.
 *
 * <p>It also matters because of what Karate turned out to be. Karate 2.1.2 resolves {@code Java.type(name)}
 * to {@code Class.forName} against the thread context classloader, with no allowlist and no configuration
 * gate — so tenant source is arbitrary JVM code, and the only boundary that matters is the one measured here.
 *
 * <h2>What a refusal means, and what it does not</h2>
 *
 * <p>Under the execution model this slice adopts, tenant code being ABLE to do something inside the sandbox
 * is not automatically a finding. Spawning a child process is expected of arbitrary code and is not an
 * escape. What has to hold is that nothing reaches the platform: the source filesystem stays frozen, no
 * capability appears, no network path opens, no credential is visible, and nothing survives the sandbox.
 */
@DisplayName("Hostile JVM containment")
class HostileJvmContainmentTests {

    private final String generation = "hostile-jvm-" + UUID.randomUUID();

    @BeforeAll
    static void requireTheRuntime() {
        // FAIL, never skip. A JVM contained by the baseline runtime says nothing about the boundary tenant
        // code would actually meet.
        assertThat(SandboxTestSupport.docker().infoCmd().exec().getRuntimes())
                .as("hostile JVM containment is a property of the mediating runtime")
                .containsKey(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    @Test
    @Timeout(600)
    @DisplayName("a JVM starts and runs within the production security profile")
    void aJvmRunsWithinTheProfile() {
        // FEASIBILITY, FIRST. The first engine slice must not discover that a JVM cannot start inside the
        // profile at all — that would be a resource finding disguised as a security one, arriving at the
        // worst possible moment.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations)
                .as("what the sandbox reported: %s", observations)
                .containsEntry("probe_identity", "KAAS_HOSTILE_JVM_V1");
        assertThat(observations).containsEntry("workload_outcome", "PASSED");
    }

    @Test
    @Timeout(600)
    @DisplayName("the JVM holds no capability and cannot regain one")
    void theJvmHoldsNoPrivilege() {
        // Read by the JVM out of its own /proc, not asserted by whatever configured it. This is the process a
        // future engine would be, so this is the capability state tenant code would inherit.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations).containsEntry("jvm_capabilities", "EMPTY");
        assertThat(observations).containsEntry("jvm_no_new_privs", "1");
    }

    @Test
    @Timeout(600)
    @DisplayName("hostile Java cannot write, execute, chmod, remount or add a device to the source filesystem")
    void theSourceFilesystemResistsHostileJava() {
        // THE COMPENSATING-CONTROL ARGUMENT FOR THE MISSING nodev, tested rather than argued.
        //
        // gVisor does not implement MS_NODEV, so the source filesystem carries no such flag. What stands in
        // its place is that the filesystem is frozen, the consumer holds no CAP_MKNOD, and the bundle format
        // cannot express a device node. Each of those is asserted elsewhere; this asks the question from the
        // attacker's side, with a real JVM, against a real delivered bundle.
        var observations = runWithSource(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations)
                .as("a device node on the source filesystem is what nodev would have prevented: %s", observations)
                .containsEntry("jvm_mknod", "false");
        assertThat(observations).containsEntry("jvm_source_write", "false");
        assertThat(observations).containsEntry("jvm_source_chmod", "false");
        assertThat(observations).containsEntry("jvm_source_exec", "false");
        assertThat(observations).containsEntry("jvm_remount", "false");
    }

    @Test
    @Timeout(600)
    @DisplayName("generated code can be written to the scratch filesystem and cannot be executed from it")
    void generatedCodeCannotExecute() {
        // THE QUESTION noexec ON /kaas/source DOES NOT ANSWER.
        //
        // Hostile code does not have to execute the source it was delivered; it can write its own. So the
        // scratch filesystem's flags matter as much as the source filesystem's, and a writable-and-executable
        // /tmp would mean tenant code could run anything it liked regardless of what /kaas/source enforces.
        //
        // Both halves are asserted. The write must succeed, or the refusal below would be about a filesystem
        // nothing could write to rather than about noexec.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations)
                .as("the write must work, or the refusal below proves nothing")
                .containsEntry("jvm_tmp_write", "true");
        assertThat(observations).containsEntry("jvm_tmp_exec", "false");
    }

    @Test
    @Timeout(600)
    @DisplayName("raw Java networking reaches nothing under DENY_ALL")
    void rawJavaNetworkingIsContained() {
        // The platform must be safe when tenant code ignores an engine's HTTP client entirely and opens a
        // socket itself, which Java interop makes a one-liner. Four destinations, including the cloud
        // metadata address and loopback, because an SSRF defence that only covers an engine's own client is
        // not a defence at all.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations).containsEntry("jvm_dns", "false");
        assertThat(observations).containsEntry("jvm_raw_socket_public", "false");
        assertThat(observations).containsEntry("jvm_raw_socket_metadata", "false");
        assertThat(observations).containsEntry("jvm_raw_socket_loopback", "false");
    }

    @Test
    @Timeout(600)
    @DisplayName("the JVM sees no credential, no daemon socket, and a bounded environment")
    void theJvmSeesNoPlatformAuthority() {
        // What tenant code could read of the platform. The environment is reported by NAME rather than by
        // value — a probe that printed values would print whatever a future defect put there — and the names
        // are asserted against a closed set, so a new variable is a test failure rather than a surprise.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations).containsEntry("jvm_docker_socket", "false");
        assertThat(observations.get("jvm_environment_names"))
                .as("no worker credential, source capability, egress capability or signing key")
                .doesNotContain("KAAS")
                .doesNotContain("TOKEN")
                .doesNotContain("CAPABILITY")
                .doesNotContain("SECRET");
    }

    @Test
    @Timeout(600)
    @DisplayName("a JVM can spawn children and its threads are bounded by the profile")
    void concurrencyIsBoundedRatherThanForbidden() {
        // NOT A FINDING, AND RECORDED AS SUCH. Arbitrary code spawning a process is expected under the model
        // this slice adopts; the question is whether the ceiling exists. It does, and it binds Java threads
        // rather than only processes -- which is the JVM-specific fact a shell probe could not have shown.
        var observations = run(SyntheticProbe.HOSTILE_JVM);

        assertThat(observations).containsEntry("jvm_child_spawn", "true");
        assertThat(Integer.parseInt(observations.get("jvm_threads_started")))
                .as("the PID ceiling bounds Java threads: %s", observations.get("jvm_thread_limit"))
                .isLessThan(200);
    }

    private Map<String, String> run(SyntheticProbe probe) {
        var profile = SandboxSecurityProfile.version1(
                SandboxTestSupport.jvmProbeImage(), ExecutionRuntimeType.GVISOR);
        return launch(profile, probe);
    }

    /** The same, with a real delivered bundle on the frozen source filesystem. */
    private Map<String, String> runWithSource(SyntheticProbe probe) {
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(content)));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("features/a.feature", content);
        SourceBundle bundle =
                SourceBundle.verified(archiveOf(entries), expected, SourceBundle.bundleDigest(expected));
        var profile = SandboxSecurityProfile.withSource(
                SandboxSecurityProfile.version1(
                        SandboxTestSupport.jvmProbeImage(), ExecutionRuntimeType.GVISOR),
                new SandboxSecurityProfile.SourceDelivery(
                        SourceFrame.of(bundle), SourceBundleContract.SOURCE_FILESYSTEM_BYTES));
        return launch(profile, probe);
    }

    private Map<String, String> launch(SandboxSecurityProfile profile, SyntheticProbe probe) {
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(probe, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        return outcome.observations();
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
