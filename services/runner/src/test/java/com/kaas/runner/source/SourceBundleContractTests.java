package com.kaas.runner.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounds this module verifies against are the ones the shared contract states.
 *
 * <p>The mirror of the control plane's test. The runner is the last component before tenant bytes reach a
 * filesystem, and it re-verifies rather than trusting what it was sent; that re-verification is only
 * meaningful while it is checking the same thing the assembler was told to produce. A limit that drifted here
 * would turn a legitimate bundle into a refusal at the mount, and the mount name and layout drifting would
 * put tenant bytes somewhere the sandbox does not expect them.
 */
@DisplayName("Source bundle contract")
class SourceBundleContractTests {

    @Test
    @DisplayName("every verification limit equals the shared contract")
    void limitsMatchTheSharedContract() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(Files.readString(contractFile()));
        JsonNode limits = contract.get("limits");

        assertThat(contract.get("formatVersion").asText()).isEqualTo(SourceBundleContract.FORMAT_VERSION);
        assertThat(limits.get("maxEntries").asInt()).isEqualTo(SourceBundleContract.MAX_ENTRIES);
        assertThat(limits.get("maxTotalBytes").asLong()).isEqualTo(SourceBundleContract.MAX_TOTAL_BYTES);
        assertThat(limits.get("maxEntryBytes").asLong()).isEqualTo(SourceBundleContract.MAX_ENTRY_BYTES);
        assertThat(limits.get("maxPathLength").asInt()).isEqualTo(SourceBundleContract.MAX_PATH_LENGTH);
    }

    @Test
    @DisplayName("the mount layout equals the shared contract")
    void theMountLayoutMatchesTheSharedContract() throws Exception {
        // The container path, manifest name and files directory are what the in-sandbox verifier looks for.
        // If they drifted from the contract, the verifier would report an absent bundle rather than a wrong
        // one -- a failure that reads like infrastructure and hides a delivery defect.
        JsonNode mount = new ObjectMapper().readTree(Files.readString(contractFile())).get("mount");

        assertThat(mount.get("containerPath").asText()).isEqualTo(SourceBundleContract.CONTAINER_PATH);
        assertThat(mount.get("manifestName").asText()).isEqualTo(SourceBundleContract.MANIFEST_NAME);
        assertThat(mount.get("filesDirectory").asText()).isEqualTo(SourceBundleContract.FILES_DIRECTORY);
        assertThat(mount.get("filesystemBytes").asLong())
                .isEqualTo(SourceBundleContract.SOURCE_FILESYSTEM_BYTES);
    }

    @Test
    @DisplayName("the probe looks for the bundle where the contract puts it")
    void theProbeAgreesWithTheContract() throws Exception {
        // The probe is a shell script and cannot import a Java constant, so its copy of the mount path is the
        // one place this agreement could silently break. A probe reading a different path would report
        // "no bundle" forever, and that reads as a clean run rather than a broken one.
        String probe = Files.readString(probeScript());

        assertThat(probe).contains(SourceBundleContract.CONTAINER_PATH);
        assertThat(probe).contains(SourceBundleContract.MANIFEST_NAME);
    }

    private static Path contractFile() {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts", "source-bundle.json");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("packages", "api-contracts", "source-bundle.json");
    }

    private static Path probeScript() {
        Path fromModule = Path.of("src", "main", "docker", "probe", "probe.sh");
        return Files.isRegularFile(fromModule) ? fromModule : Path.of("services", "runner", "src", "main", "docker", "probe", "probe.sh");
    }
}
