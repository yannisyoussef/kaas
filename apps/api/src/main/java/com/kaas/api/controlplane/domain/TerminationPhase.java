package com.kaas.api.controlplane.domain;

/**
 * Where in a run's life it ended. The vocabulary is the one the runner result contract already defines for
 * structured errors, so a control-plane termination and a runner-reported failure describe phases in the same
 * words rather than in two enums that would immediately need reconciling.
 *
 * <p>Only the phases that are actually reachable are modelled. The later ones arrive with the transitions that
 * can reach them.
 */
public enum TerminationPhase {
    QUEUE,
    CLAIM,
    CANCELLATION
}
