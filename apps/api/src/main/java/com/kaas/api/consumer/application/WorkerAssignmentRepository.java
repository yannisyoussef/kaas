package com.kaas.api.consumer.application;

import java.util.Optional;
import java.util.UUID;

/** Finds delivered work a worker may claim, and records that a worker asked. */
public interface WorkerAssignmentRepository {

    /**
     * The oldest run whose dispatch this consumer recorded as DELIVERED and which is still waiting, locked.
     *
     * <p>Locked with {@code SKIP LOCKED} on the RUN row -- the same row, in the same order, every other writer of
     * a queued run locks first -- so two workers asking at once are handed different runs rather than queueing
     * behind each other, and a run the reaper or a cancellation is holding is simply not offered.
     */
    Optional<DeliveredDispatch> lockNextDelivered(String consumer);

    /** Whether any run {@link #lockNextDelivered} could return exists now. Takes no lock. */
    boolean deliveredWaiting(String consumer);

    /** Upserts the worker's last-seen instant, on the database clock. Operational only: it authorizes nothing. */
    void recordPresence(String workerId);

    /** A delivered dispatch as the control plane stored it. The payload is re-validated before it is used. */
    record DeliveredDispatch(UUID organizationId, UUID messageId, String payload) {}
}
