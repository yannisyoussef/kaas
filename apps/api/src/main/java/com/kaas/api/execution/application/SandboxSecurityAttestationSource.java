package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.AttestationVerification;
import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import com.kaas.api.execution.domain.VerifiedSandboxSecurityAttestation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the deployment's <em>verified</em> sandbox security assessment, or nothing.
 *
 * <h2>What changed, and why the type did too</h2>
 *
 * <p>This used to hand back a parsed document whose SHA-256 matched its own contents. That proved the document
 * had not changed relative to its digest — and nothing else. It did not prove the document came from a security
 * gate, that the controls were observed by any runtime, or that the deployment authorizing an execution was
 * consuming evidence from the runtime that was assessed. An operator wrote {@code "NON_ROOT_UID": "PASS"} by
 * hand and recomputed the digest over what they had written.
 *
 * <p>Now the document must carry an Ed25519 signature from a key this deployment has pinned, and what escapes
 * this class is a {@link VerifiedSandboxSecurityAttestation} — a type only the verifier can construct. An
 * unauthenticated document cannot be represented as one, so it cannot reach the code that reads verdicts.
 *
 * <h2>Verified once, evaluated every time</h2>
 *
 * <p>Authenticity is a property of the artifact and does not change, so it is established at startup.
 * Freshness, runtime subject, profile and control coverage are properties of <em>this authorization, now</em>,
 * so they are evaluated per request. A signature proves origin and integrity; treating it as though it proved
 * freshness is the most common way this kind of design fails.
 *
 * <h2>Still no endpoint</h2>
 *
 * <p>The document arrives as deployment configuration, in the same trust domain as the database credentials
 * and the JWT issuer — the operator's — and emphatically not the tenant's or the worker's. There is
 * deliberately no API that accepts one and none that registers a verification key, so nothing that
 * authenticates to this service can assert its own security posture or nominate who may vouch for it.
 */
@Component
public class SandboxSecurityAttestationSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSecurityAttestationSource.class);

    private final Optional<VerifiedSandboxSecurityAttestation> attestation;

    private final AttestationVerification outcome;

    private final Set<String> acceptedRuntimeSubjects;

    /**
     * @param document the signed artifact, transported here as configuration
     * @param runtimeSubjects the runtimes this control plane will accept evidence for. Empty means none, and
     *     none means no execution: a control plane that accepted evidence for any subject would let a
     *     signature produced on one host authorize an execution on another
     */
    public SandboxSecurityAttestationSource(
            @Value("${kaas.execution.sandbox-attestation:}") String document,
            @Value("${kaas.execution.attestation-runtime-subjects:}") String runtimeSubjects,
            AttestationTrustStore trustStore,
            MeterRegistry meters) {

        this.acceptedRuntimeSubjects = Set.copyOf(
                java.util.Arrays.stream(runtimeSubjects == null ? new String[0] : runtimeSubjects.split(","))
                        .map(String::trim)
                        .filter(subject -> !subject.isEmpty())
                        .toList());

        SandboxSecurityAttestationVerifier.Result result =
                new SandboxSecurityAttestationVerifier(trustStore).verify(document);
        this.outcome = result.outcome();
        this.attestation = result.attestation();

        Counter.builder("kaas.security.attestation.verification")
                .tag("result", outcome.name())
                .register(meters)
                .increment();

        if (outcome.accepted()) {
            LOGGER.atInfo()
                    .addKeyValue("event", "SANDBOX_ATTESTATION_VERIFIED")
                    // Safe to log: an id, a key id, and a digest. Not the controls, not the runtime subject,
                    // and never the signature — the first two are host-describing and the third is bulk.
                    .addKeyValue("attestationId", attestation.orElseThrow().payload().attestationId())
                    .addKeyValue("keyId", attestation.orElseThrow().payload().keyId())
                    .addKeyValue("payloadDigest", attestation.orElseThrow().payloadDigest())
                    .log("Sandbox security attestation verified against a pinned key");
        } else {
            LOGGER.atError()
                    .addKeyValue("event", "SANDBOX_ATTESTATION_REFUSED")
                    // The CATEGORY only. This document is operator-supplied configuration an attacker may have
                    // influenced, and a diagnostic that quoted it would repeat attacker-chosen text into a log.
                    .addKeyValue("outcome", outcome.name())
                    .log("No usable sandbox security attestation; execution authorization is unavailable");
        }
    }

    /** The verified attestation, if one is configured and authentic. Never a partially-checked one. */
    public Optional<VerifiedSandboxSecurityAttestation> attestation() {
        return attestation;
    }

    /** Which stage refused, when there is nothing usable. */
    public AttestationVerification outcome() {
        return outcome;
    }

    /** The runtimes this deployment accepts evidence for. Empty refuses everything, by construction. */
    public Set<String> acceptedRuntimeSubjects() {
        return acceptedRuntimeSubjects;
    }
}
