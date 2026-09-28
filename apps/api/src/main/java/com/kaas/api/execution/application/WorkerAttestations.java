package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.SandboxSecurityAttestationVerifier;
import com.kaas.api.execution.domain.VerifiedSandboxSecurityAttestation;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The evidence an authorization for one worker is judged against.
 *
 * <p>A worker that has submitted evidence is judged by ITS OWN LATEST submission and nothing else -- not another
 * worker's, and not the document in deployment configuration. Evidence describes one host, so another host's
 * cannot vouch for it; and falling back to configuration when a worker's own evidence has gone stale would be
 * exactly the stale-evidence extension a refresh exists to make unnecessary.
 *
 * <p>A worker that has never submitted anything is judged by the configured document, as every worker was before
 * submission existed. That keeps a deployment that transports evidence as configuration working unchanged.
 *
 * <p>The stored document is re-verified here, on every use, against the keys pinned NOW. A row is evidence that a
 * document verified when it arrived; it is not a reason to believe it still does after an operator removed the
 * key that signed it.
 */
@Component
public class WorkerAttestations {
    private final SubmittedAttestationRepository submitted;
    private final SandboxSecurityAttestationSource configured;
    private final SandboxSecurityAttestationVerifier verifier;

    public WorkerAttestations(
            SubmittedAttestationRepository submitted,
            SandboxSecurityAttestationSource configured,
            AttestationTrustStore trustStore) {
        this.submitted = submitted;
        this.configured = configured;
        this.verifier = new SandboxSecurityAttestationVerifier(trustStore);
    }

    /** Authentic evidence for this worker, or none. Freshness and sufficiency are the caller's to judge. */
    public Optional<VerifiedSandboxSecurityAttestation> forWorker(String workerId) {
        var own = submitted.latestFor(workerId);
        if (own.isPresent()) {
            return verifier.verify(own.orElseThrow().document()).attestation();
        }
        return configured.attestation();
    }
}
