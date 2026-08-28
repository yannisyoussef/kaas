package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record RunProfileRevisionSummary(
        UUID revisionId,
        UUID runProfileId,
        long revisionNumber,
        UUID environmentRevisionId,
        String contentDigest,
        String createdBy,
        Instant createdAt) {}
