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
