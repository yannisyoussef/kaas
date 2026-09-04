package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.TestOutcome;
import java.time.Instant;
import java.util.UUID;

/**
 * Reads the identity a result document claims for itself.
 *
 * <p>A port rather than a static helper because what it returns is untrusted input, and naming it as a
 * boundary makes that visible at every call site. Nothing this returns is authoritative: it exists so the
 * control plane can compare the document's claims against state the document did not supply, and refuse on
 * disagreement.
 */
public interface ResultDocumentReader {

    /**
     * @throws RuntimeException if the document is not readable as a result. The caller converts that into one
     *     refusal, deliberately indistinguishable from a provenance mismatch.
     */
    ParsedResult read(String document);

    /** The claims a result document makes about which execution it describes and how that execution went. */
    record ParsedResult(
            UUID organizationId,
            UUID projectId,
            UUID runId,
            long runVersion,
            UUID attemptId,
            int assignmentEpoch,
            UUID commandId,
            Instant startedAt,
            Instant finishedAt,
            TestOutcome testOutcome,
            InfrastructureOutcome infrastructureOutcome) {}
}
