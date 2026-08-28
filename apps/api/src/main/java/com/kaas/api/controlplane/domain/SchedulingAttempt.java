package com.kaas.api.controlplane.domain;

import java.util.UUID;

/**
 * One scheduling attempt's durable outcome, carrying everything the database needs to derive the next delay and
 * the quarantine decision itself.
 *
 * <p>The policy values travel with the attempt so the whole record is written in a single statement. Reading the
 * clock or the current failure count first would mean the failure path needs a database round trip before it can
 * record a failure, which is exactly what turns a partial outage into a hot loop.
 */
public record SchedulingAttempt(
        UUID organizationId,
        UUID projectId,
        UUID runId,
        String failureCode,
        int increment,
        boolean permanent,
        boolean preserveExistingDelay,
        int maxFailures,
        double baseDelaySeconds,
        double maxDelaySeconds,
        double jitterMultiplier) {

    public static SchedulingAttempt of(
            SchedulableRun run,
            SchedulingFailure failure,
            String failureCode,
            boolean permanent,
            SchedulingBackoff backoff,
            double jitterMultiplier) {
        return new SchedulingAttempt(
                run.organizationId(),
                run.projectId(),
                run.runId(),
                failureCode,
                failure.counted() ? 1 : 0,
                permanent,
                // A deferral may postpone eligibility but must never advance it: a run that has earned a long
                // transient backoff must not be demoted to the short capacity delay.
                !failure.counted(),
                backoff.maxFailuresBeforeQuarantine(),
                backoff.baseDelay().toMillis() / 1000.0,
                backoff.maxDelay().toMillis() / 1000.0,
                jitterMultiplier);
    }
}
