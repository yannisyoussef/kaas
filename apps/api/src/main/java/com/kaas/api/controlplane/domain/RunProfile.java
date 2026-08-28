package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record RunProfile(
        UUID runProfileId, UUID projectId, String name, String createdBy, Instant createdAt) {}
