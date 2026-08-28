package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EnvironmentRevision(
        UUID revisionId,
        UUID environmentId,
        UUID projectId,
        long revisionNumber,
        List<ConfigurationVariable> variables,
        List<SecretBinding> secretBindings,
        String contentDigest,
        String createdBy,
        Instant createdAt) {
    public EnvironmentRevision {
        variables = List.copyOf(variables);
        secretBindings = List.copyOf(secretBindings);
    }
}
