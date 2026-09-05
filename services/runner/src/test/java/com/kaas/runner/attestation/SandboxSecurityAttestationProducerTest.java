package com.kaas.runner.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.gate.EgressEnforcementAssessment;
import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.gate.SecurityCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the producer will and will not sign.
 *
 * <p>Its inputs are assessment objects that only the gates construct. There is no {@code produce(Map)} and no
 * parameter that sets a verdict, which is the whole point of the slice — a caller who wants to claim a control
 * passed has to produce an assessment saying so.
 */
@DisplayName("Sandbox security attestation producer")
class SandboxSecurityAttestationProducerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String PROBE = "sha256:" + "1".repeat(64);

    @Test
    @DisplayName("verdicts come from the assessment, control for control")
    void verdictsAreReadOffTheAssessment() {
        var signed = produce(assessment(
                mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS),
                mandatory("NO_DOCKER_SOCKET", SecurityCheck.Verdict.FAIL),
                mandatory("KERNEL_PATHS_MASKED", SecurityCheck.Verdict.UNSUPPORTED)));

        assertThat(signed.payload().mandatoryControls())
                .containsEntry("NON_ROOT_UID", "PASS")
                .containsEntry("NO_DOCKER_SOCKET", "FAIL")
                // UNSUPPORTED survives as itself. "This host cannot enforce it" and "this host was shown not
                // to enforce it" are different facts, neither is a pass, and folding them together would
                // destroy the distinction the gate went to trouble to report.
                .containsEntry("KERNEL_PATHS_MASKED", "UNSUPPORTED");
    }

    @Test
    @DisplayName("deployment-specific observations are not signed as mandatory controls")
    void deploymentSpecificChecksAreNotMandatoryControls() {
        // THE REGRESSION. The gate reports deployment-specific hardening — user namespaces, AppArmor, a
        // rootless daemon — which is real, is never required, and was being folded into the mandatory set.
        // The control plane requires exactly the contracted set and matches it for EQUALITY, so every
        // attestation the producer made was refused as CONTROL_FAILED while naming a control nothing has ever
        // required. Found by running the real chain, not by any unit test that existed at the time.
        var signed = produce(assessment(
                mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS),
                new SecurityCheck(
                        "USER_NAMESPACE",
                        SecurityCheck.Verdict.UNSUPPORTED,
                        SecurityCheck.Enforcement.DEPLOYMENT_SPECIFIC,
                        "not enabled on this host")));

        assertThat(signed.payload().mandatoryControls())
                .as("the signed claim covers exactly the controls a verifier checks")
                .containsOnlyKeys("NON_ROOT_UID");
    }

    @Test
    @DisplayName("no egress evidence means no egress claim and no proxy image")
    void noEgressEvidenceMeansNoClaim() {
        var signed = produce(assessment(mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS)));

        assertThat(signed.payload().egressControls()).isEmpty();
        // And no proxy image. An artifact naming an image it demonstrated nothing about would be a claim
        // about an artifact, made by evidence that never touched it.
        assertThat(signed.payload().egressProxyImageDigest()).isEmpty();
    }

    @Test
    @DisplayName("egress evidence carries the image it was gathered with")
    void egressEvidenceCarriesItsImage() {
        var egress = new EgressEnforcementAssessment(
                Optional.of("sha256:" + "2".repeat(64)),
                List.of(new SecurityCheck(
                        "EGRESS_PROXY_READY",
                        SecurityCheck.Verdict.PASS,
                        SecurityCheck.Enforcement.MANDATORY,
                        "serving")));

        var signed = new SandboxSecurityAttestationProducer(signer())
                .produce(
                        assessment(mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS)),
                        egress,
                        runtime(),
                        PROBE);

        // EGRESS_PROXY_READY=PASS says a proxy was ready. It does not say WHICH, and the two travel together
        // so an artifact cannot pair one run's verdicts with another run's image.
        assertThat(signed.payload().egressProxyImageDigest())
                .contains("sha256:" + "2".repeat(64));
    }

    @Test
    @DisplayName("an assessment with no observations is not signed at all")
    void nothingObservedIsNotSigned() {
        // Signing it would produce an authentic statement that says nothing. The control plane would refuse
        // it as incomplete evidence rather than as "the gate never ran", and those want different
        // investigations.
        assertThatThrownBy(() -> produce(assessment()))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failed -> assertThat(((AttestationProductionFailed) failed).failure())
                        .isEqualTo(AttestationFailure.ASSESSMENT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a probe image named by tag rather than digest is refused")
    void aTaggedProbeImageIsRefused() {
        // A mutable tag is not an identity, and the whole assessment describes what that image did.
        assertThatThrownBy(() -> new SandboxSecurityAttestationProducer(signer())
                        .produce(
                                assessment(mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS)),
                                EgressEnforcementAssessment.nothingObserved(),
                                runtime(),
                                "kaas/probe:latest"))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failed -> assertThat(((AttestationProductionFailed) failed).failure())
                        .isEqualTo(AttestationFailure.ASSESSMENT_INCOMPLETE));
    }

    @Test
    @DisplayName("the same evidence and key produce the same artifact, field for field")
    void productionIsDeterministic() {
        var first = produce(assessment(mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS)));
        var second = produce(assessment(mandatory("NON_ROOT_UID", SecurityCheck.Verdict.PASS)));

        // Ed25519 is deterministic, so identical evidence signs identically. The attestation id differs by
        // design — it identifies the statement, not the evidence — and everything the signature covers apart
        // from it is byte-identical.
        assertThat(second.payloadDigest()).isNotEqualTo(first.payloadDigest());
        assertThat(MAPPER.readTree(first.toJson()).get("mandatoryControls").toString())
                .isEqualTo(MAPPER.readTree(second.toJson()).get("mandatoryControls").toString());
    }

    @Test
    @DisplayName("a runtime subject is required and has no discoverable default")
    void aRuntimeSubjectIsRequired() {
        // Discovering one would mean publishing a hostname or a machine id. Requiring one means an operator
        // decides what this runtime is called, on both sides.
        assertThatThrownBy(() -> new RuntimeIdentity("docker", "  ", "gen:x"))
                .isInstanceOf(AttestationProductionFailed.class);
    }

    @Test
    @DisplayName("a missing signing key fails the producer rather than generating one")
    void aMissingKeyFailsRatherThanGenerating() {
        // An automatically generated signer destroys pinning continuity: the control plane would not have the
        // new key, every attestation would be refused, and the obvious repair is to trust whatever turned up.
        assertThatThrownBy(() -> AttestationSigner.fromFile("kaas-test-key-1", Path.of("no-such-key.pk8")))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failed -> assertThat(((AttestationProductionFailed) failed).failure())
                        .isEqualTo(AttestationFailure.SIGNING_KEY_UNUSABLE));
    }

    @Test
    @DisplayName("a key of the wrong curve fails the producer")
    void aWrongCurveKeyFails() throws Exception {
        // getAlgorithm() reports "EdDSA" for both Ed25519 and Ed448, so an algorithm-name check would accept
        // this. The curve is checked explicitly.
        var ed448 = java.security.KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        Path file = Files.createTempFile("kaas-ed448", ".pk8");
        file.toFile().deleteOnExit();
        Files.writeString(file, java.util.Base64.getEncoder().encodeToString(ed448.getPrivate().getEncoded()));

        assertThatThrownBy(() -> AttestationSigner.fromFile("kaas-test-key-1", file))
                .isInstanceOf(AttestationProductionFailed.class)
                .satisfies(failed -> assertThat(((AttestationProductionFailed) failed).failure())
                        .isEqualTo(AttestationFailure.SIGNING_KEY_UNUSABLE));
    }

    @Test
    @DisplayName("the signer never prints its key")
    void theSignerNeverPrintsItsKey() {
        assertThat(signer().toString()).contains("redacted").doesNotContain("MC4CAQ");
    }

    // ---------------------------------------------------------------------------------------------------

    private static SignedAttestation produce(HostileExecutionAssessment assessment) {
        return new SandboxSecurityAttestationProducer(signer())
                .produce(assessment, EgressEnforcementAssessment.nothingObserved(), runtime(), PROBE);
    }

    private static HostileExecutionAssessment assessment(SecurityCheck... checks) {
        return new HostileExecutionAssessment(
                "kaas.sandbox.v1",
                "docker",
                ExecutionRuntimeType.DOCKER.name(),
                Instant.parse("2026-09-05T09:12:33Z"),
                List.of(checks));
    }

    private static SecurityCheck mandatory(String control, SecurityCheck.Verdict verdict) {
        return new SecurityCheck(control, verdict, SecurityCheck.Enforcement.MANDATORY, "observed");
    }

    private static RuntimeIdentity runtime() {
        return new RuntimeIdentity("docker", "kaas.runtime.test", "gen:" + "a".repeat(32));
    }

    private static AttestationSigner signer() {
        try {
            String pkcs8 = MAPPER.readTree(AttestationSigningVectorTest.vector("test-keys.json"))
                    .get("keys").get("kaas-test-key-1").get("privateKeyPkcs8").stringValue();
            Path file = Files.createTempFile("kaas-producer-test-key", ".pk8");
            Files.writeString(file, pkcs8);
            file.toFile().deleteOnExit();
            return AttestationSigner.fromFile("kaas-test-key-1", file);
        } catch (Exception impossible) {
            throw new IllegalStateException("The published test key is unusable", impossible);
        }
    }
}
