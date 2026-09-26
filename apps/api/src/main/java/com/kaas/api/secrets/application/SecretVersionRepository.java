package com.kaas.api.secrets.application;

import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.SecretVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable secret version metadata, ciphertext, and revocation. Never a plaintext, never a hash of one. */
public interface SecretVersionRepository {

    /** Whether the reference exists in exactly this organization and project. */
    boolean referenceExists(UUID organizationId, UUID projectId, UUID secretReferenceId);

    /**
     * Takes the row lock on the reference, so two concurrent writes for one reference serialise.
     *
     * @return false when the reference does not exist in this organization and project
     */
    boolean lockReference(UUID organizationId, UUID projectId, UUID secretReferenceId);

    /** The next version number: one more than the highest ever issued, revoked or not. */
    int nextVersionNumber(UUID secretReferenceId);

    /** Writes the metadata and the ciphertext together. */
    SecretVersion insert(
            UUID organizationId,
            UUID projectId,
            UUID secretReferenceId,
            int version,
            SecretTransit.EncryptedValue encrypted,
            String createdBy,
            Instant createdAt);

    List<SecretVersion> list(UUID organizationId, UUID projectId, UUID secretReferenceId);

    Optional<SecretVersion> find(UUID organizationId, UUID projectId, UUID secretReferenceId, int version);

    /**
     * Records the revocation and destroys the ciphertext, in the caller's transaction.
     *
     * @return false when the version was already revoked
     */
    boolean revoke(
            UUID organizationId, UUID projectId, UUID secretReferenceId, int version, String revokedBy, Instant at);
}
