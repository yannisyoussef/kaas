package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record Project(
        UUID projectId,
        String name,
        long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {}
