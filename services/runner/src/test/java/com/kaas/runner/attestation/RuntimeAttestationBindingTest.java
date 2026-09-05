package com.kaas.runner.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner's own answer to "does this command's evidence describe me?"
 *
 * <p>Independent of the control plane's verification, and only ever able to move the answer towards refusal.
 * A runner cannot use this to make a command acceptable; it can only refuse one.
 */
@DisplayName("Runtime attestation binding")
class RuntimeAttestationBindingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("the evidence identity is recomputed from the fields, not read out of the document")
    void theDigestIsRecomputedRatherThanRead() {
        String document = AttestationSigningVectorTest.vector("valid-signed.json");
        String published = MAPPER.readTree(document).get("payloadDigest").stringValue();

        assertThat(RuntimeAttestationBinding.of(document).payloadDigest()).isEqualTo(published);

        // Now edit the document's OWN digest property. A binding that read it would happily adopt the edited
        // value and this runtime would then accept commands bound to evidence nobody produced.
        var tampered = MAPPER.readTree(document);
        ((tools.jackson.databind.node.ObjectNode) tampered).put("payloadDigest", "sha256:" + "0".repeat(64));

        assertThat(RuntimeAttestationBinding.of(tampered.toString()).payloadDigest())
                .as("the recomputed identity ignores what the document claims about itself")
                .isEqualTo(published);
    }

    @Test
    @DisplayName("editing a control changes the identity, so foreign evidence cannot borrow it")
    void editingEvidenceChangesItsIdentity() {
        var document = MAPPER.readTree(AttestationSigningVectorTest.vector("valid-signed.json"));
        String original = RuntimeAttestationBinding.of(document.toString()).payloadDigest();

        ((tools.jackson.databind.node.ObjectNode) document.get("mandatoryControls"))
                .put("NO_DOCKER_SOCKET", "FAIL");

        assertThat(RuntimeAttestationBinding.of(document.toString()).payloadDigest()).isNotEqualTo(original);
    }

    @Test
    @DisplayName("a missing artifact fails rather than binding to nothing")
    void aMissingArtifactFails() {
        // Falling back to "no binding" would turn a deployment mistake into a silently disabled control, and
        // the symptom would be a runner that accepts any evidence at all.
        assertThatThrownBy(() -> RuntimeAttestationBinding.fromFile(Path.of("does-not-exist.json")))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failure -> assertThat(((AttestationProductionFailed) failure).failure())
                        .isEqualTo(AttestationFailure.ASSESSMENT_UNAVAILABLE));
    }

    @Test
    @DisplayName("an artifact that is not a v3 document fails rather than binding to a guess")
    void aMalformedArtifactFails() throws Exception {
        Path file = Files.createTempFile("kaas-not-an-attestation", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, "{\"securityGatePassed\":true}");

        assertThatThrownBy(() -> RuntimeAttestationBinding.fromFile(file))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failure -> assertThat(((AttestationProductionFailed) failure).failure())
                        .isEqualTo(AttestationFailure.ASSESSMENT_INCOMPLETE));
    }

    @Test
    @DisplayName("the subject is carried for diagnostics and is never what a command is compared against")
    void theSubjectIsCarriedButNotCompared() {
        // Comparing a command against a SUBJECT would be the weaker check: two different assessments of the
        // same runtime share a subject and describe different security postures. The digest is the identity.
        assertThat(RuntimeAttestationBinding.of(AttestationSigningVectorTest.vector("valid-signed.json"))
                        .runtimeSubject())
                .isEqualTo("kaas.runtime.vector");
    }
}
