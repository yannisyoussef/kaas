package com.kaas.runner.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The gate's mandatory control set is the same set the control plane requires.
 *
 * <p>The control plane cannot import this module — it is build-guarded against acquiring anything that holds
 * container-runtime access — so the set it demands is a separate copy. A shared file in the contracts package
 * is the single source of truth, and this is the runner's half of the agreement.
 *
 * <p>The comparison is against the controls the gate <em>actually emits</em>, taken from its own source rather
 * than from a list a test maintains alongside it. A test that compared one hand-written list to another would
 * pass while the gate emitted something different from both.
 */
class MandatoryControlContractTest {

    private static final String CONTRACT = "packages/api-contracts/mandatory-sandbox-controls.json";

    @Test
    void theGateEmitsExactlyTheSharedEgressControlSet() throws IOException {
        // The same agreement, for the smaller set that decides whether this deployment may enforce an
        // allowlist. It is a separate set on both sides because it is required under a different condition,
        // and separate sets need separate contract tests — one test over a union would go green when a
        // control moved from one set to the other, which changes when it is demanded.
        Set<String> shared = sharedList("egressControls");
        Set<String> emitted = controlsInSource(
                "services/runner/src/main/java/com/kaas/runner/gate/EgressEnforcementGate.java",
                "check\\(\\s*\"([A-Z_]+)\"");

        assertThat(emitted)
                .as("the egress gate's controls must equal the shared contract")
                .isEqualTo(shared);
    }

    @Test
    void everyRuntimesMandatoryControlSetMatchesTheSharedContract() throws IOException {
        // Observed by RUNNING the gate, not by reading its source.
        //
        // Source scraping was enough while there was one set and every control name was a literal in a
        // mandatory(...) call. It stopped being enough the moment the set became runtime-scoped: the mediation
        // control is emitted through a named constant, and which controls are mandatory now depends on the
        // runtime rather than on the shape of the call. A pattern cannot answer either question, and a
        // pattern that silently matched nothing would report an empty set as agreement.
        for (String profileVersion : sharedProfileVersions()) {
            ExecutionRuntimeType runtime = runtimeFor(profileVersion);
            Set<String> emitted = new HostileExecutionSecurityGate(new SetMembershipLauncher(runtime), "docker")
                    .assess()
                    .checks()
                    .stream()
                    .filter(check -> check.enforcement() == SecurityCheck.Enforcement.MANDATORY)
                    .map(SecurityCheck::control)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            assertThat(emitted)
                    .as("the gate's mandatory controls under %s must equal the shared contract; adding one "
                            + "here requires teaching the control plane about it, or every attestation it "
                            + "accepts stops covering everything the platform requires", profileVersion)
                    .isEqualTo(sharedControlsFor(profileVersion));
        }
    }

    @Test
    void everyRuntimeProducesTheProfileVersionTheContractBindsItTo() throws IOException {
        // The runner's half of the runtime binding. The control plane asserts the same map from its side; this
        // asserts that the runtime constants here actually produce those profile versions, so a rename on
        // either side fails the build instead of producing evidence nobody can judge.
        java.util.Map<String, String> declared = sharedRuntimeBinding();
        assertThat(declared).as("the contract must bind a runtime to every profile version").isNotEmpty();
        for (var binding : declared.entrySet()) {
            ExecutionRuntimeType runtime = ExecutionRuntimeType.valueOf(binding.getValue());
            assertThat(runtime.profileVersion())
                    .as("%s must produce profile version %s", binding.getValue(), binding.getKey())
                    .isEqualTo(binding.getKey());
        }
        // Every runtime this build has, not merely every one the contract happens to mention: a runtime with
        // no entry could produce evidence the control plane has no rules for.
        assertThat(declared.values())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(ExecutionRuntimeType.values())
                                .map(Enum::name)
                                .toList());
    }

    private static java.util.Map<String, String> sharedRuntimeBinding() throws IOException {
        String json = Files.readString(locate(CONTRACT));
        int start = json.indexOf("\"runtimeByProfileVersion\"");
        assertThat(start).as("the contract must declare runtimeByProfileVersion").isNotNegative();
        int open = json.indexOf('{', start);
        int close = json.indexOf("\n  }", open);
        java.util.Map<String, String> binding = new java.util.LinkedHashMap<>();
        Matcher entries = Pattern.compile("\"(kaas\\.[A-Za-z0-9.\\-]+)\"\\s*:\\s*\"([A-Z_]+)\"")
                .matcher(json.substring(open, close));
        while (entries.find()) {
            binding.put(entries.group(1), entries.group(2));
        }
        return binding;
    }

    @Test
    void theTwoRuntimesDoNotEmitTheSameMandatorySet() throws IOException {
        // Anti-vacuity. Every assertion above holds just as well if the scoping is decorative.
        assertThat(sharedControlsFor("kaas.sandbox.v1"))
                .isNotEqualTo(sharedControlsFor("kaas.sandbox.gvisor.v1"));
    }

    private static ExecutionRuntimeType runtimeFor(String profileVersion) {
        return java.util.Arrays.stream(ExecutionRuntimeType.values())
                .filter(candidate -> candidate.profileVersion().equals(profileVersion))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The shared contract names profile version " + profileVersion
                                + ", which no runtime in this build produces."));
    }

    /**
     * A launcher that reports nothing at all.
     *
     * <p>Membership of the mandatory set is the property under test, and it does not depend on any verdict —
     * a control the gate emits is emitted whether it passes or fails. Reporting nothing also exercises the
     * one place where membership genuinely does vary: a runtime that cannot expose the no-new-privileges flag
     * must drop that control out of the mandatory set, and one that can must keep it there and fail it.
     */
    private record SetMembershipLauncher(ExecutionRuntimeType runtime) implements SandboxLauncher {
        @Override
        public SandboxSecurityProfile profile() {
            return SandboxSecurityProfile.version1("sha256:" + "a".repeat(64), runtime);
        }

        @Override
        public SandboxOutcome run(SandboxLaunchRequest request) {
            return new SandboxOutcome(
                    java.util.Optional.empty(),
                    java.util.Map.of(),
                    false,
                    0,
                    java.time.Duration.ZERO,
                    false,
                    java.util.Optional.empty());
        }
    }

    /**
     * The per-runtime control lists, parsed the same hand-rolled way as the rest of this test.
     *
     * <p>No JSON library, because this module must not gain a dependency to read one small file, and because
     * a parser that silently returned nothing for a renamed key would make the whole contract vacuous — so
     * every lookup below asserts it found something.
     */
    private static java.util.Map<String, Set<String>> sharedControlSets() throws IOException {
        String json = Files.readString(locate(CONTRACT));
        int start = json.indexOf("\"controlsByProfileVersion\"");
        assertThat(start).as("the contract must declare controlsByProfileVersion").isNotNegative();
        int open = json.indexOf('{', start);
        int close = json.indexOf("\n  }", open);
        assertThat(close).as("controlsByProfileVersion must be a complete object").isGreaterThan(open);

        java.util.Map<String, Set<String>> sets = new java.util.LinkedHashMap<>();
        Matcher versions = Pattern.compile("\"(kaas\\.[A-Za-z0-9.\\-]+)\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(json.substring(open, close));
        while (versions.find()) {
            Matcher controls = Pattern.compile("\"([A-Z_]{3,})\"").matcher(versions.group(2));
            Set<String> named = new LinkedHashSet<>();
            while (controls.find()) {
                named.add(controls.group(1));
            }
            sets.put(versions.group(1), named);
        }
        assertThat(sets).as("the contract must name at least one profile version").isNotEmpty();
        return sets;
    }

    private static Set<String> sharedProfileVersions() throws IOException {
        return sharedControlSets().keySet();
    }

    private static Set<String> sharedControlsFor(String profileVersion) throws IOException {
        Set<String> controls = sharedControlSets().get(profileVersion);
        assertThat(controls).as("the contract must declare controls for %s", profileVersion).isNotNull();
        return controls;
    }

    private static Set<String> controlsInSource(String path, String pattern) throws IOException {
        Matcher matcher = Pattern.compile(pattern).matcher(Files.readString(locate(path)));
        Set<String> controls = new LinkedHashSet<>();
        while (matcher.find()) {
            controls.add(matcher.group(1));
        }
        assertThat(controls).as("the source at %s must contain controls to compare", path).isNotEmpty();
        return controls;
    }

    /**
     * One named array from the shared contract, bounded to that array.
     *
     * <p>The bound is the whole point. The previous version scanned from the start of {@code "controls"} to
     * the end of the file, which was correct while that was the last array in it — and the moment a second
     * array of controls was added below, the mandatory set silently grew to include the egress controls and
     * this test would have failed for a reason that had nothing to do with the gate.
     */
    private static Set<String> sharedList(String field) throws IOException {
        String json = Files.readString(locate(CONTRACT));
        int start = json.indexOf("\"" + field + "\"");
        assertThat(start).as("the contract must declare %s", field).isNotNegative();
        int open = json.indexOf('[', start);
        int close = json.indexOf(']', open);
        Matcher matcher = Pattern.compile("\"([A-Z_]{3,})\"").matcher(json.substring(open, close));
        Set<String> controls = new LinkedHashSet<>();
        while (matcher.find()) {
            controls.add(matcher.group(1));
        }
        return controls;
    }

    /** Located from the repository root or from the module, so the test runs the same either way. */
    private static Path locate(String repositoryRelative) {
        Path fromRoot = Path.of(repositoryRelative);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "..").resolve(repositoryRelative);
    }
}
