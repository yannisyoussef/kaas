package com.kaas.runner.authority;

/**
 * What the control plane last said about this worker's right to keep executing.
 *
 * <h2>Why this is not a boolean</h2>
 *
 * <p>Three materially different things used to arrive as one "the heartbeat failed": the network was
 * unreachable, the server returned an error, and the control plane authoritatively said this worker no longer
 * owns the assignment. Collapsing them left only two possible designs, and both are wrong. Treat every failure
 * as fatal and one dropped packet kills a healthy run. Treat every failure as survivable — which is what the
 * runner did — and a fenced worker keeps executing until something else happens to notice.
 *
 * <p>So the distinction that matters is not success versus failure. It is <strong>definitive versus
 * transient</strong>: did the platform decide something about this assignment, or did we merely fail to ask?
 */
public enum AuthorityDecision {

    /** The lease was extended. Execution continues, and the budget is reset from the returned window. */
    RENEWED(false),

    /**
     * The run is no longer owned by any worker — it was cancelled, stopped, or reached a terminal state.
     *
     * <p>This is how user cancellation reaches a running workload. The run leaves the owned states, the next
     * renewal is refused with this reason, and execution stops — rather than waiting for the workload to
     * finish and discovering the cancellation at the next phase transition.
     */
    RUN_NOT_OWNED(true),

    /** This attempt or epoch is not the active assignment: it was fenced, superseded, or never acquired. */
    STALE_ASSIGNMENT(true),

    /** The lease had already expired when the renewal arrived. Authority is gone, not merely unrenewed. */
    LEASE_EXPIRED(true),

    /** No assignment exists for this run at all. */
    NO_ACTIVE_ASSIGNMENT(true),

    /**
     * The renewal did not take because the database clock did not advance, and the lease is still valid.
     *
     * <p><strong>Refused but not definitive.</strong> This is the one refusal that is not a statement about
     * authority: its causes are a backwards NTP step, a failover to a standby whose clock trails, or two
     * renewals inside one microsecond. The assignment is untouched and the existing lease still runs. Treating
     * it as fencing would end healthy executions for a reason that has nothing to do with ownership — so it is
     * handled exactly like an unreachable control plane, by trying again inside the remaining budget.
     */
    CLOCK_NOT_ADVANCED(false),

    /**
     * The control plane could not be reached, or answered in a way that decided nothing.
     *
     * <p>Not evidence of anything. It consumes the remaining lease budget and nothing more: if connectivity
     * returns before the budget is gone, execution continues; if it does not, execution is terminated because
     * the lease can no longer be assumed valid — never because the network was blamed for a decision it did
     * not make.
     */
    UNAVAILABLE(false),

    /**
     * The control plane answered something this build does not recognise.
     *
     * <p>Treated as definitive, which is the opposite of how the unknown is usually handled here. The reason
     * is that this vocabulary is closed and shared: a decision this worker cannot interpret means the control
     * plane is newer than the worker, and continuing to execute on an unread answer is exactly the case where
     * failing closed costs a run and failing open costs the boundary.
     */
    UNRECOGNIZED(true);

    private final boolean definitiveLoss;

    AuthorityDecision(boolean definitiveLoss) {
        this.definitiveLoss = definitiveLoss;
    }

    /**
     * Whether the platform has decided this worker's authority has ended.
     *
     * <p>A definitive loss stops execution promptly and does not wait for the lease budget: there is nothing
     * left to wait for, because the answer will not change.
     */
    public boolean definitiveLoss() {
        return definitiveLoss;
    }

    /** Whether this decision extended the lease. Only a renewal refreshes the budget. */
    public boolean renewed() {
        return this == RENEWED;
    }

    /**
     * The decision for a reason string the control plane returned.
     *
     * <p>Never {@code valueOf}. An unrecognised reason maps to {@link #UNRECOGNIZED} rather than throwing,
     * because an exception on the monitor thread would end the monitor — and a stopped monitor is a workload
     * with nothing watching it, which is the failure this whole mechanism exists to prevent.
     */
    public static AuthorityDecision fromReason(String reason) {
        if (reason == null) {
            return UNRECOGNIZED;
        }
        for (AuthorityDecision decision : values()) {
            if (decision != UNAVAILABLE && decision != UNRECOGNIZED && decision.name().equals(reason)) {
                return decision;
            }
        }
        return UNRECOGNIZED;
    }
}
