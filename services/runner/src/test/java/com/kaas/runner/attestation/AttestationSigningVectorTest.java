package com.kaas.runner.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The producer's half of the signing contract, checked against the same vectors the verifier uses.
 *
 * <p>These are the same fixed files {@code apps/api} consumes, and neither module computed them: the preimage
 * was produced by a third implementation written from
 * {@code packages/api-contracts/sandbox-security-attestation-signing.md} alone. Two implementations that agree
 * only with each other can be two implementations of one misunderstanding.
 *
 * <p>If this goes red, the producer and the contract have diverged. Regenerating the vectors to make it pass
 * is a schema change and invalidates every attestation in existence — it is never the quiet repair.
 */
@DisplayName("Attestation signing vectors (producer side)")
class AttestationSigningVectorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("the producer builds the exact preimage bytes the contract fixes")
    void thePreimageMatchesTheFixedVector() {
        JsonNode expected = load("expected.json");

        byte[] canonical = payloadOf(load("canonical-payload.json")).canonicalBytes();

        assertThat(HexFormat.of().formatHex(canonical))
                .as("the canonical preimage is fixed by contract; a change here is a schema change")
                .isEqualTo(expected.get("preimageHex").stringValue());
        assertThat(canonical.length).isEqualTo(expected.get("preimageLength").asInt());
    }

    @Test
    @DisplayName("the producer's digest matches the fixed vector")
    void theDigestMatchesTheFixedVector() {
        assertThat(payloadOf(load("canonical-payload.json")).payloadDigest())
                .isEqualTo(load("expected.json").get("payloadDigest").stringValue());
    }

    @Test
    @DisplayName("signing the fixed payload with the fixed key reproduces the fixed signature")
    void signingReproducesTheFixedSignature() {
        // Ed25519 is deterministic — no nonce, no per-signature entropy — so this is an equality assertion
        // rather than a verify(). It is the stronger check: a verify() would pass for any correct signature,
        // including one over a preimage this test never looked at.
        var payload = payloadOf(load("canonical-payload.json"));

        byte[] signature = signerForTestKey("kaas-test-key-1").sign(payload.canonicalBytes());

        assertThat(Base64.getEncoder().encodeToString(signature))
                .isEqualTo(load("expected.json").get("signature").stringValue());
    }

    @Test
    @DisplayName("the signed document the producer emits is the one the vector fixes")
    void theProducedDocumentMatchesTheVector() {
        var payload = payloadOf(load("canonical-payload.json"));

        SignedAttestation signed = SignedAttestation.of(payload, signerForTestKey("kaas-test-key-1"));

        JsonNode expected = load("expected.json");
        assertThat(signed.payloadDigest()).isEqualTo(expected.get("payloadDigest").stringValue());
        assertThat(signed.signature()).isEqualTo(expected.get("signature").stringValue());
        // And the serialized artifact parses back to the same values, so a round trip through the transport
        // format cannot change what a verifier will reconstruct.
        JsonNode emitted = MAPPER.readTree(signed.toJson());
        JsonNode fixed = load("valid-signed.json");
        for (String property : new String[] {
            "schemaVersion", "attestationId", "producerVersion", "keyId", "signatureAlgorithm",
            "securityProfileVersion", "runtime", "runtimeSubject", "runtimeGeneration",
            "probeImageDigest", "egressProxyImageDigest", "assessedAt", "payloadDigest", "signature"
        }) {
            assertThat(emitted.get(property).stringValue())
                    .as("%s", property)
                    .isEqualTo(fixed.get(property).stringValue());
        }
    }

    @Test
    @DisplayName("a payload with no egress evidence signs differently from one claiming an empty image")
    void absentIsNotTheEmptyString() {
        var withEvidence = payloadOf(load("canonical-payload.json"));
        var withoutEvidence = new AttestationPayload(
                withEvidence.schemaVersion(), withEvidence.attestationId(), withEvidence.producerVersion(),
                withEvidence.keyId(), withEvidence.signatureAlgorithm(), withEvidence.securityProfileVersion(),
                withEvidence.runtime(), withEvidence.runtimeSubject(), withEvidence.runtimeGeneration(),
                withEvidence.probeImageDigest(), Optional.empty(), withEvidence.assessedAt(),
                withEvidence.mandatoryControls(), withEvidence.egressControls());
        var withEmptyString = new AttestationPayload(
                withEvidence.schemaVersion(), withEvidence.attestationId(), withEvidence.producerVersion(),
                withEvidence.keyId(), withEvidence.signatureAlgorithm(), withEvidence.securityProfileVersion(),
                withEvidence.runtime(), withEvidence.runtimeSubject(), withEvidence.runtimeGeneration(),
                withEvidence.probeImageDigest(), Optional.of(""), withEvidence.assessedAt(),
                withEvidence.mandatoryControls(), withEvidence.egressControls());

        // Three distinct statements, three distinct preimages. "No egress claim" and "an egress claim about
        // nothing" must not be interchangeable, or an artifact could lose its proxy image identity silently.
        assertThat(withoutEvidence.payloadDigest()).isNotEqualTo(withEvidence.payloadDigest());
        assertThat(withoutEvidence.payloadDigest()).isNotEqualTo(withEmptyString.payloadDigest());
    }

    // ---------------------------------------------------------------------------------------------------

    private static AttestationSigner signerForTestKey(String keyId) {
        try {
            String pkcs8 = load("test-keys.json").get("keys").get(keyId).get("privateKeyPkcs8").stringValue();
            Path file = Files.createTempFile("kaas-test-signing-key", ".pk8");
            Files.writeString(file, pkcs8);
            file.toFile().deleteOnExit();
            return AttestationSigner.fromFile(keyId, file);
        } catch (Exception impossible) {
            throw new IllegalStateException("The test key material is unusable", impossible);
        }
    }

    private static AttestationPayload payloadOf(JsonNode root) {
        return new AttestationPayload(
                root.get("schemaVersion").stringValue(),
                root.get("attestationId").stringValue(),
                root.get("producerVersion").stringValue(),
                root.get("keyId").stringValue(),
                root.get("signatureAlgorithm").stringValue(),
                root.get("securityProfileVersion").stringValue(),
                root.get("runtime").stringValue(),
                root.get("runtimeSubject").stringValue(),
                root.get("runtimeGeneration").stringValue(),
                root.get("probeImageDigest").stringValue(),
                Optional.ofNullable(root.get("egressProxyImageDigest")).map(JsonNode::stringValue),
                Instant.parse(root.get("assessedAt").stringValue()),
                controlsOf(root.get("mandatoryControls")),
                controlsOf(root.get("egressControls")));
    }

    private static Map<String, String> controlsOf(JsonNode node) {
        Map<String, String> controls = new LinkedHashMap<>();
        node.propertyNames().forEach(name -> controls.put(name, node.get(name).stringValue()));
        return controls;
    }

    private static JsonNode load(String name) {
        return MAPPER.readTree(vector(name));
    }

    /** One published vector, located from the module or the repository root. */
    static String vector(String name) {
        Path fromModule = Path.of("..", "..", "packages", "api-contracts",
                "fixtures", "sandbox-security-attestation-signing", name);
        Path path = fromModule.toFile().isFile()
                ? fromModule
                : Path.of("packages", "api-contracts", "fixtures",
                        "sandbox-security-attestation-signing", name);
        try {
            return Files.readString(path);
        } catch (Exception missing) {
            throw new IllegalStateException("The signing vector " + name + " is missing", missing);
        }
    }
}
