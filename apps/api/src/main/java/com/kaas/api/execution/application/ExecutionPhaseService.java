package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.CancellationStatus;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.ExecutionPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances a run through the phases its assigned worker drives.
 *
 * <p>Every advance revalidates ownership from scratch. That is the point of the design rather than an expense
 * to be optimised away: a worker legitimately owned this attempt when it started provisioning, and by the time
 * it reports RUNNING the run may have been cancelled, its lease may have lapsed, or the attempt may have been
 * reassigned to somebody else at a higher epoch. Holding the previous answer proves nothing about now.
 *
 * <p>Identity and epoch are always checked together. Either alone is insufficient — the worker id says who is
 * asking and the epoch says which assignment they are asking about, and a reassignment to the same worker
 * after a fence produces a request where the id matches and the epoch does not.
 */
@Service
public class ExecutionPhaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionPhaseService.class);

    /**
     * The namespace every phase-driving worker must be in, which the database guard also requires.
     *
     * <p>Checked here as well so a service principal outside it — the scheduler, a reconciler, an operator's
     * own token — is refused with a reason rather than failing later on a constraint whose message describes a
     * column rather than an authority.
     */
    public static final String WORKER_ACTOR_PREFIX = "kaas.worker.";

    private final ExecutionLifecycleRepository lifecycle;

    public ExecutionPhaseService(ExecutionLifecycleRepository lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * READ COMMITTED, matching every other ownership writer.
     *
     * <p>The isolation level is pinned rather than inherited because the correctness argument depends on it:
     * the row lock is what serialises concurrent writers, and the state read after taking it must be the
     * committed present rather than a snapshot from transaction start.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhaseDecision advance(
            String workerId, UUID runId, UUID attemptId, int assignmentEpoch,
            ExecutionPhase phase, String sandboxReference) {

        if (workerId == null || !workerId.startsWith(WORKER_ACTOR_PREFIX)) {
            // A platform principal that is not a worker cannot drive an execution phase, whatever else it is
            // entitled to do. The database refuses this too; refusing here names the actual reason.
            return PhaseDecision.refused(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }

        Optional<ExecutionLifecycleRepository.AssignedRun> locked = lifecycle.lockAssignedRun(runId);
        if (locked.isEmpty()) {
            return PhaseDecision.refused(ExecutionDenial.ASSIGNMENT_STALE);
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();

        // AFTER the lock. Reading the clock before waiting on a contended row evaluates every window below
        // against an instant from before the wait.
        Instant now = lifecycle.currentDatabaseTime();

        // CLAMPED UP to the run's own last update, exactly as the claim and scheduling paths clamp.
        //
        // A CLAIMED run's updated_at is >= the database clock by construction: RunSchedulingService and
        // RunClaimService each take max(dbTime, previous updated_at), so an API host that leads the database
        // carries that lead forward into the row. This was the one writer that did not clamp, and the aggregate
        // refuses a transition that precedes its own last update — so a few hundred milliseconds of ordinary
        // drift turned the first phase advance into a 500, the runner abandoned, and the run was stranded in
        // CLAIMED with no phase deadline for any reconciler to find.
        Instant at = now.isBefore(run.updatedAt()) ? run.updatedAt() : now;

        Optional<ExecutionDenial> notThisAssignment =
                proveAssignment(attempt, workerId, attemptId, assignmentEpoch);
        if (notThisAssignment.isPresent()) {
            return PhaseDecision.refused(notThisAssignment.orElseThrow());
        }
        // A cancellation in flight outranks the advance: letting a worker move into the next phase would
        // extend the life of work somebody has already asked to stop, and each phase arms a fresh deadline —
        // so the run would take LONGER to stop the more often its worker reported progress.
        Optional<ExecutionDenial> cannotAct = checkStillLive(run, attempt, now);
        if (cannotAct.isPresent()) {
            return PhaseDecision.refused(cannotAct.orElseThrow());
        }

        if (run.lifecycleState() != phase.from()) {
            // Most often a retry of an advance that already succeeded. The run's CURRENT state travels with the
            // refusal so the worker can tell those apart: if the run is already in the phase it asked for, its
            // own earlier request landed and the response was lost. Without the state, "already done" and
            // "wrong order" are the same answer and a worker must treat both as fatal.
            //
            // Safe to disclose: this is reached only after the caller has proved it holds this assignment.
            return PhaseDecision.refusedInPhase(ExecutionDenial.PHASE_NOT_ENTERABLE, run);
        }

        // The deadline is measured from the clamped instant, so it is always strictly after it — the guard
        // requires phase_deadline_at > updated_at, and measuring from an earlier instant could violate that.
        if (phase == ExecutionPhase.PROVISIONING && (sandboxReference == null || sandboxReference.isBlank())) {
            // The database requires it for this phase and only this phase, so a missing one used to arrive as a
            // trigger violation: an opaque 409 CONFLICT carrying no denial code, with the run left in CLAIMED.
            // Refusing here names the actual problem.
            return PhaseDecision.refused(ExecutionDenial.PHASE_NOT_ENTERABLE);
        }

        Instant deadline = at.plus(phase.budget());
        TestRun advanced = switch (phase) {
            case PROVISIONING -> run.provisioning(at, deadline);
            case RUNNING -> run.running(at, deadline);
            case COLLECTING_RESULTS -> run.collectingResults(at, deadline);
            case PROCESSING_RESULTS -> run.processingResults(at, deadline);
        };

        lifecycle.persistPhase(organizationId, run, advanced, attempt, UUID.randomUUID(), sandboxReference);
        LOGGER.info(
                "run {} entered {} under assignment epoch {} with a deadline at {}",
                runId, phase, assignmentEpoch, deadline);
        return PhaseDecision.advanced(advanced);
    }

    /**
     * Whether this caller still owns this attempt, right now.
     *
     * <p>Shared with the result path deliberately. Two copies of this reasoning would drift, and the copy used
     * on the less-travelled path would be the one that stopped checking the epoch.
     */
    /**
     * Reports that this assignment's infrastructure failed, stopping the run.
     *
     * <p>This route did not exist. The runner detected a sandbox that would not start, returned the fact to its
     * own caller, and told the control plane nothing — so the run sat in its phase until the deadline expired
     * and was recorded as having TIMED OUT. That is a false cause written into the one place operators go to
     * find out what broke, for a failure the platform observed within seconds and then discarded.
     *
     * <p>It stops the run rather than completing it: an infrastructure failure produces no test outcome,
     * because nothing ran to completion.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ExecutionDenial> reportInfrastructureFailure(
            String workerId, UUID runId, UUID attemptId, int assignmentEpoch, String detail) {

        Optional<ExecutionLifecycleRepository.AssignedRun> locked = lifecycle.lockAssignedRun(runId);
        if (locked.isEmpty()) {
            return Optional.of(ExecutionDenial.ASSIGNMENT_STALE);
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();
        Instant now = lifecycle.currentDatabaseTime();

        Optional<ExecutionDenial> notThisAssignment =
                proveAssignment(attempt, workerId, attemptId, assignmentEpoch);
        if (notThisAssignment.isPresent()) {
            return notThisAssignment;
        }
        Optional<ExecutionDenial> cannotAct = checkStillLive(run, attempt, now);
        if (cannotAct.isPresent()) {
            // Already stopping, already fenced, or already lapsed. All three mean somebody else has ended this
            // run, and a late failure report must not overwrite the reason they recorded.
            return cannotAct;
        }
        if (!STOPPABLE.contains(run.lifecycleState())) {
            return Optional.of(ExecutionDenial.PHASE_NOT_ENTERABLE);
        }

        lifecycle.stopForInfrastructureFailure(organizationId, run, attempt, workerId, now);
        LOGGER.atWarn()
                .addKeyValue("event", "EXECUTION_INFRASTRUCTURE_FAILED")
                .addKeyValue("runId", runId)
                .addKeyValue("attemptId", attemptId)
                .addKeyValue("phase", run.lifecycleState())
                // The detail is the runner's own description of its sandbox, not tenant content.
                .addKeyValue("detail", detail)
                .log("A worker reported that its execution infrastructure failed");
        return Optional.empty();
    }

    /** The phases a worker can report an infrastructure failure from. Matches V10's guard arm exactly. */
    private static final java.util.Set<RunLifecycle> STOPPABLE = java.util.EnumSet.of(
            RunLifecycle.PROVISIONING,
            RunLifecycle.RUNNING,
            RunLifecycle.COLLECTING_RESULTS,
            RunLifecycle.PROCESSING_RESULTS);

    /**
     * Whether this caller IS the assignment it claims to be.
     *
     * <p>Split from liveness deliberately. Both the phase path and the result path need to prove identity
     * first and then ask a question of their own before checking whether the assignment is still live — and
     * the questions differ. Merging the two halves forced one fixed order on both callers, and that order made
     * a branch in each of them unreachable.
     *
     * <p>Proving identity is what makes it safe for a caller to be told anything specific afterwards.
     */
    static Optional<ExecutionDenial> proveAssignment(
            ExecutionAttempt attempt, String workerId, UUID attemptId, int assignmentEpoch) {

        // The run must still name this attempt. The repository reads the attempt through the run's own
        // current_attempt_id, so an attempt the run has moved on from is not returned at all and this compares
        // what the caller asked about against what the run currently points at.
        if (!attempt.attemptId().equals(attemptId)) {
            return Optional.of(ExecutionDenial.ASSIGNMENT_STALE);
        }
        var assignment = attempt.assignment();
        if (assignment == null) {
            return Optional.of(ExecutionDenial.ASSIGNMENT_STALE);
        }
        // Identity and epoch together, never either alone. A reassignment to the same worker after a fence
        // produces a request where the identity matches and the epoch does not.
        //
        // ACQUISITION is required. Before it, the stored worker id is the dispatch consumer's own configured
        // constant — one value for every run in the deployment — so comparing against it would admit any worker
        // in the fleet and the epoch would be fencing nothing. A worker reaches acquisition by authorizing or
        // heartbeating, and the execution loop authorizes first.
        if (!assignment.acquired()
                || !assignment.workerId().equals(workerId)
                || assignment.epoch() != assignmentEpoch) {
            return Optional.of(ExecutionDenial.ASSIGNMENT_STALE);
        }
        // Deliberately NOT checking fenced() here, even though isHeldBy does. Fencing is a liveness fact, and
        // moving it into the identity proof re-creates the bug this split exists to fix: every stopped or
        // completed run is fenced, so a fence check ordered first answers "somebody else has this" to a worker
        // whose run was merely cancelled, or whose result was already accepted.
        return Optional.empty();
    }

    /**
     * Whether the assignment this caller has proved it holds may still act.
     *
     * <p>Only called after {@link #proveAssignment}, so everything reported here is being reported to the
     * assignment it is about.
     */
    static Optional<ExecutionDenial> checkStillLive(TestRun run, ExecutionAttempt attempt, Instant now) {
        // WHY THE RUN'S STOP STATE IS CHECKED BEFORE THE FENCE.
        //
        // Both paths into STOPPING — a tenant cancellation and a phase-deadline expiry — fence the assignment
        // in the same transaction that stops the run. So a stopped run's worker is always also fenced, and
        // checking the fence first told it ASSIGNMENT_STALE: "somebody else has this now". That is false and
        // actively misleading. Nobody else has it; the run was stopped.
        //
        // A worker that believes it was superseded reports something different to its operator than one that
        // knows the run was cancelled, and only the second is what happened. Ordering it this way also makes
        // the branch reachable at all: while the fence answered first, deleting it changed no observable
        // behaviour and no test could tell.
        if (run.cancellationStatus() != CancellationStatus.NOT_REQUESTED || run.stopReason() != null) {
            return Optional.of(ExecutionDenial.RUN_STOPPING);
        }
        var assignment = attempt.assignment();
        if (assignment.fenced()) {
            return Optional.of(ExecutionDenial.ASSIGNMENT_STALE);
        }
        // Expiry is a separate fact from fencing: a lease can lapse without anybody having fenced it yet, and
        // the reconciler that would do so runs on its own schedule. Checking only the fence flag would let a
        // worker keep driving a run for as long as the reconciler was behind.
        if (!assignment.leaseExpiresAt().isAfter(now)) {
            return Optional.of(ExecutionDenial.LEASE_EXPIRED);
        }
        return Optional.empty();
    }

    /** Advanced, or refused with the reason and — where it is safe and useful — where the run actually is. */
    public record PhaseDecision(
            Optional<TestRun> run, Optional<ExecutionDenial> denial, Optional<TestRun> currentRun) {

        public static PhaseDecision advanced(TestRun run) {
            return new PhaseDecision(Optional.of(run), Optional.empty(), Optional.empty());
        }

        public static PhaseDecision refused(ExecutionDenial denial) {
            return new PhaseDecision(Optional.empty(), Optional.of(denial), Optional.empty());
        }

        public static PhaseDecision refusedInPhase(ExecutionDenial denial, TestRun currentRun) {
            return new PhaseDecision(Optional.empty(), Optional.of(denial), Optional.of(currentRun));
        }
    }
}
