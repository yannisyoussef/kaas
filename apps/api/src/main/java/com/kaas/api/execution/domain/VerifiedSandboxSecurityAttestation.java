package com.kaas.api.execution.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An attestation whose signature has already been verified against a pinned key.
 *
 * <h2>Only the verifier can make one</h2>
 *
 * <p>The constructor is package-private and {@link SandboxSecurityAttestationVerifier} is the only thing in
 * this package that calls it. That is the point of the type existing at all: every method below reads security
 * verdicts, and "has anyone checked where these came from?" is answered by the type rather than by reading the
 * call chain. A parsed-but-unauthenticated document cannot be represented as one of these, so it cannot reach
 * the semantic checks by accident.
 *
 * <p>The remaining questions are deliberately <em>not</em> answered here. Authenticity says the evidence is
 * genuine; it says nothing about whether it is recent, whether it describes this runtime, or whether the
 * controls passed. A signature proves origin and integrity and nothing else, and treating it as though it
 * proved freshness is the single most common way this kind of design fails.
 */
public final class VerifiedSandboxSecurityAttestation {

    /**
     * How far ahead of database time an assessment may be stamped before it is treated as wrong.
     *
     * <p>Not slack — a correction. {@code assessedAt} is stamped by whichever host ran the gate and the
     * comparison instant comes from the database, so these are two clock domains. With zero tolerance,
     * ordinary sub-second drift makes a freshly produced attestation unusable and refuses all execution with a
     * message that would lead nobody to the clock. The staleness bound on the other side is measured in hours;
     * a minute here is the same judgement applied symmetrically.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(1);

    private final AttestationPayloadFields payload;

    private final String payloadDigest;

    VerifiedSandboxSecurityAttestation(AttestationPayloadFields payload, String payloadDigest) {
        this.payload = payload;
        this.payloadDigest = payload.payloadDigest();
        if (!this.payloadDigest.equals(payloadDigest)) {
            // Unreachable through the verifier, which compares them before constructing this. Restated here
            // because this object's digest is what an ExecutionCommand will bind, and a mismatch would bind a
            // command to evidence other than the evidence that authorized it.
            throw new IllegalStateException("A verified attestation carries the digest of its own payload.");
        }
    }

    public AttestationPayloadFields payload() {
        return payload;
    }

    /** The authenticated evidence identity. This is what {@code ExecutionCommand} binds. */
    public String payloadDigest() {
        return payloadDigest;
    }

    /**
     * Why this authentic evidence still cannot authorize an execution here and now, or empty.
     *
     * <p>Every branch is a refusal. There is no path that reaches the end without each condition having been
     * positively established, and the outcome is a category rather than a boolean so a caller cannot treat
     * "could not evaluate" as "evaluated successfully".
     *
     * @param acceptedRuntimeSubjects the runtimes this deployment was told to accept evidence for. Empty means
     *     none — which refuses everything, because a control plane that accepts evidence for any subject lets
     *     a signature from host A authorize an execution on host B
     */
    public Optional<AttestationVerification> reasonItCannotAuthorize(
            Instant now,
            Duration maximumAge,
            String expectedProfileVersion,
            Set<String> acceptedRuntimeSubjects) {

        if (!acceptedRuntimeSubjects.contains(payload.runtimeSubject())) {
            // THE host-A-authorizes-host-B check. Displaying the subject rather than validating it would make
            // a signed attestation universal across every runtime the same key ever signed for.
            return Optional.of(AttestationVerification.WRONG_SUBJECT);
        }
        if (payload.assessedAt().isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            // An assessment from the future is a clock problem or a forgery, and neither is a reason to run.
            return Optional.of(AttestationVerification.STALE);
        }
        if (payload.assessedAt().isBefore(now.minus(maximumAge))) {
            // Freshness matters because what is attested is a property of a running host, not of the source
            // tree. A host reconfigured last month is not described by an assessment from last year. A
            // signature does not make an old statement current.
            return Optional.of(AttestationVerification.STALE);
        }
        if (!expectedProfileVersion.equals(payload.securityProfileVersion())) {
            return Optional.of(AttestationVerification.PROFILE_MISMATCH);
        }
        if (!coveredAndPassing(payload.mandatoryControls(), RequiredSecurityControls.MANDATORY)) {
            return Optional.of(AttestationVerification.CONTROL_FAILED);
        }
        return Optional.empty();
    }

    /**
     * Why this deployment cannot be relied on to enforce a destination allowlist, or empty.
     *
     * <p>Called only for an {@code ALLOWLIST} policy. Calling it unconditionally would refuse a
     * {@code DENY_ALL} run because of a subsystem it does not use.
     *
     * <p>An empty egress control set is a refusal, not a pass: an assessment produced where egress was never
     * exercised carries no egress controls at all, and the fail-closed reading of "no evidence" is "not
     * enforceable".
     */
    public Optional<AttestationVerification> reasonEgressCannotBeEnforced() {
        return coveredAndPassing(payload.egressControls(), RequiredSecurityControls.EGRESS)
                ? Optional.empty()
                : Optional.of(AttestationVerification.CONTROL_FAILED);
    }

    /** Which controls did not pass, for an operator. Safe to say: the signature already proved the source. */
    public List<String> controlsNotPassing() {
        return java.util.stream.Stream.concat(
                        payload.mandatoryControls().entrySet().stream(),
                        payload.egressControls().entrySet().stream())
                .filter(control -> !RequiredSecurityControls.PASS.equals(control.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * Exact coverage in both directions, and every member passing.
     *
     * <p>Equality rather than containment: a shorter assessment must not pass by omitting what it failed, and
     * an assessment covering a different set was produced for a different definition of "secure".
     */
    private static boolean coveredAndPassing(Map<String, String> observed, Set<String> required) {
        return observed.keySet().equals(required)
                && observed.values().stream().allMatch(RequiredSecurityControls.PASS::equals);
    }
}
