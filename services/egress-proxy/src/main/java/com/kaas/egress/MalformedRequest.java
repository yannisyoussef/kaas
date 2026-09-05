package com.kaas.egress;

/**
 * A request that is not well formed enough to have one unambiguous meaning.
 *
 * <p>Distinct from {@link MalformedDestination}: this is about the HTTP framing, and it is raised in every
 * case where two readers of the same bytes could disagree about what was asked for. Ambiguity is refused
 * rather than resolved, because resolving it means picking one of the two readings and the attacker picks the
 * other.
 */
public class MalformedRequest extends RuntimeException {
    public MalformedRequest(String message) {
        super(message);
    }
}
