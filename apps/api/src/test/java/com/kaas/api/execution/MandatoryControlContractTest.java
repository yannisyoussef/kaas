package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.AttestationPayloadFields;
import com.kaas.api.execution.domain.RequiredSecurityControls;
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
        assertThat(RequiredSecurityControls.MANDATORY)
                .as("the control plane's required set must equal the shared contract at %s", locate())
                .isEqualTo(sharedList("controls"));
    }

    @Test
    void theRequiredEgressControlSetMatchesTheSharedContract() throws Exception {
        // Asserted separately from the mandatory set because the two are demanded under different conditions:
        // every execution needs the mandatory controls, only an ALLOWLIST execution needs these. A single
        // assertion over the union would stay green if a control moved between the sets, which would silently
        // change whether a DENY_ALL run depends on the egress subsystem being healthy.
        assertThat(RequiredSecurityControls.EGRESS)
                .as("the control plane's required egress set must equal the shared contract at %s", locate())
                .isEqualTo(sharedList("egressControls"));
    }

    @Test
    void theTwoSetsShareNoControl() throws Exception {
        // A control in both sets would be required unconditionally through one door and conditionally through
        // the other, and which rule applied would depend on which check ran first.
        assertThat(RequiredSecurityControls.MANDATORY)
                .doesNotContainAnyElementsOf(RequiredSecurityControls.EGRESS);
    }

    @Test
    void theContractDeclaresTheSchemaVersionTheControlPlaneRequires() throws Exception {
        // The attestation's schema version and the contract's are one statement about one document format.
        // They drifted apart the moment the egress controls were added on one side only, and the symptom
        // would have been every attestation in every deployment being refused with no obvious cause.
        String declared = JsonMapper.builder()
                .build()
                .readTree(Files.readString(locate()))
                .get("schemaVersion")
                .stringValue();
        assertThat(declared).isEqualTo(AttestationPayloadFields.SCHEMA_VERSION);
    }

    private static Set<String> sharedList(String field) throws Exception {
        return StreamSupport.stream(
                        JsonMapper.builder()
                                .build()
                                .readTree(Files.readString(locate()))
                                .get(field)
                                .spliterator(),
                        false)
                .map(node -> node.stringValue())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Located from the module rather than from configuration, so the test cannot be pointed at a copy. */
    private static Path locate() {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts", "mandatory-sandbox-controls.json");
        return Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("packages", "api-contracts", "mandatory-sandbox-controls.json");
    }
}
