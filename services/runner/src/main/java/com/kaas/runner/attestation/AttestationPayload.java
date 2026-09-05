package com.kaas.runner.attestation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The security evidence that gets signed, and the exact bytes that get signed.
 *
 * <h2>Why the bytes are built here rather than serialized</h2>
 *
 * <p>Jackson's field ordering, whitespace, and serializer configuration must never define security semantics.
 * If the signature were over a JSON serialization, then upgrading a library, adding a field, or changing a
 * pretty-printer would silently change what a signature means — and the failure would be a verifier that
 * rejects genuine evidence, followed by somebody relaxing the verifier.
 *
 * <p>So the preimage is constructed explicitly, length-prefixed, label-separated, and ordered, exactly as
 * {@code packages/api-contracts/sandbox-security-attestation-signing.md} specifies. That document is normative;
 * this class implements it, and the control plane implements it again, independently. They are checked against
 * each other and against committed vectors rather than against a shared method.
 *
 * <h2>Length prefixes are not decoration</h2>
 *
 * <p>Without them, concatenating {@code ("AB", "C")} and {@code ("A", "BC")} produces identical bytes, and an
 * attacker gets to choose where one field ends and the next begins. Every string emitted below carries a
 * four-byte big-endian length first, including the domain separator.
 */
public record AttestationPayload(
        String schemaVersion,
        String attestationId,
        String producerVersion,
        String keyId,
        String signatureAlgorithm,
        String securityProfileVersion,
        String runtime,
        /**
         * Which sandbox runtime served the probes: the name of the platform's runtime constant, never a
         * daemon string and never anything a request supplied.
         *
         * <p>Signed alongside {@code securityProfileVersion}, which already implies it. The redundancy is the
         * point: two independent statements about the same boundary, both covered by one signature, so a
         * document that says {@code kaas.sandbox.gvisor.v1} and {@code DOCKER} is self-contradictory and is
         * refused rather than resolved in favour of whichever field a reader happened to consult.
         */
        String sandboxRuntime,
        String runtimeSubject,
        String runtimeGeneration,
        String probeImageDigest,
        /**
         * The proxy image this evidence describes, or empty when no egress evidence was gathered.
         *
         * <p>Empty and "present but blank" are different statements and produce different preimages. A control
         * reading {@code EGRESS_PROXY_IMAGE_PINNED=PASS} says an image was pinned; it does not say WHICH, and
         * an attestation that cannot name the image it demonstrated is evidence about nothing in particular.
         */
        Optional<String> egressProxyImageDigest,
        Instant assessedAt,
        Map<String, String> mandatoryControls,
        Map<String, String> egressControls) {

    /** The schema this payload shape is. A later shape is a different version and a different preimage. */
    public static final String SCHEMA_VERSION = "kaas.sandbox-security-attestation.v4";

    /** The one algorithm. Not a field a document gets to choose; a constant a verifier requires. */
    public static final String SIGNATURE_ALGORITHM = "ED25519";

    /**
     * The domain separator, first in every preimage.
     *
     * <p>So that this signing key cannot accidentally authenticate a different KaaS document type that happens
     * to have the same byte shape, and so a v3 preimage can never be mistaken for a v4 one even if every other
     * field matched. The separator moved with the schema for exactly that reason: v4 added a signed field, and
     * a v3 document whose bytes happened to line up must not verify against a v4 reader.
     */
    static final String DOMAIN = "KAAS_SANDBOX_SECURITY_ATTESTATION_V4";

    /** Emitted for an absent optional field, so absent and empty-string are not the same preimage. */
    static final String ABSENT = " ABSENT";

    public AttestationPayload {
        Objects.requireNonNull(schemaVersion);
        Objects.requireNonNull(attestationId);
        Objects.requireNonNull(producerVersion);
        Objects.requireNonNull(keyId);
        Objects.requireNonNull(signatureAlgorithm);
        Objects.requireNonNull(securityProfileVersion);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(runtimeSubject);
        Objects.requireNonNull(runtimeGeneration);
        Objects.requireNonNull(probeImageDigest);
        Objects.requireNonNull(egressProxyImageDigest);
        Objects.requireNonNull(assessedAt);
        mandatoryControls = Map.copyOf(mandatoryControls);
        egressControls = Map.copyOf(egressControls);
        // Truncated here rather than at every use, so the value carried by this record is already the one the
        // contract specifies. A payload holding nanoseconds would digest differently from the document it
        // serializes to, and the mismatch would surface as an invalid signature somewhere far from the cause.
        assessedAt = assessedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /** The exact bytes that are digested and signed. */
    public byte[] canonicalBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        value(out, DOMAIN);

        field(out, "SCHEMA_VERSION", schemaVersion);
        field(out, "ATTESTATION_ID", attestationId);
        field(out, "PRODUCER_VERSION", producerVersion);
        field(out, "KEY_ID", keyId);
        field(out, "SIGNATURE_ALGORITHM", signatureAlgorithm);
        field(out, "SECURITY_PROFILE_VERSION", securityProfileVersion);
        field(out, "RUNTIME", runtime);
        field(out, "SANDBOX_RUNTIME", sandboxRuntime);
        field(out, "RUNTIME_SUBJECT", runtimeSubject);
        field(out, "RUNTIME_GENERATION", runtimeGeneration);
        field(out, "PROBE_IMAGE_DIGEST", probeImageDigest);
        field(out, "EGRESS_PROXY_IMAGE_DIGEST", egressProxyImageDigest.orElse(ABSENT));
        field(out, "ASSESSED_AT", assessedAtText());

        controls(out, "MANDATORY_CONTROL", mandatoryControls);
        controls(out, "EGRESS_CONTROL", egressControls);
        return out.toByteArray();
    }

    /** The digest of those bytes. Derived, never read back from a document. */
    public String payloadDigest() {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** ISO-8601, UTC, whole seconds. One representation, so the preimage cannot depend on clock precision. */
    public String assessedAtText() {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(assessedAt);
    }

    /**
     * A count, then every entry ordered by control name.
     *
     * <p>The count comes first so a truncated list cannot be extended or shortened without changing the
     * preimage. The ordering is by name so the preimage describes the content rather than the order a parser
     * happened to produce — {@link TreeMap} uses {@code String.compareTo}, which is the unsigned UTF-16
     * code-unit order the contract names.
     */
    private static void controls(ByteArrayOutputStream out, String label, Map<String, String> controls) {
        label(out, label + "_COUNT");
        value(out, Integer.toString(controls.size()));
        new TreeMap<>(controls).forEach((control, verdict) -> {
            label(out, label);
            value(out, control);
            value(out, verdict);
        });
    }

    private static void field(ByteArrayOutputStream out, String label, String value) {
        label(out, label);
        value(out, value);
    }

    private static void label(ByteArrayOutputStream out, String label) {
        lengthPrefixed(out, label);
    }

    private static void value(ByteArrayOutputStream out, String value) {
        lengthPrefixed(out, value);
    }

    private static void lengthPrefixed(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
