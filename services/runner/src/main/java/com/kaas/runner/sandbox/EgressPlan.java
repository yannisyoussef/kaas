package com.kaas.runner.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * Everything one ALLOWLIST execution needs to reach its proxy: a credential and where to aim.
 *
 * <h2>The credential</h2>
 *
 * <p>An opaque, assignment-scoped bearer token that exists in the authorization response and nowhere else. It
 * is delivered into the sandbox's environment, and the working assumption is that whatever runs there can read
 * it. Its protection is therefore not secrecy from the workload but the narrowness of what it authorizes: one
 * run, one attempt, one assignment epoch, one policy, briefly, and revalidated against authoritative state on
 * every request and every tunnel revalidation. A stolen one authorizes nothing else.
 *
 * <p>What it must never do is outlive the execution in something readable by anything that is not it — a log,
 * a metric, a container label, a database row, the persisted command. {@link #toString()} is overridden for
 * that reason: a record's generated {@code toString} prints every component, and this object is exactly the
 * kind that ends up interpolated into a debug line by accident.
 */
public record EgressPlan(String capabilityToken, List<EgressTarget> destinations) {

    public EgressPlan {
        Objects.requireNonNull(capabilityToken, "An allowlist execution carries an egress capability.");
        if (capabilityToken.isBlank()) {
            throw new IllegalArgumentException("An egress capability is not blank.");
        }
        destinations = List.copyOf(destinations);
        if (destinations.isEmpty()) {
            // An allowlist with nothing on it is not a policy this runner can act on. The control plane's own
            // model already refuses one, and refusing again here means a delivery that somehow lost its
            // destinations fails rather than launching a workload with nowhere to go.
            throw new IllegalArgumentException("An allowlist names at least one destination.");
        }
    }

    /**
     * The destination the synthetic workload exercises.
     *
     * <p>The first, deterministically. A policy may name several and the workload demonstrates the mechanism
     * rather than surveying the policy; picking arbitrarily would make the evidence differ between two runs of
     * the same run snapshot.
     */
    public EgressTarget primary() {
        return destinations.get(0);
    }

    /**
     * A port on the primary destination's host that the policy provably does not name.
     *
     * <p>The workload needs a destination that must be refused, and inventing a hostname for it would be a
     * guess — a tenant's policy could name any hostname anyone thought of. A port is different: the set is
     * finite, the policy's own entries are right here, and the first port absent from them is refused by
     * construction rather than by hope. The proxy denies it before resolving anything, so this also needs no
     * name to exist anywhere.
     */
    public int unlistedPortOnPrimary() {
        String host = primary().host();
        for (int candidate = 1; candidate <= 65535; candidate++) {
            int port = candidate;
            boolean listed = destinations.stream()
                    .anyMatch(destination -> destination.host().equals(host) && destination.port() == port);
            if (!listed) {
                return port;
            }
        }
        // Unreachable in practice — it would take a policy naming all 65535 ports of one host — and an
        // exception is the right answer to it rather than a port that IS listed, which would make the
        // workload's denial scenario silently assert the opposite of what it claims.
        throw new IllegalStateException("Every port of the destination host is allowlisted.");
    }

    /** Redacted. See the class comment: the token must not reach a log by way of string interpolation. */
    @Override
    public String toString() {
        return "EgressPlan[capabilityToken=<redacted>, destinations=" + destinations + "]";
    }
}
