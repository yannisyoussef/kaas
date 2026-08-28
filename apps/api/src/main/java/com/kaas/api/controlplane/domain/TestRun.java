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

    public TestRun queued(Instant startedAt, Instant deadlineAt) {
        if (lifecycleState != RunLifecycle.CREATED || !lifecycleState.canTransitionTo(RunLifecycle.QUEUED)) {
            throw new IllegalStateException("Only a CREATED run can be queued.");
        }
        if (startedAt == null || deadlineAt == null || !deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Queue timing is required.");
        }
        return new TestRun(
                runId, projectId, Math.addExact(runVersion, 1), RunLifecycle.QUEUED, cancellationStatus,
                testOutcome, infrastructureOutcome, qualityGateStatus, snapshotDigest, startedAt, deadlineAt,
                createdBy, createdAt, startedAt);
    }
}
