package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.AttestationVerification;
import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import com.kaas.api.execution.domain.VerifiedSandboxSecurityAttestation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Answers one question offline: would this control plane accept this attestation?
 *
 * <h2>Why this exists</h2>
 *
 * <p>An operator who has produced an artifact on a runner host needs to know whether the control plane will
 * take it <em>before</em> deploying it, and a CI job needs to prove that the document its producer just wrote
 * is one a real verifier accepts. Discovering either at authorization time means discovering it as a refused
 * execution with a category and no artifact to look at.
 *
 * <p>It uses the same verifier the control plane uses, with the trust root supplied the same way. That is the
 * point: a separate "checker" with its own parsing would eventually disagree with the thing that matters, and
 * the disagreement would be discovered by an outage.
 *
 * <h2>What it grants</h2>
 *
 * <p>Nothing. It reads a file and prints a category. It cannot authorize an execution, cannot register a key,
 * and cannot make the running control plane accept anything it would otherwise refuse — the trust root it uses
 * is the one passed to this process, and a deployment's is the one in its own configuration.
 */
public final class AttestationVerificationCli {

    private AttestationVerificationCli() {}

    public static void main(String[] args) throws Exception {
        String document = Files.readString(Path.of(required("kaas.attestation.verify.document")));
        AttestationTrustStore trustStore =
                new AttestationTrustStore(required("kaas.attestation.verify.trusted-keys"));

        var result = new SandboxSecurityAttestationVerifier(trustStore).verify(document);
        System.out.println("verification=" + result.outcome());
        if (!result.outcome().accepted()) {
            // A category and nothing from the document. Before authenticity is established the contents are
            // attacker-influenceable, and this output goes into CI logs and tickets.
            System.exit(1);
        }

        VerifiedSandboxSecurityAttestation attestation = result.attestation().orElseThrow();
        // Safe to print now: a pinned key produced this, so it is a trusted producer's statement.
        System.out.println("attestationId=" + attestation.payload().attestationId());
        System.out.println("keyId=" + attestation.payload().keyId());
        System.out.println("payloadDigest=" + attestation.payloadDigest());
        System.out.println("runtimeSubject=" + attestation.payload().runtimeSubject());
        System.out.println("securityProfileVersion=" + attestation.payload().securityProfileVersion());
        System.out.println("probeImageDigest=" + attestation.payload().probeImageDigest());
        System.out.println(
                "egressProxyImageDigest=" + attestation.payload().egressProxyImageDigest().orElse("ABSENT"));
        System.out.println("mandatoryControls=" + attestation.payload().mandatoryControls().size());
        System.out.println("egressControls=" + attestation.payload().egressControls().size());
        System.out.println("controlsNotPassing=" + attestation.controlsNotPassing());

        // The semantic verdict too, when the caller says which runtime and profile it means. Authenticity is
        // not sufficiency, and an artifact that verifies but could never authorize anything is a thing an
        // operator should learn here rather than from a refused execution.
        String subjects = System.getProperty("kaas.attestation.verify.runtime-subjects", "");
        String profile = System.getProperty("kaas.attestation.verify.profile-version", "");
        if (!subjects.isBlank() && !profile.isBlank()) {
            var unusable = attestation.reasonItCannotAuthorize(
                    Instant.now(),
                    Duration.parse(
                            System.getProperty("kaas.attestation.verify.maximum-age", "PT24H")),
                    profile,
                    Set.of(subjects.split(",")));
            System.out.println("authorizes=" + unusable.isEmpty());
            unusable.ifPresent(reason -> System.out.println("wouldRefuseBecause=" + reason));
            System.out.println(
                    "egressEnforceable=" + attestation.reasonEgressCannotBeEnforced().isEmpty());
            if (unusable.isPresent()) {
                System.exit(2);
            }
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be set.");
        }
        return value;
    }

    /** Unused here, but named so the enum is on the classpath the CLI reports from. */
    static AttestationVerification unusedMarker() {
        return AttestationVerification.VALID;
    }
}
