package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerLeaseRepository {
    /**
     * Locks a run that owns an assignment, together with that assignment. Run first, attempt second — the same
     * order every other writer that touches both uses, because a lock order that varies by caller is how two
     * correct transactions deadlock.
     *
     * <p>Looked up by run alone, without an organization. A worker is a platform service and holds no tenant
     * identity to scope by; the organization comes back from the row, and every write is scoped by it.
     */
    Optional<OwnedRun> lockOwnedByRun(UUID runId);

    Instant currentDatabaseTime();

    /** Renews a live lease and nothing else: no lifecycle change, no version bump, no lifecycle event. */
    boolean renewLease(UUID organizationId, ExecutionAttempt attempt);

    /**
     * Fences the assignment and moves the run to STOPPING in one transaction. Splitting them would leave a
     * window where the run is stopping while a worker still believes it owns the attempt.
     */
    void persistStop(
            UUID organizationId,
            TestRun previous,
            TestRun stopping,
            ExecutionAttempt fenced,
            UUID lifecycleEventId,
            String actor);

    /** Settles a stopping run. The outcome was fixed when it entered STOPPING and is not recomputed here. */
    void persistSettlement(UUID organizationId, TestRun previous, TestRun settled, UUID lifecycleEventId);

    /** Assignments whose lease expired long enough ago that the recovery window has also passed. */
    List<UUID> findExpiredLeases(int batchSize);

    /** Runs sitting in STOPPING with nothing left to wait for. */
    List<UUID> findStopping(int batchSize);

    /** A run, the organization that owns it, and the attempt holding its assignment. */
    record OwnedRun(UUID organizationId, TestRun run, ExecutionAttempt attempt) {}
}
