package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record SecretReference(
        UUID secretReferenceId, UUID projectId, String name, String createdBy, Instant createdAt) {}
