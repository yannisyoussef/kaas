package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RunSchedulingRepository {
    Optional<TestRun> lockCreated(UUID organizationId, UUID runId, long expectedRunVersion);

    Instant currentDatabaseTime();

    void persistSchedule(
            UUID organizationId,
            TestRun previous,
            TestRun queued,
            ExecutionAttempt attempt,
            ExecutionDispatch dispatch,
            UUID lifecycleEventId,
            UUID outboxId,
            String dispatchPayload);
}
