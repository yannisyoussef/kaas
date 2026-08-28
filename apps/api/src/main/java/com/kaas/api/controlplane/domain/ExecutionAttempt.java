package com.kaas.api.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public record ExecutionAttempt(
        UUID attemptId, UUID runId, int attemptNumber, ExecutionAttemptState state, Instant createdAt) {
    public ExecutionAttempt {
        if (attemptId == null || runId == null || attemptNumber != 1
                || state != ExecutionAttemptState.WAITING_FOR_CLAIM || createdAt == null) {
            throw new IllegalArgumentException("The initial attempt must be unassigned and waiting for claim.");
        }
    }
}
