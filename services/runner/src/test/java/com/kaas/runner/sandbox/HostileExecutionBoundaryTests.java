package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.gate.HostileExecutionSecurityGate;
import com.kaas.runner.gate.SecurityCheck;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Executable evidence that the sandbox boundary enforces what it claims.
 *
 * <p>Every assertion here is behavioural: the probe reports what it could actually observe or do from inside
 * the sandbox, and the test asserts on that. None of them reads back the launcher's own configuration and
 * calls it proof, because a setting is a statement of intent and the question is whether the runtime honoured
 * it.
 *
 * <p>Nothing here is a real exploit. Each check is an observation or a bounded, self-limiting attempt whose
 * failure is the evidence — a probe capable of harming a host would be a worse thing to run than the untrusted
 * content this boundary exists to contain.
 */
class HostileExecutionBoundaryTests {
    private static SandboxSecurityProfile profile;
    private static DockerSandboxLauncher launcher;
    private static String generation;

    @BeforeAll
    static void buildProbe() {
        profile = SandboxTestSupport.profile();
        generation = "test-" + UUID.randomUUID();
        launcher = SandboxTestSupport.launcher(profile, generation);
    }

    private static SandboxOutcome run(SyntheticProbe probe) {
        return launcher.run(new SandboxLaunchRequest(probe, profile.version(), UUID.randomUUID()));
    }

    @Test
    @Timeout(180)
    void theSandboxRunsAsAnUnprivilegedUserWithNoCapabilities() {
        SandboxOutcome outcome = run(SyntheticProbe.INSPECT);

        // Read from the kernel by the process itself. The image's USER directive is advisory — a launcher can
        // override it — so neither the image nor the launcher setting is accepted as evidence on its own.
        assertThat(outcome.observation("uid")).contains("65534");
        assertThat(outcome.observation("gid")).contains("65534");
        // All zeroes is the kernel's own report that nothing is permitted, effective, or even inheritable.
        assertThat(outcome.observation("cap_eff")).contains("0000000000000000");
        assertThat(outcome.observation("cap_prm")).contains("0000000000000000");
        assertThat(outcome.observation("cap_bnd")).contains("0000000000000000");
        assertThat(outcome.observation("no_new_privs")).contains("1");
    }

    @Test
    @Timeout(180)
    void theRootFilesystemIsReadOnlyAndOnlyTheApprovedTemporaryPathIsWritable() {
        SandboxOutcome outcome = run(SyntheticProbe.INSPECT);

        assertThat(outcome.observation("rootfs_writable")).contains("false");
        // The approved location has to work, or the boundary is merely broken rather than secure.
        assertThat(outcome.observation("tmp_writable")).contains("true");
    }

    @Test
    @Timeout(180)
    void theSandboxCanSeeNoHostSurfaceAtAll() {
        SandboxOutcome outcome = run(SyntheticProbe.INSPECT);

        // Enumerated, not guessed at. Asking whether /host and /workspace exist found a mount at those two
        // names and nothing else: a bind at /mnt/hostdata, the host's /etc at /opt/hostetc, and $HOME at
        // /mnt/host were all demonstrated passing this test while the sandbox held the host.
        //
        // A container holding the daemon socket is a root shell on the host wearing a container's clothes, and
        // the socket is looked for wherever it might be rather than at one path that does not exist in this
        // image.
        assertThat(outcome.observedSet("unix_sockets")).isEmpty();
        assertThat(outcome.observedSet("block_device_nodes")).isEmpty();
        assertThat(outcome.observedSet("char_device_nodes"))
                .allSatisfy(node -> assertThat(node).startsWith("/dev/"));
        assertThat(outcome.observedSet("mount_points"))
                .isNotEmpty()
                .allSatisfy(path -> assertThat(path).matches(
                        "/|/proc.*|/sys.*|/dev.*|/tmp|/etc/(hosts|hostname|resolv\\.conf)"));
        // The masked overmounts are positive evidence that the runtime kept its own internals out. A daemon
        // started with systempaths=unconfined exposes all of them and would otherwise look identical.
        assertThat(outcome.observedSet("mount_points"))
                .contains("/proc/kcore", "/proc/keys", "/proc/timer_list");
    }

    @Test
    @Timeout(180)
    void theSandboxEnvironmentIsBuiltFromNothingRatherThanFilteredFromTheHost() {
        SandboxOutcome outcome = run(SyntheticProbe.INSPECT);
        String names = outcome.observation("env_names").orElseThrow();

        // The assertion is about what is present, not about which known-sensitive names were removed.
        // Subtraction requires knowing every name worth removing, and the one nobody thought of is the leak.
        // KAAS_SANDBOX and PATH come from the profile's allowlist; HOME, HOSTNAME, PWD and SHLVL are
        // manufactured by the runtime and the shell rather than inherited. Named explicitly so that anything
        // genuinely inherited from the launcher's process still fails this.
        assertThat(names.split(","))
                .allSatisfy(name -> assertThat(name.trim())
                        .isIn("", "KAAS_SANDBOX", "PATH", "HOME", "HOSTNAME", "PWD", "SHLVL"));
        // The build puts AWS_SECRET_ACCESS_KEY, GITHUB_TOKEN and KAAS_DATABASE_PASSWORD in this JVM's own
        // environment, so these assertions are exercised rather than merely written. Without those canaries
        // they asserted the absence of names nothing had ever set.
        assertThat(System.getenv("AWS_SECRET_ACCESS_KEY"))
                .as("the canary must be present in the launcher, or this test proves nothing")
                .isNotNull();
        assertThat(names).doesNotContain("AWS_", "GITHUB_", "DOCKER_", "SSH_AUTH_SOCK", "KAAS_DATABASE",
                "KAAS_RABBIT", "KAAS_OIDC", "PASSWORD", "SECRET", "TOKEN");
    }

    @Test
    @Timeout(180)
    void nothingIsReachableFromInsideTheSandbox() {
        SandboxOutcome outcome = run(SyntheticProbe.NETWORK);

        // Positive evidence first, because reachability alone cannot tell "no network" from "a network with
        // nothing routable on it". A container on an internal Docker network produced byte-identical
        // reachability output to this one while a sibling reached it by name. Measured 0/0/0 here against
        // 1/2/1 on a bridged container.
        assertThat(outcome.observation("net_global_addresses")).contains("0");
        assertThat(outcome.observation("net_default_routes")).contains("0");
        assertThat(outcome.observation("net_interfaces_up")).contains("0");

        // Every destination class an escape would use, including the one that matters most in a cloud: the
        // link-local metadata address. Loopback is deliberately absent — --network none leaves a fully working
        // lo, so that attempt could only ever report that nothing was listening.
        assertThat(outcome.observation("net_public")).contains("unreachable");
        assertThat(outcome.observation("net_private")).contains("unreachable");
        assertThat(outcome.observation("net_metadata")).contains("unreachable");
        assertThat(outcome.observation("net_metadata_v6")).contains("unreachable");
        assertThat(outcome.observation("net_gateway")).contains("unreachable");
        // DNS is a separate exfiltration channel from TCP reachability, so it gets its own assertion. Denying
        // it does not by itself solve SSRF, and this slice does not claim it does.
        assertThat(outcome.observation("net_dns")).contains("unresolvable");
    }

    @Test
    @Timeout(180)
    void processCreationStopsAtTheConfiguredCeiling() {
        SandboxOutcome outcome = run(SyntheticProbe.PROCESSES);

        int started = Integer.parseInt(outcome.observation("processes_started").orElse("-1"));
        int requested = Integer.parseInt(outcome.observation("processes_requested").orElse("-1"));
        assertThat(requested).isPositive();
        // The probe asks for more than the ceiling allows and reports progress as it goes. It got fewer than it
        // asked for, and it never reached the marker printed after the loop — the second half matters because
        // the shell is killed outright when it cannot fork, so without it "stopped early" and "finished
        // quietly" are indistinguishable.
        assertThat(started).as("processes the sandbox managed to start").isPositive().isLessThan(requested);
        assertThat(outcome.observation("processes_loop_completed")).isEmpty();
    }

    @Test
    @Timeout(180)
    void memoryAllocationIsStoppedAndTheHostSurvivesIt() {
        SandboxOutcome outcome = run(SyntheticProbe.MEMORY);

        int allocated = Integer.parseInt(outcome.observation("memory_allocated_mb").orElse("-1"));
        int requested = Integer.parseInt(outcome.observation("memory_requested_mb").orElse("-1"));
        boolean stopped = allocated < requested || outcome.exitCode().filter(code -> code != 0).isPresent();
        assertThat(stopped)
                .as("allocated=%s requested=%s exit=%s", allocated, requested, outcome.exitCode().orElse(null))
                .isTrue();
        // The launcher is still healthy afterwards, which is the half of this that matters operationally.
        assertThat(run(SyntheticProbe.INSPECT).observation("uid")).contains("65534");
    }

    @Test
    @Timeout(180)
    void aWorkloadThatWillNotStopIsTerminatedAtTheDeadlineAndCleanedUp() {
        long before = managedContainerCount();

        SandboxOutcome outcome = run(SyntheticProbe.SLEEP);

        // The probe sleeps for an hour. Termination cannot depend on it cooperating, so the launcher enforces
        // the deadline itself.
        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.observation("sleep_completed")).isEmpty();
        assertThat(outcome.elapsed()).isLessThan(profile.wallClockTimeout().plus(Duration.ofSeconds(20)));
        // And nothing is left behind: a timed-out sandbox is still a sandbox that has to be removed.
        assertThat(managedContainerCount()).isEqualTo(before);
    }

    @Test
    @Timeout(180)
    void outputIsBoundedSoAnUntrustedWorkloadCannotFloodTheHost() {
        SandboxOutcome outcome = run(SyntheticProbe.OUTPUT);

        assertThat(outcome.outputTruncated()).isTrue();
        // Truncation is not silent data loss to be discovered later; the outcome says so explicitly.
        assertThat(managedContainerCount()).isZero();
    }

    @Test
    @Timeout(240)
    void theGateFailsClosedAndReportsEveryMandatoryControl() {
        HostileExecutionSecurityGate gate = new HostileExecutionSecurityGate(launcher, "docker");

        HostileExecutionAssessment assessment = gate.assess();

        assertThat(assessment.passed())
                .as("blockers=%s", assessment.blockers())
                .isTrue();
        List<String> mandatory = assessment.checks().stream()
                .filter(check -> check.enforcement() == SecurityCheck.Enforcement.MANDATORY)
                .map(SecurityCheck::control)
                .toList();
        // The mandatory set is named here so that quietly demoting a control to deployment-specific — which
        // would make the gate pass without it — cannot happen without this failing.
        assertThat(mandatory)
                .containsExactlyInAnyOrder(
                        "NON_ROOT_UID", "NON_ROOT_GID", "READ_ONLY_ROOT", "WRITABLE_TMPFS", "NO_DOCKER_SOCKET",
                        "NO_HOST_MOUNTS", "NO_HOST_DEVICES", "KERNEL_PATHS_MASKED",
                        "CAPABILITIES_DROPPED", "NO_NEW_PRIVILEGES",
                        "MINIMAL_ENVIRONMENT", "NETWORK_DENIED", "PID_LIMIT", "MEMORY_LIMIT",
                        "WALL_CLOCK_TIMEOUT", "OUTPUT_BOUNDED");
        // A control the host cannot enforce is reported as unsupported, never as a pass.
        // Named exactly, like the mandatory set. Asserting only that this list was non-empty let it pass with
        // one entry while the documentation claimed five were reported, and nothing would have noticed the
        // other four never being implemented.
        assertThat(assessment.checks())
                .filteredOn(check -> check.enforcement() == SecurityCheck.Enforcement.DEPLOYMENT_SPECIFIC)
                .extracting(SecurityCheck::control)
                .containsExactlyInAnyOrder(
                        "SECCOMP_FILTER",
                        "USER_NAMESPACE",
                        // Deployment-specific UNDER THE BASELINE RUNTIME, and mandatory under a mediating one.
                        // The baseline asserts no kernel identity of its own — whatever the host runs is not a
                        // property this platform gets to claim — so there is nothing here to demand. Under
                        // gVisor the same control is MANDATORY and is what distinguishes "the daemon said
                        // runsc" from "runsc is actually underneath this workload".
                        "HOST_KERNEL_SYSCALL_MEDIATION");
    }

    @Test
    @Timeout(600)
    void theBaselineRuntimeClaimsNoMediation() throws Exception {
        var assessment = new HostileExecutionSecurityGate(launcher, "docker").assess();

        var mediation = assessment.checks().stream()
                .filter(check -> HostileExecutionSecurityGate.RUNTIME_MEDIATION_CONTROL.equals(check.control()))
                .findFirst()
                .orElseThrow();

        // UNSUPPORTED, not PASS and not FAIL. Standard Docker does not mediate host syscalls and this
        // assessment says so; a PASS here would be the single most misleading verdict this gate could emit,
        // because every other control would look identical to a genuinely mediated sandbox.
        assertThat(mediation.verdict()).isEqualTo(SecurityCheck.Verdict.UNSUPPORTED);
        assertThat(mediation.enforcement()).isEqualTo(SecurityCheck.Enforcement.DEPLOYMENT_SPECIFIC);
        assertThat(mediation.blocksRelease())
                .as("the baseline gate must not start failing because a stronger runtime exists")
                .isFalse();
    }

    /** A syntactically valid content address. The profile refuses anything a name could repoint. */
    private static final String PINNED = "sha256:" + "a".repeat(64);

    @Test
    void theProfileRefusesToDescribeAWeakerSandboxThanTheOneItPromises() {
        // Making a dangerous configuration unrepresentable is stronger than validating it away, because
        // validation is something you can forget to call and a constructor is not.
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", PINNED, SandboxSecurityProfile.NOBODY, true, true, List.of("ALL"), List.of(),
                        "bridge", 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no network");
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", PINNED, SandboxSecurityProfile.NOBODY, false, true, List.of("ALL"), List.of(),
                        "none", 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", PINNED, "0:0", true, true, List.of("ALL"), List.of(), "none",
                        1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", PINNED, SandboxSecurityProfile.NOBODY, true, true, List.of("ALL"),
                        List.of("SYS_ADMIN"), "none", 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class);
        // Swap must be pinned to the memory limit, or a workload evades the ceiling by swapping past it.
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", PINNED, SandboxSecurityProfile.NOBODY, true, true, List.of("ALL"), List.of(),
                        "none", 1024, 4096, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Swap");
        // A tag is a mutable pointer to executable code. A probe image that can be substituted reports
        // whatever its substitute likes, which would invalidate every behavioural claim the gate makes.
        assertThatThrownBy(() -> new SandboxSecurityProfile(
                        "weak", "busybox:latest", SandboxSecurityProfile.NOBODY, true, true, List.of("ALL"),
                        List.of(), "none", 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 1, 1, Map.of(), ExecutionRuntimeType.DOCKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
    }

    /**
     * Sandboxes belonging to <em>this</em> run, not every sandbox on the host.
     *
     * <p>Scoped by generation because a leftover from a crashed earlier run would otherwise make a cleanup
     * assertion fail for a reason that has nothing to do with the code under test — and, worse, could make one
     * pass by coincidence. Reconciling other generations is a separate property with its own test.
     */
    private static long managedContainerCount() {
        return new OrphanSandboxReconciler(SandboxTestSupport.docker(), generation, java.time.Duration.ofSeconds(30)).managedContainers().stream()
                .filter(container -> container.getLabels() != null
                        && generation.equals(container.getLabels().get(SandboxLabels.GENERATION)))
                .count();
    }
}
