package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.SchedulableRun;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunTerminationRepository {
    /**
     * Locks a run that early termination can still reach — CREATED or QUEUED, and not already asked to stop.
     * Everything past QUEUED is owned by a worker and is deliberately absent: stopping owned work needs a
     * protocol, and inventing one here would let the control plane claim an authority it does not have.
     *
     * <p>Empty means the run moved on, was already terminated, or never belonged to this organization. The caller
     * decides which by re-reading, so that a run belonging to another tenant is indistinguishable from one that
     * does not exist.
     */
    Optional<TestRun> lockTerminable(UUID organizationId, UUID runId);

    Instant currentDatabaseTime();

    /**
     * Writes one terminal transition and everything that must become true with it: the compare-and-set on the
     * run, its lifecycle event, the withdrawal of any dispatch no relay has taken yet, and the removal of
     * scheduling control state that now describes a run nothing will ever schedule.
     *
     * <p>All of it is one transaction on purpose. A committed terminal run whose dispatch still publishes would
     * send a worker to execute a run that is already over.
     */
    void persistTermination(
            UUID organizationId, TestRun previous, TestRun terminal, UUID lifecycleEventId, String actor);

    /**
     * Bounded batch of QUEUED runs whose queue deadline has passed, skipping any run whose durable backoff is
     * still serving or that an operator has quarantined. Reading is advisory: the compare-and-set in
     * {@link #persistTermination} is what makes concurrent reapers safe.
     */
    List<SchedulableRun> findExpired(int batchSize);
}
