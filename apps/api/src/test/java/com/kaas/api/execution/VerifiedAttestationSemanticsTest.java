package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.AttestationVerification;
import com.kaas.api.execution.domain.PinnedVerificationKeys;
import com.kaas.api.execution.domain.RequiredSecurityControls;
import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import com.kaas.api.execution.domain.VerifiedSandboxSecurityAttestation;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an <em>authentic</em> attestation still has to satisfy before it authorizes anything.
 *
 * <p>Every document here verifies: the signature is genuine and the key is pinned. That is deliberate. These
 * tests are about the second half of the design — a signature proves origin and integrity and says nothing
 * about whether the evidence is recent, describes this runtime, was taken under the right profile, or records
 * controls that passed. Conflating the two is the most common way a design like this fails.
 *
 * <p>The tests that cover the first half — tampering, unknown keys, wrong algorithms, unsigned v2 — live in
 * {@code AttestationSigningVectorTest}, against fixed vectors neither implementation computed.
 */
@DisplayName("Verified attestation semantics")
class VerifiedAttestationSemanticsTest {

    private static final String PROFILE = "kaas.sandbox.v1";

    private static final Duration MAX_AGE = Duration.ofHours(24);

    private static final Set<String> ACCEPTED = Set.of(SignedAttestationFixture.RUNTIME_SUBJECT);

    @Test
    @DisplayName("a complete, recent, passing assessment for this runtime authorizes")
    void aCompleteRecentPassingAssessmentAuthorizes() {
        assertThat(reason(SignedAttestationFixture.mandatoryOnly(PROFILE, Instant.now()))).isEmpty();
    }

    @Test
    @DisplayName("an assessment missing one required control is refused")
    void anAssessmentMissingOneControlIsRefused() {
        // Coverage is exact equality, not containment. Containment would let a truncated assessment pass by
        // omitting the very control it failed.
        assertThat(reason(SignedAttestationFixture.signed(
                        builder().withoutMandatoryControl("NETWORK_DENIED"))))
                .contains(AttestationVerification.CONTROL_FAILED);
    }

    @Test
    @DisplayName("an assessment covering an unknown extra control is refused")
    void anExtraControlIsRefused() {
        // The other direction of the same rule: an assessment covering a different set was produced for a
        // different definition of "secure", and this build has no idea what the extra one means.
        assertThat(reason(SignedAttestationFixture.signed(
                        builder().withMandatoryControl("SOMETHING_ELSE", "PASS"))))
                .contains(AttestationVerification.CONTROL_FAILED);
    }

    @Test
    @DisplayName("a failing control is refused, and an operator can be told which")
    void aFailingControlIsRefusedAndNamed() {
        var verified = verify(SignedAttestationFixture.signed(
                builder().withMandatoryControl("NO_DOCKER_SOCKET", "FAIL")));

        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED))
                .contains(AttestationVerification.CONTROL_FAILED);
        // Safe to say out loud, and only here: the signature already proved a pinned key produced it, so this
        // is a trusted producer's statement rather than attacker-influenced text.
        assertThat(verified.controlsNotPassing()).containsExactly("NO_DOCKER_SOCKET");
    }

    @Test
    @DisplayName("UNSUPPORTED is not a pass")
    void unsupportedIsNotAPass() {
        // "This host cannot enforce it" and "this host enforces it" are opposite statements. Only one verdict
        // counts as demonstrated, and the gate reports the difference precisely so it is not flattened here.
        assertThat(reason(SignedAttestationFixture.signed(
                        builder().withMandatoryControl("KERNEL_PATHS_MASKED", "UNSUPPORTED"))))
                .contains(AttestationVerification.CONTROL_FAILED);
    }

    @Test
    @DisplayName("an assessment older than the maximum age is refused")
    void aStaleAssessmentIsRefused() {
        assertThat(reason(SignedAttestationFixture.mandatoryOnly(
                        PROFILE, Instant.now().minus(30, ChronoUnit.DAYS))))
                .contains(AttestationVerification.STALE);
    }

    @Test
    @DisplayName("an assessment dated beyond the skew tolerance is refused")
    void aFutureAssessmentIsRefused() {
        // An assessment from the future is a clock problem or a forgery, and neither is a reason to run.
        assertThat(reason(SignedAttestationFixture.mandatoryOnly(
                        PROFILE, Instant.now().plus(10, ChronoUnit.MINUTES))))
                .contains(AttestationVerification.STALE);
    }

    @Test
    @DisplayName("a small clock difference between the two hosts is tolerated")
    void ordinaryClockSkewIsNotAForgery() {
        // The producer stamps assessedAt from its own clock and this compares against the database's. With
        // zero tolerance, ordinary sub-second drift makes a freshly produced attestation unusable and refuses
        // all execution with a message that would lead nobody to the clock.
        assertThat(reason(SignedAttestationFixture.mandatoryOnly(
                        PROFILE, Instant.now().plus(20, ChronoUnit.SECONDS))))
                .isEmpty();
    }

    @Test
    @DisplayName("an assessment for a different security profile is refused")
    void aDifferentProfileIsRefused() {
        assertThat(reason(SignedAttestationFixture.mandatoryOnly("kaas.sandbox.v1-internal", Instant.now())))
                .contains(AttestationVerification.PROFILE_MISMATCH);
    }

    @Test
    @DisplayName("an assessment for a runtime this deployment does not accept is refused")
    void evidenceForAnotherRuntimeIsRefused() {
        var verified = verify(SignedAttestationFixture.signed(
                builder().withRuntimeSubject("kaas.runtime.elsewhere")));

        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED))
                .contains(AttestationVerification.WRONG_SUBJECT);
    }

    @Test
    @DisplayName("with no accepted runtime subject configured, nothing authorizes")
    void anEmptyAcceptedSetRefusesEverything() {
        // Fail closed, and by construction rather than by a branch: an empty set contains nothing, so a
        // deployment that forgot to name its runtimes authorizes none of them.
        assertThat(verify(SignedAttestationFixture.mandatoryOnly(PROFILE, Instant.now()))
                        .reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, Set.of()))
                .contains(AttestationVerification.WRONG_SUBJECT);
    }

    // ---------------------------------------------------------------------------------------------------
    // Egress evidence, which is required under a different condition
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an assessment making no egress claim cannot enforce an allowlist")
    void anAbsentEgressClaimIsARefusal() {
        var verified = verify(SignedAttestationFixture.mandatoryOnly(PROFILE, Instant.now()));

        // It authorizes a DENY_ALL execution perfectly well. Absence of an egress claim is not a failure of
        // the mandatory evidence — it is simply no statement about egress, and the fail-closed reading of no
        // statement is "not enforceable".
        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED)).isEmpty();
        assertThat(verified.reasonEgressCannotBeEnforced())
                .contains(AttestationVerification.CONTROL_FAILED);
    }

    @Test
    @DisplayName("a complete passing egress claim can enforce an allowlist")
    void aCompletePassingEgressClaimIsAccepted() {
        assertThat(verify(SignedAttestationFixture.withEgress(PROFILE, Instant.now()))
                        .reasonEgressCannotBeEnforced())
                .isEmpty();
    }

    @Test
    @DisplayName("a failing egress control refuses the allowlist but not the execution")
    void aFailingEgressControlIsRefusedOnItsOwn() {
        var verified = verify(SignedAttestationFixture.signed(
                builder().withEgressControl("EGRESS_NO_DIRECT_ROUTE", "FAIL")));

        // The separation that matters: a DENY_ALL run must not be refused because the egress subsystem is
        // unhealthy, because it is a subsystem that run does not use.
        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED)).isEmpty();
        assertThat(verified.reasonEgressCannotBeEnforced())
                .contains(AttestationVerification.CONTROL_FAILED);
    }

    @Test
    @DisplayName("the two required sets are disjoint, so neither can satisfy the other")
    void theTwoRequiredSetsAreDisjoint() {
        // A control in both would be required unconditionally through one door and conditionally through the
        // other, and which rule applied would depend on which check ran first.
        for (String version : RequiredSecurityControls.knownProfileVersions()) {
            assertThat(RequiredSecurityControls.mandatoryFor(version))
                    .as("profile version %s", version)
                    .doesNotContainAnyElementsOf(RequiredSecurityControls.EGRESS);
        }
    }

    // ---------------------------------------------------------------------------------------------------

    private static SignedAttestationFixture.Builder builder() {
        return SignedAttestationFixture.builder(PROFILE, Instant.now());
    }

    private static Optional<AttestationVerification> reason(String document) {
        return verify(document).reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED);
    }

    /** Verifies, and fails loudly if the document was not authentic — these tests are about the next stage. */
    private static VerifiedSandboxSecurityAttestation verify(String document) {
        var result = new SandboxSecurityAttestationVerifier(pinned()).verify(document);
        assertThat(result.outcome())
                .as("these tests are about semantics, so every document here must first be authentic")
                .isEqualTo(AttestationVerification.VALID);
        return result.attestation().orElseThrow();
    }

    private static PinnedVerificationKeys pinned() {
        PublicKey key = publicKey(SignedAttestationFixture.publicKeyOf(SignedAttestationFixture.KEY_ID));
        return new PinnedVerificationKeys() {
            @Override
            public Optional<PublicKey> keyFor(String keyId) {
                return SignedAttestationFixture.KEY_ID.equals(keyId) ? Optional.of(key) : Optional.empty();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }

    private static PublicKey publicKey(String base64Spki) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
        } catch (Exception impossible) {
            throw new IllegalStateException("The published test key is unusable", impossible);
        }
    }
    @Test
    @DisplayName("a document whose profile version and sandbox runtime disagree is refused")
    void aSelfContradictoryRuntimeIsRefused() {
        // Authentic, and still not usable. The two fields describe one boundary, so a document that answers
        // the question twice and differently is not evidence about either -- and resolving the disagreement
        // in favour of one field would hand the choice to whoever wrote the document.
        var verified = verify(SignedAttestationFixture.signed(builder().withSandboxRuntime("GVISOR")));

        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED))
                .contains(AttestationVerification.RUNTIME_MISMATCH);
    }

    @Test
    @DisplayName("evidence taken under the mediating runtime authorizes only where that is what runs")
    void mediatedEvidenceDoesNotAuthorizeTheBaseline() {
        // The pair of the test above, from the other side: a genuine, internally consistent gVisor
        // attestation must not satisfy a deployment expecting the baseline profile. Without this, the
        // runtime binding would only ever refuse malformed documents and never a real mismatch.
        var verified = verify(SignedAttestationFixture.signed(
                SignedAttestationFixture.builder("kaas.sandbox.gvisor.v1", Instant.now())));

        assertThat(verified.reasonItCannotAuthorize(Instant.now(), MAX_AGE, PROFILE, ACCEPTED))
                .contains(AttestationVerification.PROFILE_MISMATCH);
    }

}
