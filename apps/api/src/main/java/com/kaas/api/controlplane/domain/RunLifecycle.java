package com.kaas.api.controlplane.domain;

import java.util.EnumSet;
import java.util.Set;

public enum RunLifecycle {
    CREATED,
    QUEUED,
    CLAIMED,
    PROVISIONING,
    RUNNING,
    COLLECTING_RESULTS,
    PROCESSING_RESULTS,
    STOPPING,
    COMPLETED;

    public boolean canTransitionTo(RunLifecycle target) {
        return transitions().contains(target);
    }

    public boolean terminal() {
        return this == COMPLETED;
    }

    /**
     * Whether a run in this state may have a sandbox that can legitimately produce egress.
     *
     * <p>Three states, and the boundaries on both sides are deliberate.
     *
     * <p>{@code CLAIMED} is included because the egress capability is delivered while the run is still
     * claimed, and a worker whose proxy asks before it has announced {@code RUNNING} is asking about a live
     * assignment it genuinely holds. {@code PROVISIONING} and {@code RUNNING} are the states in which the
     * proxy and then the sandbox actually exist.
     *
     * <p>{@code COLLECTING_RESULTS} and {@code PROCESSING_RESULTS} are excluded: by then the sandbox has been
     * removed and its proxy torn down, so traffic arriving under this run's capability is not this run's
     * execution. {@code STOPPING} is excluded because that is the whole of the fencing property — a cancelled
     * run, a lapsed lease, or an overdue phase all reach it, and each must make an unexpired capability stop
     * working. Terminal and pre-assignment states are excluded because no execution exists to authorize.
     *
     * <p>This lives here rather than as a condition at the call site because it is a statement about the
     * lifecycle, and the call site that got it wrong got it wrong by restating it: an earlier version required
     * exactly {@code CLAIMED}, which denied every request an execution ever made. It was invisible because
     * nothing had yet run an allowlist execution end to end.
     */
    public boolean mayProduceExecutionEgress() {
        return this == CLAIMED || this == PROVISIONING || this == RUNNING;
    }

    private Set<RunLifecycle> transitions() {
        return switch (this) {
            case CREATED -> EnumSet.of(QUEUED, COMPLETED);
            case QUEUED -> EnumSet.of(CLAIMED, COMPLETED);
            case CLAIMED -> EnumSet.of(PROVISIONING, STOPPING);
            case PROVISIONING -> EnumSet.of(RUNNING, STOPPING);
            // RUNNING reaches PROCESSING_RESULTS only through COLLECTING_RESULTS. The shortcut existed while
            // no phase was implemented and nothing could take either path; now that both exist, an edge no code
            // drives is an edge no test covers, and the database refuses it regardless.
            case RUNNING -> EnumSet.of(COLLECTING_RESULTS, STOPPING);
            case COLLECTING_RESULTS -> EnumSet.of(PROCESSING_RESULTS, STOPPING);
            // STOPPING is reachable here too, and only the platform may drive it. A tenant cancelling at this
            // point would discard evidence the execution already produced for no gain, but a worker that dies
            // mid-submission must still be reclaimable — without this edge the run would hold admission
            // capacity until somebody intervened by hand.
            case PROCESSING_RESULTS -> EnumSet.of(COMPLETED, STOPPING);
            case STOPPING -> EnumSet.of(COMPLETED);
            case COMPLETED -> EnumSet.noneOf(RunLifecycle.class);
        };
    }
}
