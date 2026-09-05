package com.kaas.runner.authority;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Whether a workload may still be running, and why not.
 *
 * <p>Read by the execution thread and written by the monitor thread. It is deliberately tiny: one terminal
 * reason, set once, plus a deadline. A richer shared object between two threads would need a lock, and a lock
 * held across a control-plane request is a way to stall the thread that is supposed to be stopping things.
 */
public interface ExecutionAuthority {

    /** Why execution must stop, or null while it may continue. Set once and never changed after. */
    AuthorityDecision lostReason();

    /** Whether execution must stop now. */
    default boolean lost() {
        return lostReason() != null;
    }

    /**
     * An authority that is never lost, for the trusted suites that run no assignment.
     *
     * <p>The security gate and the contract suites launch sandboxes with no run, no lease and no control plane
     * to renew against. They get this rather than a second launcher entry point, so that every sandbox this
     * repository starts — probes included — goes through the one interruption path. A separate uninterruptible
     * path would be a production mechanism whose only exercise is the tests that do not use it.
     */
    static ExecutionAuthority retained() {
        return new ExecutionAuthority() {
            @Override
            public AuthorityDecision lostReason() {
                return null;
            }

            @Override
            public Duration remainingBudget() {
                return ChronoUnit.FOREVER.getDuration();
            }
        };
    }

    /**
     * How long execution may still continue without a successful renewal.
     *
     * <p>Zero once authority is gone. Measured on the monitor's monotonic clock, never against a wall clock.
     */
    Duration remainingBudget();
}
