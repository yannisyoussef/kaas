package com.kaas.runner.gate;

import java.time.Instant;
import java.util.List;

/**
 * The result of asking whether this deployment's sandbox boundary enforces what it claims.
 *
 * <p>This is operational evidence, not a user-facing API. It says nothing about a tenant, a run, or a host
 * path, and it is deliberately not exposed through the public contract: an attacker learning which controls a
 * particular deployment cannot enforce is a gift.
 *
 * <p><strong>A passing assessment does not enable execution.</strong> It is one prerequisite among several for
 * a future slice, and the ones it does not cover — source capability issuance, secret capability issuance, an
 * egress policy model — are exactly the ones that let user content near the sandbox in the first place.
 */
public record HostileExecutionAssessment(
        String profileVersion, String runtime, Instant assessedAt, List<SecurityCheck> checks) {

    public HostileExecutionAssessment {
        checks = List.copyOf(checks);
    }

    /** Fail-closed: every mandatory control must have been positively demonstrated. */
    public boolean passed() {
        return checks.stream().noneMatch(SecurityCheck::blocksRelease);
    }

    public List<SecurityCheck> blockers() {
        return checks.stream().filter(SecurityCheck::blocksRelease).toList();
    }
}
