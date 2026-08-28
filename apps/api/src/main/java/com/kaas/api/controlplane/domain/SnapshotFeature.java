package com.kaas.api.controlplane.domain;

import java.util.UUID;

public record SnapshotFeature(
        UUID featureId,
        UUID revisionId,
        long revisionNumber,
        String logicalPath,
        String sourceDigest) {}
