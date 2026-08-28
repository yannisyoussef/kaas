package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunSchedulingRepository {
    /**
     * Bounded, deterministically ordered batch of runs awaiting their first scheduling transition. Reading is
     * lock-free: the compare-and-set in {@link #persistSchedule} is what makes concurrent schedulers safe.
     */
    List<SchedulableRun> findSchedulable(int batchSize);

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
