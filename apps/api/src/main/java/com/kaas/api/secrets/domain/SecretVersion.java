package com.kaas.api.secrets.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable version of a SecretReference, as metadata.
 *
 * <p>There is no value here and no ciphertext. This is the only representation of a version that leaves the
 * persistence layer for anything other than resolution, and so the only one an API can ever serialise.
 */
public record SecretVersion(
        UUID secretReferenceId,
        int version,
        String createdBy,
        Instant createdAt,
        boolean revoked,
        Instant revokedAt) {}
