package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record FeatureRevision(
        UUID revisionId,
        UUID featureId,
        UUID projectId,
        long revisionNumber,
        String source,
        String sourceDigest,
        String createdBy,
        Instant createdAt) {}
