package com.kaas.api.controlplane.domain;

import java.util.UUID;

public record SnapshotRevision(UUID resourceId, UUID revisionId, long revisionNumber, String contentDigest) {}
