package com.kaas.api.controlplane.domain;

import java.util.UUID;

public record SnapshotFeature(
        UUID featureId,
        UUID revisionId,
        long revisionNumber,
        String logicalPath,
        String sourceDigest) {

    /**
     * The digest is always the prefixed form.
     *
     * <p>Enforced here because it was not, and the consequence was invisible: two repositories loaded this
     * same record from the same column, one adding the prefix and one not, so the bundle digest computed from
     * a feature list depended on which of them had produced it. Nothing compared the two until a worker
     * redeemed a bundle and checked it against the command that authorized it -- at which point every such
     * check failed.
     *
     * <p>A constructor that refuses the wrong shape turns that class of defect into a failure at the point of
     * construction rather than a mismatch three components away.
     */
    public SnapshotFeature {
        if (sourceDigest == null || !sourceDigest.startsWith("sha256:")) {
            throw new IllegalArgumentException("A snapshot feature's source digest is a prefixed sha256.");
        }
    }
}
