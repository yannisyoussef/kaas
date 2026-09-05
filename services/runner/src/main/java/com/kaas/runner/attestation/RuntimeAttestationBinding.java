package com.kaas.runner.attestation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The identity of the security evidence that describes <em>this</em> runtime.
 *
 * <h2>What it is for</h2>
 *
 * <p>The control plane verifies an attestation and issues a command binding that attestation's payload digest.
 * That is one party's decision. This lets the runner make its own, independently: a command whose
 * {@code assessmentDigest} does not match the evidence describing this runtime is refused here, whatever the
 * control plane concluded.
 *
 * <p>The case it defeats is a command travelling to the wrong place. Authorization on the strength of runtime
 * A's evidence produces a command bound to A's digest; runner B holds B's evidence and refuses it. A stale
 * command — one authorized before this runtime was re-assessed — is refused for the same reason. Neither
 * depends on the control plane getting the routing right.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does <strong>not</strong> verify the signature. The runner is the party that <em>produces</em>
 * attestations and holds the signing key; a signature it checked against its own key would prove nothing about
 * anything. Verification authority belongs to the control plane, and this is not a second, weaker copy of it.
 *
 * <p>It is also not a way for the runner to assert its own security. It cannot make a command acceptable — it
 * can only make one unacceptable. The only direction it moves the answer is towards refusal.
 *
 * <p>The digest is <strong>recomputed from the artifact's fields</strong> rather than read out of its
 * {@code payloadDigest} property. Reading it would mean a locally edited digest silently redefines what this
 * runtime considers its own evidence — the very substitution the recomputation exists to catch.
 */
public final class RuntimeAttestationBinding {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String payloadDigest;

    private final String runtimeSubject;

    private RuntimeAttestationBinding(String payloadDigest, String runtimeSubject) {
        this.payloadDigest = payloadDigest;
        this.runtimeSubject = runtimeSubject;
    }

    /** The evidence identity a command must name to run here. */
    public String payloadDigest() {
        return payloadDigest;
    }

    /** Which runtime this evidence describes, for diagnostics. Never compared against a command. */
    public String runtimeSubject() {
        return runtimeSubject;
    }

    /**
     * Reads the artifact this runtime was deployed with.
     *
     * @throws AttestationProductionFailed if it is missing or unreadable. A runner told to bind to its
     *     evidence and unable to find it must fail rather than fall back to binding to nothing, which would
     *     turn a deployment mistake into a silently disabled control.
     */
    public static RuntimeAttestationBinding fromFile(Path artifact) {
        String document;
        try {
            document = Files.readString(artifact);
        } catch (Exception unreadable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_UNAVAILABLE,
                    "This runtime's attestation could not be read from " + artifact + ".");
        }
        return of(document);
    }

    /** Recomputes the digest from the document's own fields. */
    public static RuntimeAttestationBinding of(String document) {
        try {
            JsonNode root = MAPPER.readTree(document);
            AttestationPayload payload = new AttestationPayload(
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
                    Optional.ofNullable(root.get("egressProxyImageDigest")).map(JsonNode::stringValue),
                    Instant.parse(text(root, "assessedAt")),
                    controlsOf(root, "mandatoryControls"),
                    controlsOf(root, "egressControls"));
            return new RuntimeAttestationBinding(payload.payloadDigest(), payload.runtimeSubject());
        } catch (RuntimeException malformed) {
            throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_INCOMPLETE,
                    "This runtime's attestation could not be read as a v3 document.");
        }
    }

    private static Map<String, String> controlsOf(JsonNode root, String property) {
        JsonNode controls = root.get(property);
        if (controls == null || !controls.isObject()) {
            throw new IllegalArgumentException("Control sets are objects.");
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        controls.propertyNames().forEach(name -> verdicts.put(name, controls.get(name).stringValue()));
        return verdicts;
    }

    private static String text(JsonNode root, String property) {
        JsonNode value = root.get(property);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("A required property is missing or not textual.");
        }
        return value.stringValue();
    }
}
