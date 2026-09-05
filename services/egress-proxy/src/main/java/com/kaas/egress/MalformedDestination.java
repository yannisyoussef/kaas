package com.kaas.egress;

/**
 * A request target that is not a canonical destination.
 *
 * <p>Separate from a policy denial on purpose. "This is not a destination" and "this destination is not
 * allowed" are different facts, and collapsing them would let a parser bug read as a policy decision in
 * whatever evidence a later reader is looking at.
 */
public class MalformedDestination extends RuntimeException {
    public MalformedDestination(String message) {
        super(message);
    }
}
