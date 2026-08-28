package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record Feature(
        UUID featureId,
        UUID projectId,
        String name,
        String logicalPath,
        String createdBy,
        Instant createdAt) {}
