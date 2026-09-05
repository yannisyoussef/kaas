package com.kaas.runner.attestation;

import java.util.Base64;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A payload, its digest, and a signature over it — the artifact that travels to the control plane.
 *
 * <h2>What is inside the signature and what is beside it</h2>
 *
 * <p>The payload is signed. {@code payloadDigest} and {@code signature} are not: a payload cannot contain its
 * own digest without circularity, and cannot contain its own signature at all. Everything else — including
 * {@code keyId} and {@code signatureAlgorithm} — is inside, so the signer commits to which key and which
 * algorithm it used rather than leaving that to be asserted alongside.
 *
 * <p>The JSON below is the transport format and deliberately does <em>not</em> define security semantics. The
 * signature is over the canonical preimage, so a verifier reconstructs that preimage from the parsed fields
 * rather than re-hashing the bytes it received. Reformat this document, reorder it, or re-serialize it with a
 * different library and it still verifies; change one character of one field and it does not.
 */
public record SignedAttestation(AttestationPayload payload, String payloadDigest, String signature) {

    /** Standard Base64 with padding, as the contract specifies. Not base64url, not unpadded, not MIME. */
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    public static SignedAttestation of(AttestationPayload payload, AttestationSigner signer) {
        byte[] canonical = payload.canonicalBytes();
        // Digest and signature over THE SAME BYTES. Computing the digest from a serialized document and the
        // signature from the preimage would produce an artifact whose two integrity values describe different
        // things, and only one of them would be the one anybody checked.
        return new SignedAttestation(
                payload, payload.payloadDigest(), BASE64.encodeToString(signer.sign(canonical)));
    }

    /**
     * The document, with properties in a fixed order.
     *
     * <p>Insertion-ordered so the artifact is byte-identical for identical evidence, which is what makes it
     * diffable and what lets a test assert reproducibility. That determinism is a convenience; it is not what
     * the signature rests on.
     */
    public String toJson() {
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", payload.schemaVersion());
        root.put("attestationId", payload.attestationId());
        root.put("producerVersion", payload.producerVersion());
        root.put("keyId", payload.keyId());
        root.put("signatureAlgorithm", payload.signatureAlgorithm());
        root.put("securityProfileVersion", payload.securityProfileVersion());
        root.put("runtime", payload.runtime());
        root.put("sandboxRuntime", payload.sandboxRuntime());
        root.put("runtimeSubject", payload.runtimeSubject());
        root.put("runtimeGeneration", payload.runtimeGeneration());
        root.put("probeImageDigest", payload.probeImageDigest());
        // Omitted entirely when there is no egress evidence, matching the contract: the preimage distinguishes
        // absent from empty, and so must the document, or a round trip would not reproduce the preimage.
        payload.egressProxyImageDigest().ifPresent(digest -> root.put("egressProxyImageDigest", digest));
        root.put("assessedAt", payload.assessedAtText());
        ObjectNode mandatory = root.putObject("mandatoryControls");
        new java.util.TreeMap<>(payload.mandatoryControls()).forEach(mandatory::put);
        ObjectNode egress = root.putObject("egressControls");
        new java.util.TreeMap<>(payload.egressControls()).forEach(egress::put);
        root.put("payloadDigest", payloadDigest);
        root.put("signature", signature);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }
}
