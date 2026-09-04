package com.kaas.runner.client;

/**
 * The control plane could not be reached, as distinct from having refused.
 *
 * <p>The distinction is the whole reason this type exists. A refusal is authoritative and final; an
 * unavailability says nothing about whether this assignment may proceed. Conflating them would let a network
 * partition read as permission — or, in the other direction, turn a transient blip into an abandoned run.
 */
public class ControlPlaneUnavailable extends Exception {

    public ControlPlaneUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
