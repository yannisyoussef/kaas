package com.kaas.runner.gate;

import static org.assertj.core.api.Assertions.assertThat;

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
    void theGateEmitsExactlyTheSharedMandatoryControlSet() throws IOException {
        Set<String> shared = sharedContract();
        Set<String> emitted = mandatoryControlsInGateSource();

        assertThat(emitted)
                .as("the gate's mandatory controls must equal the shared contract; adding one here requires "
                        + "teaching the control plane about it, or every attestation it accepts stops covering "
                        + "everything the platform requires")
                .isEqualTo(shared);
    }

    /**
     * The controls the gate hands to {@code mandatory(...)}, read from its source.
     *
     * <p>Source inspection rather than a live run, because running the gate needs a daemon and this property is
     * about the set's membership rather than about any verdict. {@code HostileExecutionBoundaryTests} and
     * {@code SecurityGateRedPathTests} both pin the emitted set behaviourally, so a control that this pattern
     * missed would already be failing there.
     */
    private static Set<String> mandatoryControlsInGateSource() throws IOException {
        return controlsInSource(
                "services/runner/src/main/java/com/kaas/runner/gate/HostileExecutionSecurityGate.java",
                "mandatory\\(\\s*outcome,\\s*\"([A-Z_]+)\"");
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

    private static Set<String> sharedContract() throws IOException {
        return sharedList("controls");
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
        String json = Files.readString(locate("packages/api-contracts/mandatory-sandbox-controls.json"));
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
