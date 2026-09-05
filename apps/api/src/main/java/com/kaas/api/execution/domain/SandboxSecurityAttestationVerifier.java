package com.kaas.api.execution.domain;

import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a document nobody has authenticated into a {@link VerifiedSandboxSecurityAttestation}, or refuses.
 *
 * <h2>The order is the design</h2>
 *
 * <p>Parse, schema, algorithm, resolve the pinned key, reconstruct the payload, recompute the digest, verify
 * the signature — and only then does anything read a verdict. Nothing about the document is trusted before the
 * signature over it verifies, which is why the semantic checks live on the verified type and cannot be reached
 * from here.
 *
 * <p>The signature is verified over the payload <em>reconstructed from the parsed fields</em>, never over the
 * bytes as received. Verifying received bytes would authenticate a serialization: reformatting the artifact
 * would break it, and a field this verifier does not know about would be signed over without ever being
 * understood. Reconstruction means the signature covers exactly the fields this code knows, and the parser
 * refuses a document carrying anything else.
 *
 * <h2>Why it says so little when it refuses</h2>
 *
 * <p>Before authenticity is established the document is operator-supplied configuration that an attacker may
 * have influenced. Every refusal below returns a category and nothing drawn from the document, because a
 * diagnostic that quoted the contents would be a diagnostic that repeated attacker-chosen text.
 */
public final class SandboxSecurityAttestationVerifier {

    /**
     * A private mapper, so a change to a shared one cannot reach the parser whose input decides whether
     * execution is permitted.
     *
     * <p><strong>The strictness that matters is not a mapper flag.</strong> This reads a
     * {@link JsonNode} rather than binding to a type, and {@code FAIL_ON_UNKNOWN_PROPERTIES} applies only to
     * bean binding — it does nothing here. The real enforcement is the explicit property allowlist below, and
     * it is the only enforcement. That lesson is recorded in this repository because a comment once claimed a
     * protection that was not operating, in exactly this class of parser.
     */
    private static final ObjectMapper STRICT = JsonMapper.builder().build();

    /** Exactly these, no more and no fewer. A property not on this list is a refusal. */
    private static final Set<String> SIGNED_PROPERTIES = Set.of(
            "schemaVersion",
            "attestationId",
            "producerVersion",
            "keyId",
            "signatureAlgorithm",
            "securityProfileVersion",
            "runtime",
            "runtimeSubject",
            "runtimeGeneration",
            "probeImageDigest",
            "egressProxyImageDigest",
            "assessedAt",
            "mandatoryControls",
            "egressControls");

    private static final Set<String> ENVELOPE_PROPERTIES = Set.of("payloadDigest", "signature");

    /** Ed25519 signatures are exactly this long. A total shape check before any crypto runs. */
    private static final int SIGNATURE_LENGTH = 64;

    private final PinnedVerificationKeys keys;

    public SandboxSecurityAttestationVerifier(PinnedVerificationKeys keys) {
        this.keys = keys;
    }

    /** What a verification produced: an outcome always, and an attestation only when the outcome is VALID. */
    public record Result(
            AttestationVerification outcome, Optional<VerifiedSandboxSecurityAttestation> attestation) {

        static Result refused(AttestationVerification outcome) {
            return new Result(outcome, Optional.empty());
        }
    }

    /**
     * Verifies authenticity only. Freshness, subject, profile and controls are the verified type's to answer.
     */
    public Result verify(String document) {
        if (!keys.available()) {
            // Nothing could have been checked, so nothing is accepted. Distinguished from a bad signature
            // because the operator action is entirely different.
            return Result.refused(AttestationVerification.TRUST_ROOT_UNAVAILABLE);
        }
        if (document == null || document.isBlank()) {
            return Result.refused(AttestationVerification.ABSENT);
        }

        JsonNode root;
        try {
            root = STRICT.readTree(document);
        } catch (RuntimeException unparseable) {
            return Result.refused(AttestationVerification.MALFORMED);
        }
        if (!root.isObject()) {
            return Result.refused(AttestationVerification.MALFORMED);
        }
        // THE SCHEMA FIRST, before the property allowlist, because the schema is what DEFINES that allowlist.
        // Checking a document's properties against v3's set before knowing it claims to be v3 is checking it
        // against the wrong list — and it reports an operator's old v2 artifact as "malformed", which sends
        // them looking for a typo instead of for a producer. Both are refusals either way; only one of them
        // tells the truth about what is wrong.
        JsonNode schema = root.get("schemaVersion");
        if (schema == null || !schema.isString()) {
            return Result.refused(AttestationVerification.MALFORMED);
        }
        if (!AttestationPayloadFields.SCHEMA_VERSION.equals(schema.stringValue())) {
            return Result.refused(AttestationVerification.UNSUPPORTED_SCHEMA);
        }
        for (String property : root.propertyNames()) {
            if (!SIGNED_PROPERTIES.contains(property) && !ENVELOPE_PROPERTIES.contains(property)) {
                // An undefined property might be a misspelled control set, a second signature, or a field a
                // newer producer emits. All three are documents this build does not understand, and a document
                // this build does not understand must not be one it authorizes.
                return Result.refused(AttestationVerification.MALFORMED);
            }
        }

        AttestationPayloadFields payload;
        String claimedDigest;
        byte[] signature;
        try {
            if (!AttestationPayloadFields.SIGNATURE_ALGORITHM.equals(text(root, "signatureAlgorithm"))) {
                // Compared, never dispatched on. A verifier that looked up an algorithm named by the document
                // would let the document choose how it is checked.
                return Result.refused(AttestationVerification.MALFORMED);
            }
            payload = payloadOf(root);
            claimedDigest = text(root, "payloadDigest");
            // Strict standard Base64. MIME decoding would accept line breaks and stray characters, which means
            // several spellings of one signature — and two documents that are the same to one reader and
            // different to another.
            signature = Base64.getDecoder().decode(text(root, "signature"));
        } catch (RuntimeException malformed) {
            return Result.refused(AttestationVerification.MALFORMED);
        }
        if (signature.length != SIGNATURE_LENGTH) {
            return Result.refused(AttestationVerification.MALFORMED);
        }

        // Exactly one key, selected by the id the document names. Never a loop over the trusted set: accepting
        // "some key we trust signed this" would make a key an operator removed keep working for any document
        // that stopped naming it.
        Optional<PublicKey> key = keys.keyFor(payload.keyId());
        if (key.isEmpty()) {
            return Result.refused(AttestationVerification.UNKNOWN_KEY);
        }

        byte[] canonical = payload.canonicalBytes();
        if (!java.security.MessageDigest.isEqual(
                payload.payloadDigest().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                claimedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            // Recomputed from the reconstructed payload and compared, never taken on trust. Constant time
            // because this compares a caller-controlled value against one derived here.
            return Result.refused(AttestationVerification.DIGEST_MISMATCH);
        }
        if (!signatureVerifies(canonical, signature, key.orElseThrow())) {
            return Result.refused(AttestationVerification.INVALID_SIGNATURE);
        }
        return new Result(
                AttestationVerification.VALID,
                Optional.of(new VerifiedSandboxSecurityAttestation(payload, claimedDigest)));
    }

    private static boolean signatureVerifies(byte[] canonical, byte[] signature, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical);
            return verifier.verify(signature);
        } catch (Exception refused) {
            // A malformed signature, a key the provider rejects, anything at all: not verified.
            return false;
        }
    }

    private static AttestationPayloadFields payloadOf(JsonNode root) {
        return new AttestationPayloadFields(
                text(root, "schemaVersion"),
                text(root, "attestationId"),
                text(root, "producerVersion"),
                text(root, "keyId"),
                text(root, "signatureAlgorithm"),
                text(root, "securityProfileVersion"),
                text(root, "runtime"),
                text(root, "runtimeSubject"),
                text(root, "runtimeGeneration"),
                text(root, "probeImageDigest"),
                // Absent is legal and means no egress claim. Present-but-not-textual is an error rather than
                // an absence: a document that tried to name a proxy image and failed must not read as one that
                // named none, because those produce different preimages and only one of them was signed.
                optionalText(root, "egressProxyImageDigest"),
                Instant.parse(text(root, "assessedAt")),
                controlsOf(root, "mandatoryControls"),
                controlsOf(root, "egressControls"));
    }

    private static Map<String, String> controlsOf(JsonNode root, String property) {
        JsonNode controls = root.get(property);
        if (controls == null || !controls.isObject()) {
            throw new IllegalArgumentException("Control sets are objects.");
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String control : controls.propertyNames()) {
            JsonNode verdict = controls.get(control);
            if (!verdict.isString()) {
                throw new IllegalArgumentException("Each control carries a textual verdict.");
            }
            verdicts.put(control, verdict.stringValue());
        }
        return verdicts;
    }

    private static String text(JsonNode root, String property) {
        JsonNode value = root.get(property);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("A required property is missing or not textual.");
        }
        return value.stringValue();
    }

    private static Optional<String> optionalText(JsonNode root, String property) {
        JsonNode value = root.get(property);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("An optional property is textual when present.");
        }
        return Optional.of(value.stringValue());
    }
}
