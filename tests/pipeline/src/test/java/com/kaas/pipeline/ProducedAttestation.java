package com.kaas.pipeline;

import com.kaas.api.execution.domain.RequiredSecurityControls;
import com.kaas.runner.attestation.AttestationSigner;
import com.kaas.runner.attestation.RuntimeIdentity;
import com.kaas.runner.attestation.SandboxSecurityAttestationProducer;
import com.kaas.runner.gate.EgressEnforcementAssessment;
import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.gate.SecurityCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attestations built by the RUNNER's producer, for the CONTROL PLANE's verifier to check.
 *
 * <h2>Why this module and not either of the others</h2>
 *
 * <p>This is the interoperability claim, and it is the one neither side can make alone. {@code apps/api} tests
 * its verifier against fixed vectors; {@code services/runner} tests its producer against the same vectors. Only
 * here do a real producer and a real verifier meet — the producer builds a document from assessment objects,
 * signs it with a published test key, and the control plane parses, reconstructs, and verifies it over real
 * HTTP with the key pinned in its own configuration.
 *
 * <p>Two implementations that pass the same vectors should interoperate. "Should" is the word this module
 * exists to remove.
 *
 * <p>The signing key here is the repository's published test key. Its private half is in the tree deliberately:
 * a key anyone can read can never be mistaken for a production signer, and production has no default trust
 * root that would admit it.
 */
final class ProducedAttestation {

    static final String KEY_ID = "kaas-test-key-1";

    /** The subject these fixtures attest, which a test control plane must be configured to accept. */
    static final String RUNTIME_SUBJECT = "kaas.runtime.pipeline";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ProducedAttestation() {}

    /** Mandatory evidence only — what a deployment that cannot enforce an allowlist produces. */
    static String mandatoryOnly(String profileVersion, Instant assessedAt) {
        return produce(profileVersion, assessedAt, EgressEnforcementAssessment.nothingObserved());
    }

    /** Mandatory and egress evidence, all passing. */
    static String withEgress(String profileVersion, Instant assessedAt) {
        List<SecurityCheck> egress = new ArrayList<>();
        RequiredSecurityControls.EGRESS.forEach(control -> egress.add(passing(control)));
        return produce(
                profileVersion,
                assessedAt,
                new EgressEnforcementAssessment(
                        Optional.of("sha256:" + "2".repeat(64)), List.copyOf(egress)));
    }

    private static String produce(
            String profileVersion, Instant assessedAt, EgressEnforcementAssessment egress) {
        List<SecurityCheck> mandatory = new ArrayList<>();
        // Scoped to the profile version this attestation claims, because the required set now differs by
        // runtime and an artifact carrying the other runtime's controls is one the verifier must refuse.
        RequiredSecurityControls.mandatoryFor(profileVersion).forEach(c -> mandatory.add(passing(c)));
        // Real assessment objects, not a verdict map. The producer has no API that takes one, which is the
        // point of the slice: a caller who wants to claim a control passed has to produce an assessment
        // saying so, and only a gate produces those.
        // The sandbox runtime the profile version implies, so the two signed answers agree. A document whose
        // profile version and runtime disagree is refused, and this pipeline exists to produce genuine ones.
        var assessment = new HostileExecutionAssessment(
                profileVersion,
                "docker",
                "kaas.sandbox.gvisor.v1".equals(profileVersion)
                        ? ExecutionRuntimeType.GVISOR.name()
                        : ExecutionRuntimeType.DOCKER.name(),
                assessedAt,
                List.copyOf(mandatory));
        return new SandboxSecurityAttestationProducer(signer())
                .produce(
                        assessment,
                        egress,
                        new RuntimeIdentity("docker", RUNTIME_SUBJECT, "gen:" + "a".repeat(32)),
                        "sha256:" + "1".repeat(64))
                .toJson();
    }

    private static SecurityCheck passing(String control) {
        return new SecurityCheck(
                control, SecurityCheck.Verdict.PASS, SecurityCheck.Enforcement.MANDATORY, "observed");
    }

    /** The pinned-key configuration a control plane needs to accept what this produces. */
    static String trustedKeys() {
        return KEY_ID + "=" + keys().get(KEY_ID).get("publicKeySpki").stringValue();
    }

    private static AttestationSigner signer() {
        try {
            Path file = Files.createTempFile("kaas-pipeline-test-key", ".pk8");
            Files.writeString(file, keys().get(KEY_ID).get("privateKeyPkcs8").stringValue());
            file.toFile().deleteOnExit();
            return AttestationSigner.fromFile(KEY_ID, file);
        } catch (Exception impossible) {
            throw new IllegalStateException("The published test key is unusable", impossible);
        }
    }

    private static JsonNode keys() {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts",
                "fixtures", "sandbox-security-attestation-signing", "test-keys.json");
        Path path = fromModule.toFile().isFile()
                ? fromModule
                : Path.of("packages", "api-contracts", "fixtures",
                        "sandbox-security-attestation-signing", "test-keys.json");
        try {
            return MAPPER.readTree(Files.readString(path)).get("keys");
        } catch (Exception missing) {
            throw new IllegalStateException("The published test keys are missing", missing);
        }
    }
}
