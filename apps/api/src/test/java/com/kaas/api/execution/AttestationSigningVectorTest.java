package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.AttestationPayloadFields;
import com.kaas.api.execution.domain.AttestationVerification;
import com.kaas.api.execution.domain.PinnedVerificationKeys;
import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The control plane's half of the signing contract, checked against vectors it did not compute.
 *
 * <h2>Why fixed vectors rather than round-tripping</h2>
 *
 * <p>{@code verify(sign(payload))} passing proves only that one implementation is self-consistent. Two
 * implementations of a contract can make the same mistake and agree perfectly. The vectors in
 * {@code packages/api-contracts/fixtures/sandbox-security-attestation-signing/} are fixed in the repository,
 * and the preimage in them was produced by a third implementation written from the contract document alone —
 * so this test compares against a value neither Java side computed.
 *
 * <p>If this file goes red after a change to the preimage, that is the contract saying the change was not
 * agreed. Regenerating the vectors to make it pass is the one repair that must never be made silently: it is
 * a schema change, and it invalidates every attestation in existence.
 */
@DisplayName("Attestation signing vectors")
class AttestationSigningVectorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("this side reconstructs the exact preimage bytes the contract fixes")
    void thePreimageMatchesTheFixedVector() {
        JsonNode expected = load("expected.json");

        byte[] canonical = payloadOf(load("canonical-payload.json")).canonicalBytes();

        // The bytes, not merely their digest. A digest comparison would pass for two different preimages that
        // happened to be produced by the same bug on both sides; the hex says exactly where they diverge.
        assertThat(HexFormat.of().formatHex(canonical))
                .as("the canonical preimage is fixed by contract and was cross-checked against an "
                        + "independent implementation; a change here is a schema change")
                .isEqualTo(expected.get("preimageHex").stringValue());
        assertThat(canonical.length).isEqualTo(expected.get("preimageLength").asInt());
    }

    @Test
    @DisplayName("the payload digest matches, and is recomputed rather than read")
    void theDigestMatchesTheFixedVector() {
        assertThat(payloadOf(load("canonical-payload.json")).payloadDigest())
                .isEqualTo(load("expected.json").get("payloadDigest").stringValue());
    }

    @Test
    @DisplayName("the signed document produced elsewhere verifies here")
    void theSignedDocumentVerifies() {
        var result = verifier("kaas-test-key-1").verify(document("valid-signed.json"));

        assertThat(result.outcome())
                .as("a document signed by the producer's implementation must verify under this one, or the "
                        + "two have disagreed about the contract")
                .isEqualTo(AttestationVerification.VALID);
        assertThat(result.attestation()).isPresent();
        assertThat(result.attestation().orElseThrow().payloadDigest())
                .isEqualTo(load("expected.json").get("payloadDigest").stringValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "tampered-mandatory-verdict",
        "tampered-sandbox-runtime",
        "superseded-v3",
        "dropped-mandatory-control",
        "tampered-egress-verdict",
        "tampered-assessed-at",
        "tampered-profile-version",
        "tampered-probe-image-digest",
        "tampered-proxy-image-digest",
        "tampered-runtime",
        "tampered-runtime-subject",
        "tampered-runtime-generation",
        "tampered-payload-digest",
        "tampered-signature",
        "key-id-does-not-match-signature",
        "signed-by-a-different-trusted-key",
        "unknown-key-id",
        "wrong-algorithm",
        "missing-signature",
        "unknown-property",
        "unsigned-v2"
    })
    @DisplayName("every vector that mutates one field family is refused")
    void everyTamperedVectorIsRefused(String vector) {
        // BOTH test keys pinned, deliberately. With only one, "signed by a different trusted key" would be
        // refused as an unknown key and would prove nothing about key selection — the mutation would be
        // killed by the wrong guard.
        var result = verifier("kaas-test-key-1", "kaas-test-key-2")
                .verify(document("invalid/" + vector + ".json"));

        assertThat(result.outcome())
                .as("%s", vector)
                .isNotEqualTo(AttestationVerification.VALID);
        assertThat(result.attestation())
                .as("a refusal produces no attestation at all, so nothing downstream can read its verdicts")
                .isEmpty();
    }

    @Test
    @DisplayName("each refusal says which stage refused, and says nothing the document supplied")
    void refusalsAreCategorisedByStage() {
        var trusting = verifier("kaas-test-key-1", "kaas-test-key-2");

        // The categories matter: an operator with an old artifact, an operator with a rotated-out key, and an
        // operator being attacked need three different actions, and one generic "invalid" serves none of them.
        assertThat(trusting.verify(document("invalid/unsigned-v2.json")).outcome())
                .isEqualTo(AttestationVerification.UNSUPPORTED_SCHEMA);
        // v3 is refused exactly as v2 is. It is genuinely signed -- under the v3 domain separator -- so this
        // is a superseded schema being refused rather than a broken document failing anyway.
        assertThat(trusting.verify(document("invalid/superseded-v3.json")).outcome())
                .isEqualTo(AttestationVerification.UNSUPPORTED_SCHEMA);
        assertThat(trusting.verify(document("invalid/unknown-key-id.json")).outcome())
                .isEqualTo(AttestationVerification.UNKNOWN_KEY);
        assertThat(trusting.verify(document("invalid/unknown-property.json")).outcome())
                .isEqualTo(AttestationVerification.MALFORMED);
        assertThat(trusting.verify(document("invalid/wrong-algorithm.json")).outcome())
                .isEqualTo(AttestationVerification.MALFORMED);
        // Tampering with a signed field leaves the digest describing the untampered payload, so the digest
        // check refuses first — before any signature verification is attempted. That is the cheap check
        // catching it, and it is the order the contract specifies.
        assertThat(trusting.verify(document("invalid/tampered-runtime-subject.json")).outcome())
                .isEqualTo(AttestationVerification.DIGEST_MISMATCH);
        // Here the payload is untouched, so the digest matches and only the signature can refuse it. This is
        // the case that proves signature verification actually runs.
        assertThat(trusting.verify(document("invalid/signed-by-a-different-trusted-key.json")).outcome())
                .isEqualTo(AttestationVerification.INVALID_SIGNATURE);
        assertThat(trusting.verify(document("invalid/tampered-signature.json")).outcome())
                .isEqualTo(AttestationVerification.INVALID_SIGNATURE);
    }

    @Test
    @DisplayName("with no key pinned, nothing verifies and the reason says so")
    void withNoTrustRootNothingVerifies() {
        var none = new SandboxSecurityAttestationVerifier(new PinnedVerificationKeys() {
            @Override
            public Optional<PublicKey> keyFor(String keyId) {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return false;
            }
        });

        var result = none.verify(document("valid-signed.json"));

        // A genuinely valid document, refused because nothing could have checked it. Distinguished from an
        // invalid signature because the operator action is entirely different.
        assertThat(result.outcome()).isEqualTo(AttestationVerification.TRUST_ROOT_UNAVAILABLE);
        assertThat(result.attestation()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------

    private static SandboxSecurityAttestationVerifier verifier(String... trustedKeyIds) {
        JsonNode keys = load("test-keys.json").get("keys");
        Map<String, PublicKey> pinned = new LinkedHashMap<>();
        for (String keyId : trustedKeyIds) {
            pinned.put(keyId, publicKeyOf(keys.get(keyId).get("publicKeySpki").stringValue()));
        }
        return new SandboxSecurityAttestationVerifier(new PinnedVerificationKeys() {
            @Override
            public Optional<PublicKey> keyFor(String keyId) {
                return Optional.ofNullable(pinned.get(keyId));
            }

            @Override
            public boolean available() {
                return !pinned.isEmpty();
            }
        });
    }

    private static PublicKey publicKeyOf(String base64Spki) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
        } catch (Exception impossible) {
            throw new IllegalStateException("The test key material is unusable", impossible);
        }
    }

    @Test
    @DisplayName("every negative vector on disk is actually exercised, and every named one exists")
    void theNegativeVectorsAndTheirIndexAgree() throws Exception {
        // Anti-vacuity for the whole refusal suite. A vector added to the directory but not to the list above
        // is a refusal nobody checks; a name in the index with no file is a claim about a document that does
        // not exist. Both have happened, and neither fails anything on its own.
        Path fromModule = Path.of("..", "..", "packages", "api-contracts",
                "fixtures", "sandbox-security-attestation-signing", "invalid");
        Path directory = fromModule.toFile().isDirectory()
                ? fromModule
                : Path.of("packages", "api-contracts", "fixtures",
                        "sandbox-security-attestation-signing", "invalid");
        Set<String> onDisk;
        try (var files = java.nio.file.Files.list(directory)) {
            onDisk = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !name.equals("index.json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }

        JsonNode index = MAPPER.readTree(document("invalid/index.json")).get("vectors");
        Set<String> named = new java.util.TreeSet<>();
        index.propertyNames().forEach(named::add);

        assertThat(named).as("every vector on disk must be explained in the index").isEqualTo(onDisk);
        assertThat(EXERCISED)
                .as("every vector on disk must be exercised by a test that names it")
                .containsAll(onDisk);
    }

    /**
     * The vectors this class refuses by name, in one place so the test above can compare against it.
     *
     * <p>Kept as data rather than read back out of the annotation, because a reflective read of the very
     * annotation under test would agree with itself no matter what it said.
     */
    private static final Set<String> EXERCISED = Set.of(
            // Authentic by construction, and refused at AUTHORIZATION rather than verification: a document
            // whose profile version and sandbox runtime disagree is properly signed, so the suite that
            // checks authenticity is the wrong place for it. VerifiedAttestationSemanticsTest names it.
            "self-contradictory-runtime",
            "tampered-mandatory-verdict", "tampered-sandbox-runtime",
            "superseded-v3", "dropped-mandatory-control", "tampered-egress-verdict", "tampered-assessed-at",
            "tampered-profile-version", "tampered-probe-image-digest", "tampered-proxy-image-digest",
            "tampered-runtime", "tampered-runtime-subject", "tampered-runtime-generation",
            "tampered-payload-digest", "tampered-signature", "missing-signature", "unknown-property",
            "unknown-key-id", "signed-by-a-different-trusted-key", "key-id-does-not-match-signature",
            "wrong-algorithm", "unsigned-v2");


    private static AttestationPayloadFields payloadOf(JsonNode root) {
        return new AttestationPayloadFields(
                root.get("schemaVersion").stringValue(),
                root.get("attestationId").stringValue(),
                root.get("producerVersion").stringValue(),
                root.get("keyId").stringValue(),
                root.get("signatureAlgorithm").stringValue(),
                root.get("securityProfileVersion").stringValue(),
                root.get("runtime").stringValue(),
                root.get("sandboxRuntime").stringValue(),
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
        return MAPPER.readTree(document(name));
    }

    /** Located from the module or the root, because a module's working directory is its own. */
    static String document(String name) {
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
