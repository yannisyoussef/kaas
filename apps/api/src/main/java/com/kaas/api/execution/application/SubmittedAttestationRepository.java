package com.kaas.api.execution.application;

import java.time.Instant;
import java.util.Optional;

/** Runner-submitted sandbox security evidence. Rows are appended and never edited. */
public interface SubmittedAttestationRepository {

    /** The worker's most recently ASSESSED submission, whatever its age. Freshness is judged by the caller. */
    Optional<Stored> latestFor(String workerId);

    /**
     * Appends one verified submission. Returns false when this worker already submitted this attestation id,
     * which is a retry of a submission that landed rather than a new one.
     */
    boolean insert(Stored submission);

    /** The database clock, the one authority every freshness decision in this deployment is made against. */
    Instant currentDatabaseTime();

    /** How many distinct workers hold a submission assessed at or after {@code since}. */
    int workersAssessedSince(Instant since);

    /** How many distinct workers asked for work at or after {@code since}. */
    int workersSeenSince(Instant since);

    /** How many workers did both: asked for work since {@code seenSince}, and hold evidence assessed since
     * {@code assessedSince}. The deployment check's "a runner is here and can be authorized". */
    int workersSeenWithEvidence(Instant seenSince, Instant assessedSince);

    record Stored(
            String workerId,
            String attestationId,
            String keyId,
            String payloadDigest,
            String runtimeSubject,
            String runtimeImplementationDigest,
            Instant assessedAt,
            Instant receivedAt,
            String document) {}
}
