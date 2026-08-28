package com.kaas.api.controlplane.domain;

/**
 * Where an attempt's assignment stands. This is infrastructure ownership, not test progress: an attempt is
 * claimed when a worker instance holds it under a live lease, and fenced when that ownership has ended.
 */
public enum ExecutionAttemptState {
    /** Created by the scheduler and offered to whoever consumes its dispatch. Nobody owns it. */
    WAITING_FOR_CLAIM,
    /** One worker instance owns it under an assignment epoch and a server-controlled lease. */
    CLAIMED,
    /**
     * The assignment is over and cannot be resumed. A worker still holding the fenced epoch has no authority,
     * which is the whole point: fencing is what makes a partitioned worker harmless without having to reach it.
     */
    FENCED
}
