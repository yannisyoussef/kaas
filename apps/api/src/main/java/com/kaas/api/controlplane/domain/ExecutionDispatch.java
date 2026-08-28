package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record ExecutionDispatch(
        String schemaVersion,
        UUID messageId,
        String messageType,
        UUID dispatchId,
        Instant occurredAt,
        String producer,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        long runVersion,
        UUID attemptId,
        int attemptNumber,
        UUID runSnapshotId,
        String runSnapshotDigest,
        Instant queueDeadlineAt,
        String payloadDigest) {
    public ExecutionDispatch {
        // This record is also built by deserializing an untrusted message, so the identity a digest is computed
        // over must be present before anything hashes or persists it.
        if (schemaVersion == null || messageId == null || messageType == null || dispatchId == null
                || occurredAt == null || producer == null || organizationId == null || projectId == null
                || runId == null || attemptId == null || runSnapshotId == null || runSnapshotDigest == null
                || queueDeadlineAt == null || payloadDigest == null) {
            throw new IllegalArgumentException("Execution dispatch identity is incomplete.");
        }
    }
}
