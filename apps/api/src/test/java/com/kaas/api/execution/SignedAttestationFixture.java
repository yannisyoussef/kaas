package com.kaas.api.execution;

import com.kaas.api.execution.domain.AttestationPayloadFields;
import com.kaas.api.execution.domain.RequiredSecurityControls;
import com.kaas.api.execution.domain.SandboxRuntimeBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds signed v3 attestations for tests, using the repository's published test keys.
 *
 * <h2>Why the tests sign rather than hand-write</h2>
 *
 * <p>The whole point of this slice is that nobody types {@code "NON_ROOT_UID": "PASS"} into a document any
 * more. A test fixture that hand-assembled a document and pasted a signature would be the old workflow wearing
 * test clothes, and it would stop compiling the moment the preimage changed — which is the moment it most
 * needs to keep working.
 *
 * <p>This builds the preimage the same way the verifier does and signs it. That is not circular: the preimage
 * construction is separately pinned against fixed vectors that a third implementation produced
 * ({@code AttestationSigningVectorTest}), so if this fixture and the verifier drifted together, the vector
 * test would go red first.
 *
 * <p>It deliberately does <em>not</em> use the runner's producer. This module's build fails if it depends on
 * the runner at all, which is the boundary that lets the runner hold a Docker client — and a test that reached
 * across it would be a test proving the two sides agree because they are the same code.
 */
public final class SignedAttestationFixture {

    /** Unmistakably test-only, and published with its private half so it can never be a production signer. */
    public static final String KEY_ID = "kaas-test-key-1";

    public static final String SECOND_KEY_ID = "kaas-test-key-2";

    /** The subject these fixtures attest, and the one a test control plane must be configured to accept. */
    public static final String RUNTIME_SUBJECT = "kaas.runtime.test";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SignedAttestationFixture() {}

    /** The pinned-key configuration string for a control plane that should trust the primary test key. */
    public static String trustedKeys(String... keyIds) {
        StringBuilder configured = new StringBuilder();
        for (String keyId : keyIds) {
            if (!configured.isEmpty()) {
                configured.append(',');
            }
            configured.append(keyId).append('=').append(publicKeyOf(keyId));
        }
        return configured.toString();
    }

    /** A fully passing attestation, mandatory controls only — enough for a DENY_ALL execution. */
    public static String mandatoryOnly(String profileVersion, Instant assessedAt) {
        return signed(builder(profileVersion, assessedAt).withoutEgressEvidence());
    }

    /** A fully passing attestation including egress evidence — enough for an ALLOWLIST execution. */
    public static String withEgress(String profileVersion, Instant assessedAt) {
        return signed(builder(profileVersion, assessedAt));
    }

    public static Builder builder(String profileVersion, Instant assessedAt) {
        return new Builder(profileVersion, assessedAt);
    }

    /** Signs whatever the builder describes, truthfully — including failures. */
    public static String signed(Builder builder) {
        return builder.sign(KEY_ID);
    }

    /** Everything a test may vary. Each setter exists because some test needs exactly that mutation. */
    public static final class Builder {
        private final Map<String, String> mandatory = new TreeMap<>();
        private final Map<String, String> egress = new TreeMap<>();
        // The constant, not a literal. A second copy of the schema version in a fixture is a copy that has
        // to be found and edited every time the schema moves, and the symptom of missing it is every
        // semantics test failing with UNSUPPORTED_SCHEMA for a reason unrelated to what it was testing.
        private String schemaVersion = AttestationPayloadFields.SCHEMA_VERSION;
        private String attestationId = "01JTEST00000000000000000001";
        private String producerVersion = "kaas.attestation-producer.v1";
        private String runtime = "docker";
        private String sandboxRuntime;
        private String runtimeSubject = RUNTIME_SUBJECT;
        private String runtimeGeneration = "gen:0123456789abcdef0123456789abcdef";
        private String probeImageDigest = "sha256:" + "1".repeat(64);
        private Optional<String> proxyImageDigest = Optional.of("sha256:" + "2".repeat(64));
        private final String profileVersion;
        private final Instant assessedAt;

        private Builder(String profileVersion, Instant assessedAt) {
            this.profileVersion = profileVersion;
            this.assessedAt = assessedAt;
            // Scoped to this fixture's own profile version, so a fixture built for one runtime cannot
            // accidentally carry the other runtime's control set and look valid. A version this build does
            // not recognise still has to produce a document -- that is exactly what the profile-mismatch
            // tests need one for -- so it is populated with the baseline set and refused on the profile.
            Set<String> required = RequiredSecurityControls.knownProfileVersions().contains(profileVersion)
                    ? RequiredSecurityControls.mandatoryFor(profileVersion)
                    : RequiredSecurityControls.mandatoryFor("kaas.sandbox.v1");
            required.forEach(control -> mandatory.put(control, "PASS"));
            RequiredSecurityControls.EGRESS.forEach(control -> egress.put(control, "PASS"));
        }

        public Builder withoutEgressEvidence() {
            egress.clear();
            // The image goes with the evidence. An artifact naming a proxy image it demonstrated nothing about
            // would be a different statement, and the preimage distinguishes the two.
            proxyImageDigest = Optional.empty();
            return this;
        }

        public Builder withMandatoryControl(String control, String verdict) {
            mandatory.put(control, verdict);
            return this;
        }

        public Builder withoutMandatoryControl(String control) {
            mandatory.remove(control);
            return this;
        }

        public Builder withEgressControl(String control, String verdict) {
            egress.put(control, verdict);
            return this;
        }

        public Builder withRuntimeSubject(String subject) {
            this.runtimeSubject = subject;
            return this;
        }

        public Builder withRuntime(String runtime) {
            this.runtime = runtime;
            return this;
        }

        /** For the one property that needs a self-contradictory document: profile says one, runtime another. */
        public Builder withSandboxRuntime(String sandboxRuntime) {
            this.sandboxRuntime = sandboxRuntime;
            return this;
        }

        public Builder withSchemaVersion(String schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public String sign(String keyId) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("schemaVersion", schemaVersion);
            fields.put("attestationId", attestationId);
            fields.put("producerVersion", producerVersion);
            fields.put("keyId", keyId);
            fields.put("signatureAlgorithm", "ED25519");
            fields.put("securityProfileVersion", profileVersion);
            fields.put("runtime", runtime);
            // Derived from the profile version so the document's two answers agree by default; a test that
            // wants them to disagree sets it explicitly.
            fields.put("sandboxRuntime", sandboxRuntime != null
                    ? sandboxRuntime
                    : SandboxRuntimeBinding.knownProfileVersions().contains(profileVersion)
                            ? SandboxRuntimeBinding.runtimeFor(profileVersion)
                            : "DOCKER");
            fields.put("runtimeSubject", runtimeSubject);
            fields.put("runtimeGeneration", runtimeGeneration);
            fields.put("probeImageDigest", probeImageDigest);
            proxyImageDigest.ifPresent(digest -> fields.put("egressProxyImageDigest", digest));
            fields.put("assessedAt",
                    java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                            assessedAt.truncatedTo(ChronoUnit.SECONDS)));

            byte[] preimage = Preimage.of(fields, mandatory, egress);
            ObjectNode root = MAPPER.createObjectNode();
            fields.forEach(root::put);
            ObjectNode mandatoryNode = root.putObject("mandatoryControls");
            mandatory.forEach(mandatoryNode::put);
            ObjectNode egressNode = root.putObject("egressControls");
            egress.forEach(egressNode::put);
            root.put("payloadDigest", Preimage.digestOf(preimage));
            root.put("signature", Base64.getEncoder().encodeToString(signBytes(preimage, keyId)));
            return root.toString();
        }
    }

    private static byte[] signBytes(byte[] preimage, String keyId) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyOf(keyId))));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(preimage);
            return signature.sign();
        } catch (Exception impossible) {
            throw new IllegalStateException("The published test key is unusable", impossible);
        }
    }

    static String privateKeyOf(String keyId) {
        return keys().get(keyId).get("privateKeyPkcs8").stringValue();
    }

    static String publicKeyOf(String keyId) {
        return keys().get(keyId).get("publicKeySpki").stringValue();
    }

    private static JsonNode keys() {
        return MAPPER.readTree(AttestationSigningVectorTest.document("test-keys.json")).get("keys");
    }

    /** The canonical preimage, built here so the fixture signs exactly what a verifier will reconstruct. */
    private static final class Preimage {
        static byte[] of(
                Map<String, String> fields, Map<String, String> mandatory, Map<String, String> egress) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            emit(out, "KAAS_SANDBOX_SECURITY_ATTESTATION_V4");
            label(out, "SCHEMA_VERSION", fields.get("schemaVersion"));
            label(out, "ATTESTATION_ID", fields.get("attestationId"));
            label(out, "PRODUCER_VERSION", fields.get("producerVersion"));
            label(out, "KEY_ID", fields.get("keyId"));
            label(out, "SIGNATURE_ALGORITHM", fields.get("signatureAlgorithm"));
            label(out, "SECURITY_PROFILE_VERSION", fields.get("securityProfileVersion"));
            label(out, "RUNTIME", fields.get("runtime"));
            label(out, "SANDBOX_RUNTIME", fields.get("sandboxRuntime"));
            label(out, "RUNTIME_SUBJECT", fields.get("runtimeSubject"));
            label(out, "RUNTIME_GENERATION", fields.get("runtimeGeneration"));
            label(out, "PROBE_IMAGE_DIGEST", fields.get("probeImageDigest"));
            label(out, "EGRESS_PROXY_IMAGE_DIGEST",
                    fields.getOrDefault("egressProxyImageDigest", " ABSENT"));
            label(out, "ASSESSED_AT", fields.get("assessedAt"));
            controls(out, "MANDATORY_CONTROL", mandatory);
            controls(out, "EGRESS_CONTROL", egress);
            return out.toByteArray();
        }

        static String digestOf(byte[] preimage) {
            try {
                return "sha256:" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(preimage));
            } catch (Exception impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }

        private static void controls(
                java.io.ByteArrayOutputStream out, String label, Map<String, String> controls) {
            emit(out, label + "_COUNT");
            emit(out, Integer.toString(controls.size()));
            new TreeMap<>(controls).forEach((control, verdict) -> {
                emit(out, label);
                emit(out, control);
                emit(out, verdict);
            });
        }

        private static void label(java.io.ByteArrayOutputStream out, String label, String value) {
            emit(out, label);
            emit(out, value);
        }

        private static void emit(java.io.ByteArrayOutputStream out, String text) {
            byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
            out.writeBytes(utf8);
        }
    }
}
