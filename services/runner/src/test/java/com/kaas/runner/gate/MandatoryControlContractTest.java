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
        String source = Files.readString(locate("services/runner/src/main/java/com/kaas/runner/gate"
                + "/HostileExecutionSecurityGate.java"));
        Matcher matcher = Pattern.compile("mandatory\\(\\s*outcome,\\s*\"([A-Z_]+)\"").matcher(source);
        Set<String> controls = new LinkedHashSet<>();
        while (matcher.find()) {
            controls.add(matcher.group(1));
        }
        assertThat(controls).as("the gate source must contain mandatory controls to compare").isNotEmpty();
        return controls;
    }

    private static Set<String> sharedContract() throws IOException {
        String json = Files.readString(locate("packages/api-contracts/mandatory-sandbox-controls.json"));
        Matcher matcher = Pattern.compile("\"([A-Z_]{3,})\"").matcher(
                json.substring(json.indexOf("\"controls\"")));
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
