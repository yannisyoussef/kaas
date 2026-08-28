package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record FeatureRevisionSummary(
        UUID revisionId,
        UUID featureId,
        long revisionNumber,
        String sourceDigest,
        String createdBy,
        Instant createdAt) {}
