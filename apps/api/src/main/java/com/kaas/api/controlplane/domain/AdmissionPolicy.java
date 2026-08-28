package com.kaas.api.controlplane.domain;

/**
 * How much concurrent work one organization may hold. This exists because run creation and scheduling became
 * automatic: without a ceiling, one authenticated tenant can turn an unbounded number of requests into an
 * unbounded number of queued runs, durable outbox rows, and broker messages.
 *
 * <p>It is deliberately not a billing plan, a rate limiter, or a quota framework. It is the smallest policy that
 * bounds amplification, and the limits are server configuration that no request or token claim can influence.
 */
public record AdmissionPolicy(int maxActiveRunsPerOrganization, int maxQueuedRunsPerOrganization) {
    private static final int CEILING = 100_000;

    public AdmissionPolicy {
        if (maxActiveRunsPerOrganization < 1 || maxActiveRunsPerOrganization > CEILING) {
            throw new IllegalArgumentException("Active run capacity must be between 1 and " + CEILING + ".");
        }
        if (maxQueuedRunsPerOrganization < 1 || maxQueuedRunsPerOrganization > CEILING) {
            throw new IllegalArgumentException("Queued run capacity must be between 1 and " + CEILING + ".");
        }
        if (maxQueuedRunsPerOrganization > maxActiveRunsPerOrganization) {
            // Queued runs are a subset of active runs, so a larger queue ceiling could never be reached and
            // would quietly misrepresent the policy.
            throw new IllegalArgumentException("Queued capacity cannot exceed active capacity.");
        }
    }

    /** A run occupies capacity until it is complete. Every non-terminal state counts, present and future. */
    public boolean admitsAnotherActiveRun(long activeRuns) {
        return activeRuns < maxActiveRunsPerOrganization;
    }

    public boolean admitsAnotherQueuedRun(long queuedRuns) {
        return queuedRuns < maxQueuedRunsPerOrganization;
    }
}
