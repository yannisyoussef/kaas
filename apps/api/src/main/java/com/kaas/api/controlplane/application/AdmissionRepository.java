package com.kaas.api.controlplane.application;

import java.util.UUID;

/**
 * Counting under a lock is what makes admission safe. Without the lock, concurrent requests each observe the same
 * count and all pass, so an organization at its limit can overshoot by as many requests as arrive together.
 */
public interface AdmissionRepository {

    /**
     * Serializes admission decisions for one organization for the rest of the transaction. It is an advisory
     * lock rather than a counter row: there is no counter to keep consistent, and the same technique already
     * guards idempotent creation.
     */
    void lockOrganization(UUID organizationId);

    /** Runs occupying capacity: CREATED or QUEUED. */
    long countActiveRuns(UUID organizationId);

    long countQueuedRuns(UUID organizationId);
}
