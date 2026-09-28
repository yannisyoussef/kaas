package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.AttestationVerification;
import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import com.kaas.api.execution.domain.VerifiedSandboxSecurityAttestation;
import com.kaas.api.shared.WorkerIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a runner's freshly signed sandbox security assessment.
 *
 * <h2>The submitter transports; the signature vouches</h2>
 *
 * <p>Until KAAS-DEPLOY-001 an attestation arrived only as deployment configuration, so refreshing one meant
 * restarting the control plane, and a runner living longer than the maximum age stopped being able to execute.
 * This lets the runner deliver a new one itself -- and changes nothing about who may VOUCH for a runtime. A
 * document is stored only if it verifies against a key the operator pinned, and only if it could authorize an
 * execution right now: accepted subject, accepted runtime implementation, the expected profile, every mandatory
 * control passing, and fresh. Nothing here can register a key, widen an accept-list or extend an age limit, and a
 * worker token alone produces nothing a verifier would accept.
 *
 * <p>Evidence is recorded against the worker that submitted it, and an authorization for that worker uses its
 * own latest evidence and nobody else's (see {@link WorkerAttestations}).
 */
@Service
public class AttestationSubmissionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttestationSubmissionService.class);

    /** Generous next to a real document, and far below anything that could exhaust memory. */
    static final int MAX_DOCUMENT_BYTES = 256 * 1024;

    private final SubmittedAttestationRepository repository;
    private final SandboxSecurityAttestationSource accepted;
    private final SandboxSecurityAttestationVerifier verifier;
    private final MeterRegistry meters;
    private final Duration maximumAge;
    private final String expectedProfileVersion;

    public AttestationSubmissionService(
            SubmittedAttestationRepository repository,
            SandboxSecurityAttestationSource accepted,
            AttestationTrustStore trustStore,
            MeterRegistry meters,
            @Value("${kaas.execution.attestation-max-age}") Duration maximumAge,
            @Value("${kaas.execution.security-profile-version}") String expectedProfileVersion) {
        this.repository = repository;
        this.accepted = accepted;
        this.verifier = new SandboxSecurityAttestationVerifier(trustStore);
        this.meters = meters;
        this.maximumAge = maximumAge;
        this.expectedProfileVersion = expectedProfileVersion;
    }

    /**
     * Verifies and stores one submission, or says why not.
     *
     * <p>The refusal is a closed category. It is safe to disclose to the caller -- an authenticated platform
     * worker -- and it is what an operator needs: "the key is not pinned" and "the runtime binary is not on the
     * accept-list" are different fixes.
     */
    @Transactional
    public Submission submit(String workerId, String document) {
        if (!WorkerIdentity.isWorker(workerId)) {
            return refused("NOT_A_WORKER");
        }
        if (document == null || document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            return refused(AttestationVerification.MALFORMED.name());
        }
        SandboxSecurityAttestationVerifier.Result verified = verifier.verify(document);
        if (verified.attestation().isEmpty()) {
            return refused(verified.outcome().name());
        }
        VerifiedSandboxSecurityAttestation attestation = verified.attestation().orElseThrow();
        Instant now = repository.currentDatabaseTime();
        Optional<AttestationVerification> unusable = attestation.reasonItCannotAuthorize(
                now,
                maximumAge,
                expectedProfileVersion,
                accepted.acceptedRuntimeSubjects(),
                accepted.acceptedRuntimeImplementationDigests());
        if (unusable.isPresent()) {
            // Refused rather than stored: evidence that could not authorize anything now would only ever be a
            // way to make an older, still-valid submission stop being this worker's latest.
            return refused(unusable.orElseThrow().name());
        }
        var payload = attestation.payload();
        var latest = repository.latestFor(workerId);
        if (latest.isPresent() && !payload.assessedAt().isAfter(latest.orElseThrow().assessedAt())) {
            if (latest.orElseThrow().attestationId().equals(payload.attestationId())) {
                // A retry of the submission that already landed. The same answer, not an error.
                return accepted(payload.attestationId(), payload.assessedAt(), false);
            }
            return refused("NOT_NEWER");
        }
        boolean inserted = repository.insert(new SubmittedAttestationRepository.Stored(
                workerId,
                payload.attestationId(),
                payload.keyId(),
                attestation.payloadDigest(),
                payload.runtimeSubject(),
                payload.runtimeImplementationDigest(),
                payload.assessedAt(),
                now,
                document));
        if (!inserted) {
            // The same attestation id under an older assessment instant than one already stored. Not newer.
            return refused("NOT_NEWER");
        }
        LOGGER.atInfo()
                .addKeyValue("event", "SANDBOX_ATTESTATION_SUBMITTED")
                .addKeyValue("workerId", workerId)
                // Identifiers and a digest only, exactly as the startup path logs a configured document.
                .addKeyValue("attestationId", payload.attestationId())
                .addKeyValue("keyId", payload.keyId())
                .addKeyValue("payloadDigest", attestation.payloadDigest())
                .log("A worker submitted sandbox security evidence that verified against a pinned key");
        return accepted(payload.attestationId(), payload.assessedAt(), true);
    }

    private Submission accepted(String attestationId, Instant assessedAt, boolean stored) {
        count("ACCEPTED");
        return new Submission(true, "ACCEPTED", attestationId, assessedAt, assessedAt.plus(maximumAge), stored);
    }

    private Submission refused(String code) {
        count(code);
        LOGGER.atWarn()
                .addKeyValue("event", "SANDBOX_ATTESTATION_SUBMISSION_REFUSED")
                .addKeyValue("outcome", code)
                .log("Refused a submitted sandbox security attestation");
        return new Submission(false, code, null, null, null, false);
    }

    private void count(String result) {
        Counter.builder("kaas.security.attestation.submission").tag("result", result).register(meters).increment();
    }

    /** The decision. {@code usableUntil} is when this evidence stops authorizing, on the database clock. */
    public record Submission(
            boolean accepted,
            String code,
            String attestationId,
            Instant assessedAt,
            Instant usableUntil,
            boolean stored) {}
}
