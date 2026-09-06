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
 * What the source filesystem actually enforces, measured inside a sandbox the mediating runtime confines.
 *
 * <h2>What this replaces</h2>
 *
 * <p>KAAS-18 delivered source by binding a host directory in. Under this runtime that arrives over the gofer
 * as a 9p mount carrying {@code ro} and nothing else, and a shebang script placed on it executed. Execution
 * was refused in production only because the materialiser writes files without an executable bit — a property
 * of the file, not of the filesystem, and the two are not the same claim.
 *
 * <p>The filesystem here is a tmpfs the sandbox creates for itself, populates from a byte stream, and then
 * remounts read-only before dropping every capability. Nothing is bound in from the host, so there is no
 * weaker second copy of the bytes for anything to find.
 *
 * <h2>Why the assertions are shaped this way</h2>
 *
 * <p>Each property is asserted at the level it is actually enforced, and no further. `ro` and `noexec` are
 * enforced by the mount and are asserted as such, `noexec` against a file that is genuinely executable and
 * demonstrably runs elsewhere. `nosuid` is reported by the mount but has no red path on this runtime, and
 * `nodev` is neither reported nor honoured — both are asserted in the direction they are true, so a runtime
 * release that changes either fails this suite and forces the adjudication to be redone rather than letting
 * a stale claim stand.
 */
@DisplayName("Mediated source filesystem boundary")
class MediatedSourceFilesystemBoundaryTests {

    private final String generation = "source-fs-" + UUID.randomUUID();

    @BeforeAll
    static void requireTheRuntime() {
        // FAIL, never skip. This suite is the entire evidence for the source filesystem; on a host without
        // the runtime there is no evidence, and reporting the same green either way is the failure mode the
        // gate exists to prevent.
        assertThat(SandboxTestSupport.docker().infoCmd().exec().getRuntimes())
                .as("the mediated source filesystem can only be measured under the runtime that provides it")
                .containsKey(ExecutionRuntimeType.GVISOR.daemonRuntimeName());
    }

    @Test
    @Timeout(600)
    @DisplayName("the source filesystem is a private tmpfs, frozen read-only, with no ingress beside it")
    void theSourceFilesystemIsPrivateFrozenAndAlone() {
        var observations = deliver(Map.of(
                "features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8),
                "features/nested/b.feature",
                        ("CRLF\r\nLF\ntab\there \"quotes\" \\backslash\\ emoji 🙂 combining é non-BMP 𝄞\n")
                                .getBytes(StandardCharsets.UTF_8)));

        assertThat(observations)
                .as("what the sandbox reported: %s", observations)
                .containsEntry("workload_outcome", "PASSED");
        assertThat(observations).containsEntry("source_filesystem", "tmpfs");
        assertThat(observations).containsEntry("source_entries_verified", "2");
        assertThat(observations).containsEntry("source_entry_mismatches", "0");
        assertThat(observations).containsEntry("source_format", SourceBundleContract.FORMAT_VERSION);

        // EXACTLY ONE SOURCE FILESYSTEM, AND NO GOFER MOUNT AT ALL. A weaker second copy of the same bytes
        // reachable from here would make every flag below cosmetic, because hostile code does not use the
        // path it was meant to.
        assertThat(observations).containsEntry("source_mounts_visible", "1");
        assertThat(observations)
                .as("no host directory is bound into a source-carrying sandbox")
                .containsEntry("source_ingress_visible", "0");
    }

    @Test
    @Timeout(600)
    @DisplayName("read-only is the filesystem's state, not the file's owner")
    void readOnlyIsEnforcedByTheFilesystem() {
        var observations = deliver(Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(observations)
                .as("mount options were: %s", observations.get("source_mount_options"))
                .containsEntry("source_mount_ro", "true");
        // Three claims, none of which implies the others. The mount says read-only; a write into it fails;
        // and the consumer cannot reopen it, which is what makes the first two durable rather than a state
        // the workload could undo.
        assertThat(observations).containsEntry("source_write_refused", "true");
        assertThat(observations).containsEntry("source_remount_refused", "true");
    }

    @Test
    @Timeout(600)
    @DisplayName("noexec refuses a file that is genuinely executable and runs on a permissive filesystem")
    void noexecIsEnforcedAgainstARealExecutable() {
        // THE ASSERTION KAAS-18 COULD NOT MAKE.
        //
        // The fixture has mode 0555 and valid shebang content, and the suite runs it twice: once on a
        // permissive tmpfs in the same sandbox, where it must execute, and once on the source filesystem,
        // where it must not. Without the first half the second proves only that something refused something.
        var observations = deliverWithBoundaryFixtures();
        // This case carries every key the gate reads back: the mount options, both comparisons, the
        // capability state and the counts.
        record(observations);

        assertThat(observations)
                .as("the control must actually run, or the refusal below means nothing: %s", observations)
                .containsEntry("exec_control", "EXECUTED");
        assertThat(observations).containsEntry("exec_hardened", "REFUSED");
        assertThat(observations)
                .as("mount options were: %s", observations.get("source_mount_options"))
                .containsEntry("source_mount_noexec", "true");
    }

    @Test
    @Timeout(600)
    @DisplayName("nosuid is reported, and this runtime performs no setuid transition on any filesystem")
    void nosuidIsReportedAndUnobservable() {
        // HONEST ABOUT WHAT IS AND IS NOT SHOWN.
        //
        // The mount carries nosuid. Its behavioural red path does not exist here: a setuid-root binary on a
        // fully permissive tmpfs, executed by an unprivileged user, does not escalate under this runtime,
        // while the identical test escalates under the baseline one. So there is no configuration in which
        // the flag's absence would be observable, and claiming the flag was demonstrated would be claiming a
        // test that cannot exist.
        //
        // Both halves are asserted. If a runtime release starts performing setuid transitions, the control
        // below changes and this suite fails, which is when the claim needs revisiting.
        var observations = deliverWithBoundaryFixtures();

        assertThat(observations).containsEntry("source_mount_nosuid", "true");
        assertThat(observations)
                .as("if this ever escalates, the runtime changed and nosuid needs a real red path")
                .containsEntry("suid_control", "NOT_ESCALATED");
        assertThat(observations).containsEntry("suid_hardened", "REFUSED");

        // The setuid count is asserted of a DELIVERY, not of this measurement. The boundary probe plants a
        // setuid fixture on the source filesystem deliberately -- that is the only reason it can say anything
        // about nosuid at all -- so counting zero here would mean the fixture was not there and the two
        // comparisons above were vacuous. What production must satisfy is that a real bundle brings none.
        assertThat(observations)
                .as("the measurement's own fixture must be present, or nothing above was measured")
                .containsEntry("source_setuid_files", "1");
        assertThat(deliver(Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8))))
                .as("a real bundle brings no setuid material of its own")
                .containsEntry("source_setuid_files", "0");
    }

    @Test
    @Timeout(600)
    @DisplayName("nodev is neither reported nor honoured by this runtime, and that is recorded as a gap")
    void nodevIsNotEnforcedByTheRuntime() {
        // THE GAP THIS SLICE DID NOT CLOSE, asserted as it stands so it cannot quietly become stale.
        //
        // gVisor does not implement MS_NODEV. The flag is absent from every tmpfs measured — one that asked
        // for it, the runtime's own read-only tmpfs, and this frozen mount — and a device node on such a
        // filesystem still behaves as a device, while the identical test under the baseline runtime refuses
        // the read.
        //
        // What stands in its place is not a mount flag: the filesystem is read-only, the consumer's bounding
        // set is empty so it holds no CAP_MKNOD, and the bundle format carries a path and bytes and cannot
        // express a device node. Three layers, none of them the one that was asked for. See
        // docs/security/mediated-source-filesystem.md.
        var observations = deliverWithBoundaryFixtures();

        assertThat(observations)
                .as("if this becomes true the runtime gained MS_NODEV and the adjudication must be redone")
                .containsEntry("source_mount_nodev", "false");
        assertThat(observations)
                .as("the layers that do hold: nothing irregular reached the filesystem")
                .containsEntry("source_irregular_entries", "0");
        assertThat(observations).containsEntry("final_consumer_capabilities", "EMPTY");
    }

    @Test
    @Timeout(600)
    @DisplayName("the consumer holds no capability the construction phase used")
    void theConsumerKeepsNothingFromConstruction() {
        // The construction phase runs as root and holds CAP_SYS_ADMIN, because closing a filesystem needs a
        // process that can. What matters is that none of it survives: the capability sets are read out of
        // /proc by the consumer itself, not asserted by the program that dropped them.
        var observations = deliver(Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(observations).containsEntry("final_consumer_capabilities", "EMPTY");
        // NoNewPrivs is not observable under this runtime -- /proc/self/status carries no such line -- which
        // is the same absence KAAS-17 recorded when it left NO_NEW_PRIVILEGES as UNSUPPORTED here. The
        // bootstrap does set it; what cannot be done is see it. Asserted as unsupported rather than quietly
        // dropped, so a runtime that starts exposing it fails this and the claim gets upgraded honestly.
        assertThat(observations).containsEntry("source_no_new_privileges", "unsupported");
        assertThat(observations).containsEntry("source_consumer_uid", "65534");
        assertThat(observations).containsEntry("source_consumer_gid", "65534");
        // And the capability it would need to undo the freeze is gone with the rest.
        assertThat(observations).containsEntry("source_remount_refused", "true");
    }

    @Test
    @Timeout(600)
    @DisplayName("bytes altered between framing and the freeze fail inside the sandbox")
    void alteredBytesFailAgainstTheManifest() {
        // The in-sandbox verifier recomputes every digest from the final filesystem rather than trusting what
        // the runner checked before framing. This alters one entry's bytes after the frame was built, which
        // nothing in production can do, and the sandbox catches it.
        byte[] original = "Feature: theoriginal\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(original)));
        SourceBundle bundle = SourceBundle.verified(
                archiveOf(new LinkedHashMap<>(Map.of("features/a.feature", original))),
                expected,
                SourceBundle.bundleDigest(expected));

        byte[] frame = SourceFrame.of(bundle);
        // Substitute the content in the frame without touching the digest that travels beside it.
        byte[] tampered = replace(frame, original, "Feature: SUBSTITUTED\n".getBytes(StandardCharsets.UTF_8));

        var observations = launch(new SandboxSecurityProfile.SourceDelivery(tampered, 8L * 1024 * 1024),
                SyntheticProbe.WORKLOAD_SOURCE_VERIFY);

        assertThat(observations).containsEntry("workload_outcome", "FAILED");
        assertThat(observations).containsEntry("source_entry_mismatches", "1");
    }

    /** Frames a bundle and runs the ordinary verifier over the filesystem it produces. */
    private Map<String, String> deliver(Map<String, byte[]> sources) {
        Map<String, byte[]> ordered = new LinkedHashMap<>(new java.util.TreeMap<>(sources));
        var expected = ordered.entrySet().stream()
                .map(entry -> new SourceBundle.ExpectedEntry(entry.getKey(), SourceBundle.sha256(entry.getValue())))
                .toList();
        SourceBundle bundle =
                SourceBundle.verified(archiveOf(ordered), expected, SourceBundle.bundleDigest(expected));
        return launch(
                new SandboxSecurityProfile.SourceDelivery(
                        SourceFrame.of(bundle), SourceBundleContract.SOURCE_FILESYSTEM_BYTES),
                SyntheticProbe.WORKLOAD_SOURCE_VERIFY);
    }

    /**
     * The same delivery, observed by the boundary probe instead of the verifier.
     *
     * <p>That probe plants fixtures with real modes on both the source filesystem and a permissive control,
     * which the production format cannot express and the production materialiser would never write. It is a
     * separate server-side workload for exactly that reason: the fixtures exist to make the filesystem's
     * behaviour distinguishable from the file's, and they must never be reachable from the delivery path.
     */
    private Map<String, String> deliverWithBoundaryFixtures() {
        Map<String, byte[]> ordered =
                new LinkedHashMap<>(Map.of("features/a.feature", "Feature: a\n".getBytes(StandardCharsets.UTF_8)));
        var expected = ordered.entrySet().stream()
                .map(entry -> new SourceBundle.ExpectedEntry(entry.getKey(), SourceBundle.sha256(entry.getValue())))
                .toList();
        SourceBundle bundle =
                SourceBundle.verified(archiveOf(ordered), expected, SourceBundle.bundleDigest(expected));
        return launch(
                new SandboxSecurityProfile.SourceDelivery(
                        SourceFrame.of(bundle), SourceBundleContract.SOURCE_FILESYSTEM_BYTES),
                SyntheticProbe.SOURCE_BOUNDARY);
    }

    /**
     * Writes what the sandbox observed where the gate can read it back.
     *
     * <p>The assertions above are the check; this is the record, and the gate re-reads these lines and fails
     * on them independently. It matters more here than it usually would because the interesting part of this
     * slice is which properties are enforced and which are not: a green job saying only "the suite passed"
     * would ask a reader to take that split on trust.
     *
     * <p>Only platform-defined keys and values. No logical path, no source byte, no capability material.
     */
    private static void record(Map<String, String> observations) {
        String directory = System.getenv("RUNNER_TEMP");
        if (directory == null || directory.isBlank()) {
            return; // Off CI there is no gate to read it.
        }
        StringBuilder evidence = new StringBuilder();
        evidence.append("source_verification=")
                .append("PASSED".equals(observations.get("workload_outcome")) ? "VALID" : "INVALID")
                .append('\n');
        for (String key : List.of(
                "source_filesystem", "source_mount_options", "source_mount_ro", "source_mount_noexec",
                "source_mount_nosuid", "source_mount_nodev", "source_write_refused", "source_remount_refused",
                "source_exec_refused", "exec_control", "exec_hardened", "suid_control", "suid_hardened",
                "source_setuid_files", "source_irregular_entries", "source_mounts_visible",
                "source_ingress_visible", "final_consumer_capabilities", "source_no_new_privileges",
                "source_consumer_uid", "source_entries_verified", "source_entry_mismatches")) {
            String value = observations.get(key);
            if (value != null) {
                evidence.append(key).append('=').append(value).append('\n');
            }
        }
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(directory, "source-delivery-evidence.txt"),
                    evidence.toString(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException unwritable) {
            throw new java.io.UncheckedIOException(unwritable);
        }
    }

    private Map<String, String> launch(
            SandboxSecurityProfile.SourceDelivery delivery, SyntheticProbe probe) {
        var profile = SandboxSecurityProfile.withSource(
                SandboxSecurityProfile.version1(SandboxTestSupport.probeImage(), ExecutionRuntimeType.GVISOR),
                delivery);
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(probe, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        return outcome.observations();
    }

    private static byte[] replace(byte[] haystack, byte[] find, byte[] with) {
        if (find.length != with.length) {
            throw new IllegalArgumentException("The substitution must not change the framing.");
        }
        byte[] copy = haystack.clone();
        for (int i = 0; i + find.length <= copy.length; i++) {
            if (java.util.Arrays.equals(copy, i, i + find.length, find, 0, find.length)) {
                System.arraycopy(with, 0, copy, i, with.length);
                return copy;
            }
        }
        throw new IllegalStateException("The frame did not carry the bytes it was built from.");
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
