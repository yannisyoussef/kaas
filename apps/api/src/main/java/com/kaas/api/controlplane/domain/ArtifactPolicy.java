package com.kaas.api.controlplane.domain;

import java.util.List;

public record ArtifactPolicy(List<ArtifactType> types, long maxArtifactBytes, long maxTotalBytes) {
    public ArtifactPolicy {
        types = List.copyOf(types);
    }
}
