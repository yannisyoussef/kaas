package com.kaas.runner.gate;

import java.util.List;
import java.util.Optional;

/**
 * What the egress gate observed, and the proxy image it observed it with.
 *
 * <h2>Why the image travels with the checks</h2>
 *
 * <p>{@code EGRESS_PROXY_IMAGE_PINNED=PASS} says an image was pinned. It does not say <em>which</em>, and
 * evidence that cannot name the artifact it demonstrated is evidence about nothing in particular. Binding the
 * two in one value means an attestation cannot pair one assessment's verdicts with another assessment's image
 * — which is exactly the substitution a signature is supposed to make impossible, and which would otherwise be
 * possible before the signature was ever computed.
 *
 * <p>It also replaces a field the gate populated as a side effect of running and exposed through a getter. A
 * result that has to be collected from the thing that produced it, in the right order, is a result somebody
 * will eventually collect in the wrong order.
 *
 * @param proxyImageReference the content-addressed image the gate built and ran, or empty when it never got
 *     far enough to build one — in which case every control below is a failure and nothing was demonstrated
 */
public record EgressEnforcementAssessment(Optional<String> proxyImageReference, List<SecurityCheck> checks) {

    public EgressEnforcementAssessment {
        checks = List.copyOf(checks);
    }

    /**
     * Fail-closed: every mandatory control positively demonstrated, and at least one demonstrated at all.
     *
     * <p>The emptiness check is not defensive noise. A filter for blockers over an empty list finds nothing,
     * so an assessment that observed nothing would report exactly what a perfect one reports — and "nothing was
     * demonstrated" must never read as "everything passed".
     */
    public boolean passed() {
        return !checks.isEmpty() && checks.stream().noneMatch(SecurityCheck::blocksRelease);
    }

    public List<SecurityCheck> blockers() {
        return checks.stream().filter(SecurityCheck::blocksRelease).toList();
    }

    /** Nothing was demonstrated. Distinguished from "demonstrated and failed", which is different evidence. */
    public static EgressEnforcementAssessment nothingObserved() {
        return new EgressEnforcementAssessment(Optional.empty(), List.of());
    }
}
