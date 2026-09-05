package com.kaas.api.execution.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The signed fields of a v3 attestation, and this side's reconstruction of the bytes that were signed.
 *
 * <h2>Why this is written again rather than shared</h2>
 *
 * <p>The producer lives in {@code services/runner}, which holds container-runtime access, and this module's
 * build fails if it ever depends on that one. So a shared signing library is not merely undesirable here, it
 * is forbidden by the boundary that lets the runner touch a daemon at all.
 *
 * <p>It is also the weaker arrangement. Two implementations of
 * {@code packages/api-contracts/sandbox-security-attestation-signing.md} can be checked against each other and
 * against fixed vectors; one implementation agreeing with itself proves nothing. If the producer and this class
 * ever disagree about a single byte, every attestation stops verifying — which is the loud, fail-closed
 * direction, and is the direction a shared method would have hidden.
 *
 * <p><strong>The signature is over these reconstructed bytes, never over the document as received.</strong>
 * Re-hashing the received bytes would authenticate a serialization, so reformatting the artifact would break
 * it and a field this class does not know about would sail through unnoticed. Reconstructing means the
 * signature covers exactly the fields named below and nothing else — and a document carrying an extra field is
 * refused by the parser rather than silently signed-over.
 */
public record AttestationPayloadFields(
        String schemaVersion,
        String attestationId,
        String producerVersion,
        String keyId,
        String signatureAlgorithm,
        String securityProfileVersion,
        String runtime,
        String runtimeSubject,
        String runtimeGeneration,
        String probeImageDigest,
        Optional<String> egressProxyImageDigest,
        Instant assessedAt,
        Map<String, String> mandatoryControls,
        Map<String, String> egressControls) {

    /** The only schema this control plane accepts. A v2 document is refused, never downgraded. */
    public static final String SCHEMA_VERSION = "kaas.sandbox-security-attestation.v3";

    /** The only algorithm identifier. Compared for equality; never dispatched on. */
    public static final String SIGNATURE_ALGORITHM = "ED25519";

    private static final String DOMAIN = "KAAS_SANDBOX_SECURITY_ATTESTATION_V3";

    private static final String ABSENT = " ABSENT";

    public AttestationPayloadFields {
        mandatoryControls = Map.copyOf(mandatoryControls);
        egressControls = Map.copyOf(egressControls);
        assessedAt = assessedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The canonical preimage, per the contract.
     *
     * <p>Every element is length-prefixed with a four-byte big-endian count, including the domain separator.
     * Without that, two adjacent fields could be split differently and produce identical bytes, and the choice
     * of where one ends would belong to whoever supplied them.
     */
    public byte[] canonicalBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        emit(bytes, DOMAIN);

        emit(bytes, "SCHEMA_VERSION");
        emit(bytes, schemaVersion);
        emit(bytes, "ATTESTATION_ID");
        emit(bytes, attestationId);
        emit(bytes, "PRODUCER_VERSION");
        emit(bytes, producerVersion);
        emit(bytes, "KEY_ID");
        emit(bytes, keyId);
        emit(bytes, "SIGNATURE_ALGORITHM");
        emit(bytes, signatureAlgorithm);
        emit(bytes, "SECURITY_PROFILE_VERSION");
        emit(bytes, securityProfileVersion);
        emit(bytes, "RUNTIME");
        emit(bytes, runtime);
        emit(bytes, "RUNTIME_SUBJECT");
        emit(bytes, runtimeSubject);
        emit(bytes, "RUNTIME_GENERATION");
        emit(bytes, runtimeGeneration);
        emit(bytes, "PROBE_IMAGE_DIGEST");
        emit(bytes, probeImageDigest);
        emit(bytes, "EGRESS_PROXY_IMAGE_DIGEST");
        // Absent is its own value, not the empty string. A document making no egress claim and one claiming a
        // blank proxy image must not sign identically.
        emit(bytes, egressProxyImageDigest.orElse(ABSENT));
        emit(bytes, "ASSESSED_AT");
        emit(bytes, assessedAtText());

        emitControls(bytes, "MANDATORY_CONTROL", mandatoryControls);
        emitControls(bytes, "EGRESS_CONTROL", egressControls);
        return bytes.toByteArray();
    }

    /** SHA-256 of those bytes. Recomputed on this side; the document's own value is only ever compared to it. */
    public String payloadDigest() {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** ISO-8601 instant, UTC, whole seconds — the one representation the contract permits. */
    public String assessedAtText() {
        return DateTimeFormatter.ISO_INSTANT.format(assessedAt);
    }

    /**
     * The count, then each control ordered by name.
     *
     * <p>{@link TreeMap} orders by {@code String.compareTo}, which is the unsigned UTF-16 code-unit order the
     * contract names. The count precedes the entries so that adding or removing one changes the preimage even
     * if the remaining entries are unchanged.
     */
    private static void emitControls(ByteArrayOutputStream bytes, String label, Map<String, String> controls) {
        emit(bytes, label + "_COUNT");
        emit(bytes, Integer.toString(controls.size()));
        for (Map.Entry<String, String> control : new TreeMap<>(controls).entrySet()) {
            emit(bytes, label);
            emit(bytes, control.getKey());
            emit(bytes, control.getValue());
        }
    }

    private static void emit(ByteArrayOutputStream bytes, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
        bytes.writeBytes(utf8);
    }
}
