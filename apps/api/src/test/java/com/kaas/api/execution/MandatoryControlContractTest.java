package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void everyProfileVersionsRequiredSetMatchesTheSharedContract() throws Exception {
        // Every version in the file, and every version this build knows, checked against each other in both
        // directions. Iterating only over what the code knows would let the contract gain a profile version
        // this build silently cannot authorize; iterating only over the file would let the code carry one
        // nobody agreed to.
        Set<String> declared = sharedProfileVersions();
        assertThat(RequiredSecurityControls.knownProfileVersions())
                .as("the profile versions this build can judge must be exactly those in %s", locate())
                .isEqualTo(declared);
        for (String version : declared) {
            assertThat(RequiredSecurityControls.mandatoryFor(version))
                    .as("required controls for %s must equal the shared contract at %s", version, locate())
                    .isEqualTo(sharedControlsFor(version));
        }
    }

    @Test
    void anUnknownProfileVersionIsRefusedRatherThanTreatedAsRequiringNothing() {
        // An empty required set is satisfied by an attestation that demonstrates nothing at all, so the
        // permissive default is the dangerous one.
        assertThatThrownBy(() -> RequiredSecurityControls.mandatoryFor("kaas.sandbox.unheard-of.v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTwoRuntimesDoNotRequireTheSameControls() throws Exception {
        // Anti-vacuity for the whole runtime-scoping exercise. If the two sets were equal, every test above
        // would still pass and the scoping would be an elaborate way of expressing one list.
        assertThat(RequiredSecurityControls.mandatoryFor("kaas.sandbox.v1"))
                .isNotEqualTo(RequiredSecurityControls.mandatoryFor("kaas.sandbox.gvisor.v1"));
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
        for (String version : RequiredSecurityControls.knownProfileVersions()) {
            assertThat(RequiredSecurityControls.mandatoryFor(version))
                    .as("profile version %s", version)
                    .doesNotContainAnyElementsOf(RequiredSecurityControls.EGRESS);
        }
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

    private static Set<String> sharedProfileVersions() throws Exception {
        var node = JsonMapper.builder().build()
                .readTree(Files.readString(locate()))
                .get("controlsByProfileVersion");
        return StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(node.propertyNames().iterator(), 0),
                        false)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> sharedControlsFor(String profileVersion) throws Exception {
        return StreamSupport.stream(
                        JsonMapper.builder().build()
                                .readTree(Files.readString(locate()))
                                .get("controlsByProfileVersion")
                                .get(profileVersion)
                                .spliterator(),
                        false)
                .map(control -> control.stringValue())
                .collect(Collectors.toUnmodifiableSet());
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
