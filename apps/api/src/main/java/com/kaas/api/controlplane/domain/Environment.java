package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record Environment(
        UUID environmentId, UUID projectId, String name, String createdBy, Instant createdAt) {}
