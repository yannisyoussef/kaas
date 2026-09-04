package com.kaas.api.execution.domain;

import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.TestOutcome;
import java.time.Instant;
import java.util.UUID;

/**
 * What one execution produced, bound to the assignment that produced it.
 *
 * <p>The binding is the whole point. A result document arrives over the network from a worker, and on its own
 * account it is a claim: it says which run it belongs to and how that run went. What makes it evidence is that
 * the control plane independently establishes it came from the assignment currently authorized to produce it —
 * the same run, the same attempt, the same epoch, answering the same command, over the same sealed snapshot.
 *
 * <p>Every identity field here is therefore recorded from authoritative state rather than copied out of the
 * document. The document's own copies of these fields are checked against these and the submission is refused
 * on any disagreement; they are never the source.
 */
public record ExecutionResult(
        UUID resultId,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        UUID attemptId,
        int assignmentEpoch,
        UUID commandId,
        String runSnapshotSha256,
        String resultDigest,
        TestOutcome testOutcome,
        InfrastructureOutcome infrastructureOutcome,
        String document,
        Instant submittedAt) {

    public ExecutionResult {
        if (resultId == null || organizationId == null || projectId == null || runId == null
                || attemptId == null || commandId == null || submittedAt == null) {
            throw new IllegalArgumentException("A result names the assignment that produced it.");
        }
        if (testOutcome == null || infrastructureOutcome == null) {
            throw new IllegalArgumentException("A result carries both outcomes.");
        }
        // Restated here as well as in the database, because this record is constructed on paths that do not
        // reach the database until later and a violation found at the boundary names its cause; one found at
        // INSERT names a constraint.
        boolean succeeded = infrastructureOutcome == InfrastructureOutcome.SUCCEEDED;
        boolean hasTestOutcome = testOutcome == TestOutcome.PASSED || testOutcome == TestOutcome.FAILED;
        if (succeeded != hasTestOutcome) {
            throw new IllegalArgumentException(
                    "A test outcome exists exactly when the infrastructure succeeded.");
        }
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("A result carries its document.");
        }
        if (runSnapshotSha256 == null || !runSnapshotSha256.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("A result names the snapshot it ran.");
        }
        if (resultDigest == null || !resultDigest.matches("^sha256:[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("A result carries its own digest.");
        }
        if (assignmentEpoch < 1 || assignmentEpoch > 1000) {
            throw new IllegalArgumentException("A result names a plausible assignment epoch.");
        }
    }
}
