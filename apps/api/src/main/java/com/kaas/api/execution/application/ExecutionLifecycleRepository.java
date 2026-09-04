package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.StopReason;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.execution.domain.ExecutionResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the phases between owning an attempt and finishing a run.
 *
 * <p>Phase advance and result submission share one port rather than two, because they share the primitive that
 * makes either of them safe: lock the run and its attempt together, then read the authoritative clock. Splitting
 * them would mean two implementations of that primitive, and the one used less often would be the one that
 * eventually read the clock in the wrong order.
 */
public interface ExecutionLifecycleRepository {

    /**
     * Locks the run and the attempt it currently names, in whatever state they are in.
     *
     * <p>Unfiltered for the same reason the claim path is unfiltered: filtering would collapse "this worker no
     * longer owns the attempt", "the run was cancelled", and "the run is in a different phase" into one empty
     * answer, and a worker needs to tell them apart — the first two mean stop, the third usually means a
     * duplicate request it can treat as already done.
     *
     * <p>Takes no organization. The caller here is a platform worker rather than a tenant, so there is no
     * principal to scope by; the run's own organization is what the returned record carries, and every write
     * that follows is scoped by it. Accepting one from the caller would be accepting a claim about ownership
     * from the party whose ownership is in question.
     *
     * <p>Empty means no such run.
     */
    Optional<AssignedRun> lockAssignedRun(UUID runId);

    /**
     * The database's own clock, which every window in this package is evaluated against.
     *
     * <p>Read AFTER the lock, never before. Reading it first was a real defect on the capability path: a
     * request that waited on a contended row evaluated its windows against an instant from before the wait.
     */
    Instant currentDatabaseTime();

    /** Advances the run into its next phase and records the attempt's own execution history. One transaction. */
    void persistPhase(
            UUID organizationId,
            TestRun previous,
            TestRun advanced,
            ExecutionAttempt attempt,
            UUID lifecycleEventId,
            String sandboxReference);

    /**
     * Writes the evidence and completes the run in one transaction.
     *
     * <p>Inseparable by construction: a result stored without its run completing would be evidence for
     * something the platform still believes is running, and a run completed without its result is exactly what
     * the database's evidence trigger refuses.
     */
    void persistResultAndComplete(
            UUID organizationId,
            TestRun previous,
            TestRun completed,
            ExecutionAttempt attempt,
            ExecutionResult result,
            UUID lifecycleEventId);

    /**
     * Stops a run because its execution infrastructure failed, and ends the assignment.
     *
     * <p>One transaction, and the fence is part of it: the scheduling-bundle constraint refuses a STOPPING run
     * that still holds a live assignment.
     */
    void stopForInfrastructureFailure(
            UUID organizationId, TestRun run, ExecutionAttempt attempt, String workerId, Instant at);

    /** Whether this assignment has already submitted its result, so a retry is answered rather than duplicated. */
    Optional<ExecutionResult> findResult(UUID organizationId, UUID attemptId, int assignmentEpoch);

    /**
     * The command actually issued to this assignment, if one was.
     *
     * <p>Without this the command identifier on a result submission would be checked only against the
     * document's own copy of it — two fields the same caller supplies, which agree with each other whatever
     * value the caller chose. Comparing against the command the control plane issued is what makes the field
     * mean anything.
     */
    Optional<AuthorizedCommand> findAuthorizedCommand(UUID organizationId, UUID attemptId, int assignmentEpoch);

    /**
     * Runs whose current phase is past its deadline, oldest first.
     *
     * <p>Returns identity only. The reconciler re-reads each run under its own lock before acting, because
     * anything read outside a lock is a hint about the past: by the time the reconciler reaches a run, the
     * worker may have advanced it, and timing out work that just succeeded is worse than timing it out late.
     */
    List<OverdueRun> findOverdue(int limit);

    /**
     * Moves one overdue run into STOPPING, if it is still in the phase it was found in and still overdue.
     *
     * <p>Both conditions are re-checked inside the statement, against the database's own clock. The list this
     * run came from was read without a lock, so by now the worker may have advanced the phase or the deadline
     * may have been re-armed — and timing out work that has just succeeded is a worse failure than timing it
     * out a pass late.
     *
     * @return whether this pass moved the run
     */
    boolean stopOverdue(UUID organizationId, UUID runId, RunLifecycle expected, StopReason reason);

    /**
     * The command this assignment was issued, and the run version it was issued against.
     *
     * <p>The version matters as much as the identity. A command is authorized against the run as it was at
     * that moment — CLAIMED — and by the time a result arrives the run has advanced through four phases. The
     * result names the version it was authorized under, so comparing it to the run's CURRENT version would
     * refuse every honest submission, while comparing it to nothing would accept a result for a different run.
     */
    record AuthorizedCommand(UUID commandId, long runVersion) {}

    /** A run, the organization that owns it, and the attempt it names, read together under one lock. */
    record AssignedRun(UUID organizationId, TestRun run, ExecutionAttempt attempt) {}

    /** Enough to find an overdue run again; never enough to act on it. */
    record OverdueRun(UUID organizationId, UUID runId, RunLifecycle lifecycleState, Instant phaseDeadlineAt) {}
}
