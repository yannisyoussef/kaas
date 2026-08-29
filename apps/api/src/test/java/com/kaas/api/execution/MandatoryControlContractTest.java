package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The control plane's required control set is the same set the gate produces.
 *
 * <p>The two live in modules that deliberately cannot see each other: the gate holds container-runtime access
 * and the control plane is build-guarded against depending on it. So the set is duplicated, and duplication
 * that nothing checks is duplication that drifts. A shared file in the contracts package is the single source
 * of truth, and each module asserts against it independently.
 *
 * <p>What this buys is specific. If the gate gains a control and the control plane is not taught about it, this
 * fails rather than the platform quietly accepting assessments that no longer cover everything it requires. If
 * the control plane demands one the gate never emits, every attestation would be refused forever, which is
 * safe but would be discovered in production rather than here.
 */
class MandatoryControlContractTest {

    @Test
    void theRequiredControlSetMatchesTheSharedContract() throws Exception {
        Path contract = locate();
        Set<String> shared = StreamSupport.stream(
                        JsonMapper.builder().build().readTree(Files.readString(contract)).get("controls").spliterator(),
                        false)
                .map(node -> node.stringValue())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(SandboxSecurityAttestation.REQUIRED_MANDATORY_CONTROLS)
                .as("the control plane's required set must equal the shared contract at %s", contract)
                .isEqualTo(shared);
    }

    /** Located from the module rather than from configuration, so the test cannot be pointed at a copy. */
    private static Path locate() {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts", "mandatory-sandbox-controls.json");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("packages", "api-contracts", "mandatory-sandbox-controls.json");
    }
}
