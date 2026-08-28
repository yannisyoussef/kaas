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
            case RUNNING -> EnumSet.of(COLLECTING_RESULTS, PROCESSING_RESULTS, STOPPING);
            case COLLECTING_RESULTS -> EnumSet.of(PROCESSING_RESULTS, STOPPING);
            case PROCESSING_RESULTS -> EnumSet.of(COMPLETED);
            case STOPPING -> EnumSet.of(COMPLETED);
            case COMPLETED -> EnumSet.noneOf(RunLifecycle.class);
        };
    }
}
