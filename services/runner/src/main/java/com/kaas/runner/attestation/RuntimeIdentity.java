package com.kaas.runner.attestation;

import com.github.dockerjava.api.DockerClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Which runtime this evidence describes, said without describing the host.
 *
 * <h2>Why the subject is operator-configured rather than discovered</h2>
 *
 * <p>The obvious identifiers are all wrong. A hostname, a MAC address, a machine serial, a cloud instance id,
 * or a host path each names the machine to anybody who reads the artifact, and the artifact travels. The
 * subject is instead an opaque label the operator assigns to a runtime and configures on both sides — the
 * producer stamps it, the control plane holds the set it will accept. That is the smallest binding that makes
 * the property true: evidence gathered on host A cannot authorize an execution on host B unless an operator
 * deliberately said both are the same subject.
 *
 * <p>It is emphatically not "some non-empty string". A control plane that merely displayed it would let any
 * signed attestation authorize any runtime the same key ever signed for.
 *
 * <h2>What the generation does, and the limit of it</h2>
 *
 * <p>The generation is derived from the container runtime's own opaque instance identity, hashed with domain
 * separation so nothing host-identifying is published. Two attestations taken against different runtime
 * instances are therefore distinguishable, and the value is inside the signature so it cannot be moved onto
 * evidence from elsewhere.
 *
 * <p><strong>It is not reboot invalidation, and this class will not be documented as though it were.</strong>
 * A daemon's instance identity ordinarily survives a host restart, so an attestation stays verifiable across
 * one. Freshness is bounded by {@code assessedAt} and the configured maximum age, and by nothing else. A
 * truthful bounded limitation is worth more than an invalidation claim that is not implemented.
 */
public record RuntimeIdentity(String runtime, String subject, String generation) {

    /** The runtime family. Not a version: a version is host-descriptive and would date the artifact. */
    public static final String DOCKER = "docker";

    private static final String GENERATION_DOMAIN = "KAAS_RUNTIME_GENERATION_V1";

    public RuntimeIdentity {
        Objects.requireNonNull(runtime, "Evidence names the runtime it was gathered on.");
        Objects.requireNonNull(generation, "Evidence names the runtime instance it was gathered on.");
        if (subject == null || subject.isBlank()) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "A runtime subject is configured by the operator; there is no discoverable default.");
        }
    }

    /**
     * Asks the daemon who it is, and publishes a hash of the answer.
     *
     * <p>{@code Info.getId()} is the daemon's own opaque instance identifier. Deliberately not used: the
     * daemon's name, which is the hostname; its kernel version, operating system, and server version, which
     * describe the host closely enough to help somebody attacking it.
     */
    public static RuntimeIdentity ofDaemon(DockerClient docker, String subject) {
        String instanceId;
        try {
            instanceId = docker.infoCmd().exec().getId();
        } catch (RuntimeException unreachable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The container runtime could not be asked to identify itself.");
        }
        if (instanceId == null || instanceId.isBlank()) {
            // Fail rather than substitute a constant. A generation every host shares is a generation that
            // distinguishes nothing, and it would look exactly like one that does.
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The container runtime reported no identity.");
        }
        return new RuntimeIdentity(DOCKER, subject, generationOf(instanceId));
    }

    /** Domain-separated and length-prefixed, like every other digest in this repository. */
    static String generationOf(String instanceId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, GENERATION_DOMAIN);
            update(sha, instanceId);
            // Truncated: the full digest would be no more secret and no more useful, and 128 bits of an opaque
            // label is already far beyond any chance of two runtimes colliding.
            return "gen:" + HexFormat.of().formatHex(sha.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
