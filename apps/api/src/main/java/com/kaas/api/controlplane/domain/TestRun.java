package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record TestRun(
        UUID runId,
        UUID projectId,
        long runVersion,
        RunLifecycle lifecycleState,
        CancellationStatus cancellationStatus,
        TestOutcome testOutcome,
        InfrastructureOutcome infrastructureOutcome,
        QualityGateStatus qualityGateStatus,
        String snapshotDigest,
        Instant queueStartedAt,
        Instant queueDeadlineAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static TestRun created(
            UUID runId, UUID projectId, String snapshotDigest, String createdBy, Instant now) {
        return new TestRun(
                runId,
                projectId,
                1,
                RunLifecycle.CREATED,
                CancellationStatus.NOT_REQUESTED,
                null,
                null,
                QualityGateStatus.NOT_EVALUATED,
                snapshotDigest,
                null,
                null,
                createdBy,
                now,
                now);
    }
}
