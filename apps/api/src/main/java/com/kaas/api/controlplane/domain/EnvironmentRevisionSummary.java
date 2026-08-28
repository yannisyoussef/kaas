package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentRevisionSummary(
        UUID revisionId,
        UUID environmentId,
        long revisionNumber,
        String contentDigest,
        String createdBy,
        Instant createdAt) {}
