package com.kaas.runner.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.sandbox.SandboxFailure;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the gate can go red.
 *
 * <p>Every other test in this module runs a real sandbox and asserts the gate passes, which establishes only
 * that the gate agrees with a correctly configured host. It cannot distinguish a working gate from
 * {@code blocksRelease() -> return false}, and for the whole of this slice it did not: that exact mutation left
 * the entire suite green, as did {@code passed() -> return true}. A gate that has only ever been observed green
 * is not evidence of anything.
 *
 * <p>So these tests drive the gate from a fake launcher and take away one control's evidence at a time. There
 * are two ways to take it away, and both used to be read as success: reporting that the control is off, and
 * reporting nothing at all. Absence is the more important of the two, because it is what a sandbox that failed
 * to start, a daemon that went away, or an output stream that never drained all look like.
 */
class SecurityGateRedPathTests {

    /** A sandbox that reports every control enforced. The baseline the mutations below are made against. */
    private static Map<String, String> healthyInspect() {
        Map<String, String> observations = new LinkedHashMap<>();
        observations.put("probe_tooling", "present");
        observations.put("uid", "65534");
        observations.put("gid", "65534");
        observations.put("probe_owned_owner", "65534");
        observations.put("rootfs_writable", "false");
        observations.put("tmp_writable", "true");
        observations.put("mount_points", "/,/dev,/dev/shm,/proc,/proc/kcore,/proc/keys,/proc/timer_list,"
                + "/proc/scsi,/sys,/sys/firmware,/tmp,/etc/hosts");
        observations.put("mount_writable", "/dev,/dev/shm,/proc,/tmp");
        observations.put("unix_sockets", "");
        observations.put("cap_eff", "0000000000000000");
        observations.put("cap_prm", "0000000000000000");
        observations.put("cap_bnd", "0000000000000000");
        observations.put("cap_inh", "0000000000000000");
        observations.put("cap_amb", "0000000000000000");
        observations.put("no_new_privs", "1");
        observations.put("seccomp", "2");
        observations.put("uid_map", "0:0:4294967295,");
        observations.put("block_device_nodes", "");
        observations.put("char_device_nodes", "/dev/null,/dev/zero,/dev/urandom");
        observations.put("env_names", "HOME,HOSTNAME,KAAS_SANDBOX,PATH,PWD,SHLVL");
        observations.put("env_count", "6");
        return observations;
    }

    private static Map<String, String> healthyNetwork() {
        Map<String, String> observations = new LinkedHashMap<>();
        observations.put("probe_tooling", "present");
        observations.put("net_global_addresses", "0");
        observations.put("net_default_routes", "0");
        observations.put("net_interfaces_up", "0");
        List.of("net_public", "net_private", "net_metadata", "net_link_local",
                        "net_metadata_v6", "net_docker_host", "net_gateway")
                .forEach(key -> observations.put(key, "unreachable"));
        observations.put("net_dns", "unresolvable");
        return observations;
    }

    private static Map<String, String> healthyProcesses() {
        return new LinkedHashMap<>(Map.of(
                "probe_tooling", "present",
                "processes_requested", "200",
                "processes_started", "63"));
    }

    private static Map<String, String> healthyMemory() {
        return new LinkedHashMap<>(Map.of(
                "probe_tooling", "present",
                "memory_requested_mb", "256",
                "memory_allocated_mb", "31"));
    }

    private static Map<String, String> healthySleep() {
        return new LinkedHashMap<>(Map.of("probe_tooling", "present", "sleeping", "true"));
    }

    private static Map<String, String> healthyOutput() {
        return new LinkedHashMap<>(Map.of("probe_tooling", "present", "output_requested_lines", "200000"));
    }

    /**
     * A launcher that returns canned observations instead of running anything.
     *
     * <p>{@code SandboxLauncher} is an interface and {@code SandboxOutcome} is a record precisely so this is
     * cheap. It never existed, which is why no control had a red path.
     */
    private static class FakeLauncher implements SandboxLauncher {
        private final Map<SyntheticProbe, Map<String, String>> observations = new HashMap<>();
        private final Map<SyntheticProbe, SandboxFailure> failures = new HashMap<>();
        private boolean bounding = true;

        private FakeLauncher() {
            observations.put(SyntheticProbe.INSPECT, healthyInspect());
            observations.put(SyntheticProbe.NETWORK, healthyNetwork());
            observations.put(SyntheticProbe.PROCESSES, healthyProcesses());
            observations.put(SyntheticProbe.MEMORY, healthyMemory());
            observations.put(SyntheticProbe.SLEEP, healthySleep());
            observations.put(SyntheticProbe.OUTPUT, healthyOutput());
        }

        /** Reports a control as switched off. */
        FakeLauncher reporting(SyntheticProbe probe, String key, String value) {
            observations.get(probe).put(key, value);
            return this;
        }

        /** Reports nothing at all for a control, which is the case that used to be read as a pass. */
        FakeLauncher withholding(SyntheticProbe probe, String key) {
            observations.get(probe).remove(key);
            return this;
        }

        /** Keeps every byte while still claiming the output was bounded. */
        FakeLauncher notBounding() {
            bounding = false;
            return this;
        }

        FakeLauncher failing(SyntheticProbe probe, SandboxFailure failure) {
            failures.put(probe, failure);
            return this;
        }

        @Override
        public SandboxSecurityProfile profile() {
            return SandboxSecurityProfile.version1("sha256:" + "a".repeat(64));
        }

        @Override
        public SandboxOutcome run(SandboxLaunchRequest request) {
            SyntheticProbe probe = request.probe();
            boolean timedOut = probe == SyntheticProbe.SLEEP;
            return new SandboxOutcome(
                    Optional.of(timedOut ? 137 : 0),
                    observations.get(probe),
                    probe == SyntheticProbe.OUTPUT,
                    probe == SyntheticProbe.OUTPUT ? (bounding ? 64 * 1024 : 13 * 1024 * 1024) : 512,
                    timedOut ? Duration.ofSeconds(31) : Duration.ofSeconds(1),
                    probe == SyntheticProbe.MEMORY,
                    Optional.ofNullable(failures.get(probe))
                            .or(() -> timedOut ? Optional.of(SandboxFailure.SANDBOX_TIMEOUT) : Optional.empty()));
        }
    }

    private static HostileExecutionAssessment assess(FakeLauncher launcher) {
        return new HostileExecutionSecurityGate(launcher, "fake").assess();
    }

    private static List<String> blockers(HostileExecutionAssessment assessment) {
        return assessment.checks().stream().filter(SecurityCheck::blocksRelease).map(SecurityCheck::control).toList();
    }

    @Test
    void theBaselineFixturePasses() {
        // Without this the tests below would prove nothing: every one of them asserts that removing a control
        // turns the gate red, which is satisfied trivially if the gate is red to begin with.
        HostileExecutionAssessment assessment = assess(new FakeLauncher());

        assertThat(assessment.passed()).as("blockers=%s", blockers(assessment)).isTrue();
    }

    @Test
    void everyMandatoryControlIsPresent() {
        assertThat(assess(new FakeLauncher()).checks())
                .filteredOn(check -> check.enforcement() == SecurityCheck.Enforcement.MANDATORY)
                .extracting(SecurityCheck::control)
                .containsExactlyInAnyOrder(
                        "NON_ROOT_UID", "NON_ROOT_GID", "READ_ONLY_ROOT", "WRITABLE_TMPFS",
                        "NO_DOCKER_SOCKET", "NO_HOST_MOUNTS", "NO_HOST_DEVICES", "KERNEL_PATHS_MASKED",
                        "CAPABILITIES_DROPPED", "NO_NEW_PRIVILEGES", "MINIMAL_ENVIRONMENT",
                        "NETWORK_DENIED", "PID_LIMIT", "MEMORY_LIMIT", "WALL_CLOCK_TIMEOUT",
                        "OUTPUT_BOUNDED");
    }

    @ParameterizedTest(name = "{0} blocks release when the control is reported off")
    @ValueSource(strings = {
        "NON_ROOT_UID", "NON_ROOT_GID", "READ_ONLY_ROOT", "WRITABLE_TMPFS", "NO_DOCKER_SOCKET",
        "NO_HOST_MOUNTS", "NO_HOST_DEVICES", "KERNEL_PATHS_MASKED", "CAPABILITIES_DROPPED",
        "NO_NEW_PRIVILEGES", "MINIMAL_ENVIRONMENT", "NETWORK_DENIED", "PID_LIMIT", "MEMORY_LIMIT",
        "WALL_CLOCK_TIMEOUT", "OUTPUT_BOUNDED"
    })
    void eachMandatoryControlBlocksReleaseWhenItIsReportedOff(String control) {
        HostileExecutionAssessment assessment = assess(switchOff(new FakeLauncher(), control));

        assertThat(blockers(assessment)).as("control=%s", control).contains(control);
        assertThat(assessment.passed()).isFalse();
    }

    @ParameterizedTest(name = "{0} blocks release when the evidence is absent")
    @ValueSource(strings = {
        "NON_ROOT_UID", "NON_ROOT_GID", "READ_ONLY_ROOT", "WRITABLE_TMPFS", "NO_HOST_DEVICES",
        "KERNEL_PATHS_MASKED", "CAPABILITIES_DROPPED", "NO_NEW_PRIVILEGES", "MINIMAL_ENVIRONMENT",
        "NETWORK_DENIED", "PID_LIMIT", "MEMORY_LIMIT", "WALL_CLOCK_TIMEOUT", "OUTPUT_BOUNDED"
    })
    void eachMandatoryControlBlocksReleaseWhenTheEvidenceIsMissing(String control) {
        // The case that matters most. An observation that never arrived is not a control that was enforced,
        // and five of these previously reported PASS on a sandbox that produced nothing whatsoever.
        HostileExecutionAssessment assessment = assess(withhold(new FakeLauncher(), control));

        assertThat(blockers(assessment)).as("control=%s", control).contains(control);
        assertThat(assessment.passed()).isFalse();
    }

    @Test
    void aLauncherThatLostSightOfTheSandboxBlocksEveryControlItWasMeasuring() {
        // A daemon fault used to be indistinguishable from an enforced deadline, so a run that failed two
        // seconds in satisfied the thirty-second timeout check.
        HostileExecutionAssessment assessment = assess(
                new FakeLauncher().failing(SyntheticProbe.INSPECT, SandboxFailure.SANDBOX_OBSERVE_FAILED));

        assertThat(blockers(assessment))
                .contains("NON_ROOT_UID", "READ_ONLY_ROOT", "NO_HOST_MOUNTS", "CAPABILITIES_DROPPED");
        assertThat(assessment.passed()).isFalse();
    }

    @Test
    void aProbeMissingTheToolsItsEvidenceDependsOnIsNotEvidence() {
        // A missing busybox applet and a denied operation are the same exit code. Without this, moving the
        // base image to a smaller one would turn every check that shells out into an unconditional pass.
        HostileExecutionAssessment assessment = assess(
                new FakeLauncher().reporting(SyntheticProbe.NETWORK, "probe_tooling", "missing:nc,"));

        assertThat(blockers(assessment)).contains("NETWORK_DENIED");
    }

    @Test
    void aSandboxWithARoutableAddressFailsEvenWhenNothingIsReachable() {
        // The defect this replaces: on an egress-filtered host, a container attached to a real network
        // reported every destination unreachable, exactly as an isolated one does, and the gate passed while
        // the sandbox could reach its neighbours.
        HostileExecutionAssessment assessment = assess(new FakeLauncher()
                .reporting(SyntheticProbe.NETWORK, "net_global_addresses", "1")
                .reporting(SyntheticProbe.NETWORK, "net_interfaces_up", "1"));

        assertThat(blockers(assessment)).contains("NETWORK_DENIED");
    }

    @Test
    void aHostPathMountedAnywhereFailsRatherThanOnlyAtTheTwoNamesTheProbeUsedToCheck() {
        // Demonstrated escapes: a bind at /mnt/hostdata, the host's /etc at /opt/hostetc, and $HOME at
        // /mnt/host all passed, because the probe asked only about /host and /workspace.
        assertThat(blockers(assess(new FakeLauncher().reporting(
                        SyntheticProbe.INSPECT, "mount_points", "/,/proc,/tmp,/mnt/hostdata"))))
                .contains("NO_HOST_MOUNTS");
        assertThat(blockers(assess(new FakeLauncher().reporting(
                        SyntheticProbe.INSPECT, "mount_points", "/,/proc,/tmp,/opt/hostetc"))))
                .contains("NO_HOST_MOUNTS");
    }

    @Test
    void aDaemonSocketMountedAnywhereFails() {
        // /var/run does not exist in the probe image, so the old single-path check reported "absent" while the
        // socket was mounted at /run/docker.sock and the sandbox held the host.
        assertThat(blockers(assess(new FakeLauncher()
                        .reporting(SyntheticProbe.INSPECT, "unix_sockets", "/run/docker.sock"))))
                .contains("NO_DOCKER_SOCKET");
    }

    @Test
    void aRenamedHostBlockDeviceFails() {
        // The old check counted filenames starting with sd, nvme or vd. Attaching the host's root partition as
        // /dev/loop0 left the count at zero while the sandbox read the raw disk.
        assertThat(blockers(assess(new FakeLauncher()
                        .reporting(SyntheticProbe.INSPECT, "block_device_nodes", "/dev/loop0"))))
                .contains("NO_HOST_DEVICES");
    }

    @Test
    void aProcessCountStoppedBySomethingOtherThanThePidCeilingFails() {
        // Measured: with the ceiling raised 64x and the loop broken by the memory cgroup instead, the probe
        // stopped at 42 processes and PID_LIMIT passed. The count must land at the configured ceiling.
        assertThat(blockers(assess(new FakeLauncher()
                        .reporting(SyntheticProbe.PROCESSES, "processes_started", "42"))))
                .contains("PID_LIMIT");
    }

    @Test
    void aTimeoutThatFiredImmediatelyFails() {
        // A launcher waiting on a container id that does not exist passed the deadline check in 0.164 seconds.
        FakeLauncher instant = new FakeLauncher() {
            @Override
            public SandboxOutcome run(SandboxLaunchRequest request) {
                SandboxOutcome outcome = super.run(request);
                return request.probe() == SyntheticProbe.SLEEP
                        ? new SandboxOutcome(
                                outcome.exitCode(), outcome.observations(), outcome.outputTruncated(),
                                outcome.retainedBytes(), Duration.ofMillis(164), outcome.outOfMemory(),
                                outcome.failure())
                        : outcome;
            }
        };

        assertThat(blockers(assess(instant))).contains("WALL_CLOCK_TIMEOUT");
    }

    private static FakeLauncher switchOff(FakeLauncher launcher, String control) {
        return switch (control) {
            case "NON_ROOT_UID" -> launcher.reporting(SyntheticProbe.INSPECT, "uid", "0");
            case "NON_ROOT_GID" -> launcher.reporting(SyntheticProbe.INSPECT, "gid", "0");
            case "READ_ONLY_ROOT" -> launcher.reporting(SyntheticProbe.INSPECT, "rootfs_writable", "true");
            case "WRITABLE_TMPFS" -> launcher.reporting(SyntheticProbe.INSPECT, "tmp_writable", "false");
            case "NO_DOCKER_SOCKET" ->
                    launcher.reporting(SyntheticProbe.INSPECT, "unix_sockets", "/run/docker.sock");
            case "NO_HOST_MOUNTS" ->
                    launcher.reporting(SyntheticProbe.INSPECT, "mount_points", "/,/proc,/tmp,/mnt/host");
            case "NO_HOST_DEVICES" ->
                    launcher.reporting(SyntheticProbe.INSPECT, "block_device_nodes", "/dev/sda1");
            case "KERNEL_PATHS_MASKED" ->
                    launcher.reporting(SyntheticProbe.INSPECT, "mount_points", "/,/proc,/tmp");
            case "CAPABILITIES_DROPPED" ->
                    launcher.reporting(SyntheticProbe.INSPECT, "cap_prm", "0000003fffffffff");
            case "NO_NEW_PRIVILEGES" -> launcher.reporting(SyntheticProbe.INSPECT, "no_new_privs", "0");
            case "MINIMAL_ENVIRONMENT" -> launcher.reporting(
                    SyntheticProbe.INSPECT, "env_names", "AWS_SECRET_ACCESS_KEY,HOME,KAAS_SANDBOX,PATH");
            case "NETWORK_DENIED" -> launcher.reporting(SyntheticProbe.NETWORK, "net_public", "reachable");
            case "PID_LIMIT" ->
                    launcher.reporting(SyntheticProbe.PROCESSES, "processes_loop_completed", "true");
            case "MEMORY_LIMIT" ->
                    launcher.reporting(SyntheticProbe.MEMORY, "memory_loop_completed", "true");
            case "WALL_CLOCK_TIMEOUT" ->
                    launcher.reporting(SyntheticProbe.SLEEP, "sleep_completed", "true");
            // Truncation claimed while every byte was kept. A mutation that did exactly this left the
            // launcher buffering 13 MB of attacker-controlled output with the check still green.
            case "OUTPUT_BOUNDED" -> launcher.notBounding();
            default -> throw new IllegalArgumentException("Unmapped control: " + control);
        };
    }

    private static FakeLauncher withhold(FakeLauncher launcher, String control) {
        return switch (control) {
            case "NON_ROOT_UID" -> launcher.withholding(SyntheticProbe.INSPECT, "uid");
            case "NON_ROOT_GID" -> launcher.withholding(SyntheticProbe.INSPECT, "gid");
            case "READ_ONLY_ROOT" -> launcher.withholding(SyntheticProbe.INSPECT, "rootfs_writable");
            case "WRITABLE_TMPFS" -> launcher.withholding(SyntheticProbe.INSPECT, "tmp_writable");
            case "NO_HOST_DEVICES" -> launcher.withholding(SyntheticProbe.INSPECT, "char_device_nodes")
                    .reporting(SyntheticProbe.INSPECT, "block_device_nodes", "/dev/vda1");
            case "KERNEL_PATHS_MASKED" -> launcher.withholding(SyntheticProbe.INSPECT, "mount_points");
            case "CAPABILITIES_DROPPED" -> launcher.withholding(SyntheticProbe.INSPECT, "cap_amb");
            case "NO_NEW_PRIVILEGES" -> launcher.withholding(SyntheticProbe.INSPECT, "no_new_privs");
            case "MINIMAL_ENVIRONMENT" -> launcher.withholding(SyntheticProbe.INSPECT, "env_names");
            case "NETWORK_DENIED" -> launcher.withholding(SyntheticProbe.NETWORK, "net_global_addresses");
            case "PID_LIMIT" -> launcher.withholding(SyntheticProbe.PROCESSES, "processes_started");
            case "MEMORY_LIMIT" -> launcher.withholding(SyntheticProbe.MEMORY, "memory_requested_mb");
            case "WALL_CLOCK_TIMEOUT" -> launcher.withholding(SyntheticProbe.SLEEP, "sleeping");
            case "OUTPUT_BOUNDED" -> launcher.withholding(SyntheticProbe.OUTPUT, "output_requested_lines");
            default -> throw new IllegalArgumentException("Unmapped control: " + control);
        };
    }
}
