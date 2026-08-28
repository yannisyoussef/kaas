package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RunClaimRepository {
    /**
     * Locks the run this dispatch names and the attempt it holds, whatever state they are in.
     *
     * <p>Deliberately not filtered to claimable runs. Filtering here would collapse every reason a claim cannot
     * proceed into one empty answer, and the consumer needs to tell them apart: a run somebody else already
     * claimed is a duplicate delivery, while a cancelled one is stale. It would also make the "attempt already
     * assigned" branch unreachable, which is how a check becomes decoration.
     *
     * <p>Empty means no such run in this organization — which is also the answer for another tenant's run.
     */
    Optional<ClaimableRun> lockClaimable(UUID organizationId, UUID runId);

    Instant currentDatabaseTime();

    /**
     * Writes the claim: the run's compare-and-set to CLAIMED, the assignment on its attempt, and the lifecycle
     * event. One transaction, because a run that says it is claimed while its attempt says nobody owns it is the
     * exact inconsistency the assignment epoch exists to make impossible.
     */
    void persistClaim(
            UUID organizationId, TestRun previous, TestRun claimed, ExecutionAttempt attempt, UUID lifecycleEventId);

    /**
     * The durable dispatch this message claims to be, as the control plane recorded it when it was produced.
     *
     * <p>Looked up by message identity so the delivered bytes can be checked against what was actually published
     * rather than believed on their own account. A message whose identity is unknown here was never produced by
     * this control plane, however well formed it looks.
     */
    Optional<PersistedDispatch> findDispatch(UUID organizationId, UUID messageId);

    /** A run and the attempt it names, read together under one lock. */
    record ClaimableRun(TestRun run, ExecutionAttempt attempt) {}

    /** The trusted columns of a dispatch, which are authoritative where the message body is not. */
    record PersistedDispatch(
            UUID organizationId,
            UUID projectId,
            UUID runId,
            long runVersion,
            UUID attemptId,
            UUID runSnapshotId,
            String runSnapshotDigest,
            String payloadDigest) {}
}
