package com.kaas.api.controlplane.domain;

import java.util.List;
import java.util.UUID;

public record RunSnapshot(
        UUID runId,
        UUID projectId,
        int snapshotVersion,
        List<SnapshotFeature> features,
        SnapshotRevision environment,
        SnapshotRevision runProfile,
        List<ConfigurationVariable> effectiveConfiguration,
        List<SecretBinding> secretBindings,
        RunSelection selection,
        int parallelism,
        ScenarioRetry scenarioRetry,
        int executionTimeoutSeconds,
        ArtifactPolicy artifactPolicy,
        EngineDescriptor engine,
        String snapshotDigest) {
    public RunSnapshot {
        features = List.copyOf(features);
        effectiveConfiguration = List.copyOf(effectiveConfiguration);
        secretBindings = List.copyOf(secretBindings);
    }
}
