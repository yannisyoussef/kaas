package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.SourceBundlePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bundle limits this module enforces are the ones the shared contract states.
 *
 * <p>Two modules assemble and verify the same bundle and neither may import the other, so the only thing
 * holding them together is a file both are checked against. Without this test a limit relaxed here alone
 * produces bundles the runner refuses at the last moment before a mount — a delivery failure discovered in a
 * sandbox rather than at the boundary that built it. Tightened here alone, the runner would happily accept
 * something this side would never send, which is the direction that matters less but is asserted the same way.
 *
 * <p>The runner has the mirror of this test. Both are needed: either one alone lets the file drift toward
 * whichever side is checked.
 */
@DisplayName("Source bundle contract")
class SourceBundleContractTest {

    @Test
    @DisplayName("every assembly limit equals the shared contract")
    void limitsMatchTheSharedContract() throws Exception {
        JsonNode contract = JsonMapper.builder().build().readTree(Files.readString(contractFile()));
        JsonNode limits = contract.get("limits");

        assertThat(contract.get("formatVersion").stringValue()).isEqualTo(SourceBundlePolicy.FORMAT);
        assertThat(limits.get("maxEntries").asInt()).isEqualTo(SourceBundlePolicy.MAX_FEATURES);
        assertThat(limits.get("maxTotalBytes").asLong()).isEqualTo(SourceBundlePolicy.MAX_TOTAL_BYTES);
        assertThat(limits.get("maxEntryBytes").asLong()).isEqualTo(SourceBundlePolicy.MAX_ENTRY_BYTES);
        assertThat(limits.get("maxPathLength").asInt()).isEqualTo(SourceBundlePolicy.MAX_PATH_LENGTH);
    }

    private static Path contractFile() {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts", "source-bundle.json");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("packages", "api-contracts", "source-bundle.json");
    }
}
