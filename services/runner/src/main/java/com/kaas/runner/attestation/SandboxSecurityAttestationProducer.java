package com.kaas.runner.attestation;

import com.kaas.runner.gate.EgressEnforcementAssessment;
import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.gate.SecurityCheck;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns what the security gates actually observed into a signed statement.
 *
 * <h2>The point of this class is what it will not accept</h2>
 *
 * <p>There is no {@code produce(Map<String, String> verdicts)}. The old workflow was: a gate runs, a human
 * reads its output, a human types {@code "NON_ROOT_UID": "PASS"} into a JSON file, and a human recomputes a
 * digest over what they typed. Every step after the first was a place to write something that was never
 * observed, and the digest authenticated the typing rather than the observation.
 *
 * <p>The inputs here are assessment objects that only the gates construct, and the verdicts are read off them.
 * A caller who wants to claim a control passed has to produce an assessment saying so, and the only thing that
 * produces one is the gate, running against a real runtime. That does not make fabrication impossible — a
 * process with the signing key can sign anything — but it moves the boundary to "hold the private key" rather
 * than "be able to type", which is the whole of the improvement this slice is for.
 *
 * <h2>It signs failures</h2>
 *
 * <p>A gate that observed a failure produces an attestation recording that failure, truthfully, and signs it.
 * Refusing to serialize would leave an operator with no artifact and no explanation. Authorization is a
 * separate question and is answered elsewhere: the control plane requires exact coverage of the required set
 * <em>and</em> a pass for every member, so a signed failure is authentic evidence that authorizes nothing.
 */
public final class SandboxSecurityAttestationProducer {

    /** Which producer built an artifact, so a defect in one can be traced to the artifacts it produced. */
    public static final String PRODUCER_VERSION = "kaas.attestation-producer.v1";

    private final AttestationSigner signer;

    public SandboxSecurityAttestationProducer(AttestationSigner signer) {
        this.signer = signer;
    }

    /**
     * Builds and signs the attestation for one assessed runtime.
     *
     * @param mandatory what the hostile-execution gate observed. Its profile version, runtime, and assessment
     *     instant are taken from here rather than from a parameter, so the artifact cannot describe one
     *     assessment under another assessment's identity
     * @param egress what the egress gate observed, or {@link EgressEnforcementAssessment#nothingObserved()}
     *     when egress was not assessed. Nothing observed produces an attestation with no egress controls and
     *     no proxy image — which the control plane reads as "makes no egress claim", and refuses an ALLOWLIST
     * @param runtime the opaque subject and generation this evidence describes
     * @param probeImageDigest the content-addressed probe image the mandatory assessment ran
     */
    public SignedAttestation produce(
            HostileExecutionAssessment mandatory,
            EgressEnforcementAssessment egress,
            RuntimeIdentity runtime,
            String probeImageDigest) {

        if (mandatory.checks().isEmpty()) {
            // Nothing was demonstrated. Signing it would produce an authentic statement that says nothing,
            // and the control plane's coverage check would refuse it — but it would refuse it as "incomplete
            // evidence" rather than as "the gate never ran", and those want different investigations.
            throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_UNAVAILABLE,
                    "The hostile-execution gate produced no observations to attest.");
        }
        if (probeImageDigest == null || !probeImageDigest.matches("sha256:[a-f0-9]{64}")) {
            // A mutable tag is not an identity. The whole assessment describes what that image did.
            throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_INCOMPLETE,
                    "The probe image must be identified by a digest, not a tag.");
        }

        AttestationPayload payload = new AttestationPayload(
                AttestationPayload.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                PRODUCER_VERSION,
                signer.keyId(),
                AttestationPayload.SIGNATURE_ALGORITHM,
                mandatory.profileVersion(),
                mandatory.runtime(),
                // From the assessment, which derived it from the launcher. The producer does not get to name
                // the boundary either -- it signs what was measured.
                mandatory.sandboxRuntime(),
                runtime.subject(),
                runtime.generation(),
                probeImageDigest,
                proxyImageOf(egress),
                mandatory.assessedAt(),
                verdictsOf(mandatory.checks()),
                verdictsOf(egress.checks()));
        return SignedAttestation.of(payload, signer);
    }

    /**
     * The proxy image, but only when there is egress evidence to attach it to.
     *
     * <p>An image reference beside an empty control set would be an artifact naming an image it demonstrated
     * nothing about. Absent and present are different statements in the preimage precisely so this distinction
     * survives into the signature.
     */
    private static Optional<String> proxyImageOf(EgressEnforcementAssessment egress) {
        return egress.checks().isEmpty() ? Optional.empty() : egress.proxyImageReference();
    }

    /**
     * The gate's verdicts for the controls a verifier will demand, read off its own output.
     *
     * <p><strong>Mandatory-enforcement checks only.</strong> The gate also reports deployment-specific
     * hardening — a custom seccomp profile, AppArmor, SELinux, user namespaces, a rootless daemon — which is
     * real and worth observing and is <em>never required</em>. Including it here was a defect found by running
     * the real chain: the gate emitted eighteen checks, the control plane requires exactly sixteen and matches
     * the set for equality, and every attestation this producer made was therefore refused as
     * {@code CONTROL_FAILED} while naming {@code USER_NAMESPACE} — a control nothing has ever required.
     *
     * <p>Filtering is not hiding evidence. The gate's own assessment is the complete record; this map is the
     * authenticated claim about the controls a verifier checks, and putting anything else in it makes the
     * exact-coverage rule — which is what stops a truncated assessment from passing — unsatisfiable.
     *
     * <p>{@link SecurityCheck.Verdict#UNSUPPORTED} is carried through as itself rather than folded into
     * {@code FAIL}. "This host cannot enforce it" and "this host was shown not to enforce it" are different
     * facts, neither is a pass, and an operator reading the artifact needs to tell them apart.
     */
    private static Map<String, String> verdictsOf(java.util.List<SecurityCheck> checks) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (SecurityCheck check : checks) {
            if (check.enforcement() != SecurityCheck.Enforcement.MANDATORY) {
                continue;
            }
            String previous = verdicts.put(check.control(), check.verdict().name());
            if (previous != null && !previous.equals(check.verdict().name())) {
                // One control reported twice with two answers is an assessment that contradicts itself, and a
                // map would silently keep whichever came last.
                throw new AttestationProductionFailed(
                        AttestationFailure.ASSESSMENT_INCOMPLETE,
                        "A control was assessed twice with different verdicts: " + check.control());
            }
        }
        return Map.copyOf(verdicts);
    }
}
