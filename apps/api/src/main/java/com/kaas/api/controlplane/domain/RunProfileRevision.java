package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunProfileRevision(
        UUID revisionId,
        UUID runProfileId,
        UUID projectId,
        long revisionNumber,
        UUID environmentRevisionId,
        RunSelection selection,
        int parallelism,
        ScenarioRetry scenarioRetry,
        int executionTimeoutSeconds,
        ArtifactPolicy artifactPolicy,
        List<ConfigurationVariable> configurationOverrides,
        String contentDigest,
        String createdBy,
        Instant createdAt) {
    public RunProfileRevision {
        configurationOverrides = List.copyOf(configurationOverrides);
    }
}
