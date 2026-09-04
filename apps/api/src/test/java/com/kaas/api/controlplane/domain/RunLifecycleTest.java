package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Executable oracle for the documented run state machine. The machine defines the whole lifecycle, but only
 * scheduling and early termination are implemented; the transitions a worker would drive must stay
 * defined-yet-unreachable.
 */
class RunLifecycleTest {
    private static final UUID RUN = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("22222222-0000-4000-8000-000000000002");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final java.time.Duration LEASE = java.time.Duration.ofSeconds(30);

    @Test
    void schedulingAndEarlyTerminationAreTheOnlyTransitionsLeavingCreated() {
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.QUEUED)).isTrue();
        // A run nobody has taken can end without ever running. That is a real transition, not a shortcut.
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.COMPLETED)).isTrue();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.RUNNING)).isFalse();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.CLAIMED)).isFalse();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.CREATED)).isFalse();
    }

    @Test
    void everyMutatorIsNamedAndOwnedWorkHasExactlyTwoWaysOut() {
        // This guard caught the slice that implemented execution, which is what it was for. The list is not
        // merely widened here: what it protects is that adding a mutator — especially one that reaches a
        // terminal state — cannot happen without someone editing this line and thinking about it.
        assertThat(TestRun.class.getDeclaredMethods())
                .filteredOn(method -> method.getReturnType() == TestRun.class && !method.isSynthetic())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder(
                        "created", "queued", "claimed", "provisioning", "running", "collectingResults",
                        "processingResults", "completedWithResult", "stopping", "settled", "cancelled",
                        "expired", "terminated", "withPhase");

        // Owned work now has exactly two ways out, and both are deliberate.
        //
        // Through PROCESSING_RESULTS, carrying the evidence that justifies its outcome — the only path that can
        // report a test outcome at all. Or through STOPPING, which is how every interruption ends: cancellation,
        // a lost lease, and each of the four deadlines. What no owned state may do is reach COMPLETED directly,
        // because that would be a run declaring itself finished without either evidence or fencing.
        assertThat(RunLifecycle.PROCESSING_RESULTS.canTransitionTo(RunLifecycle.COMPLETED)).isTrue();
        assertThat(RunLifecycle.STOPPING.canTransitionTo(RunLifecycle.COMPLETED)).isTrue();

        // EVERY owned state needs a way to be stopped, PROCESSING_RESULTS included.
        //
        // An earlier version of this loop listed only the first four, and that omission hid a real trap state:
        // PROCESSING_RESULTS could reach COMPLETED and nothing else, so a worker that died mid-submission left
        // the run holding admission capacity with no reconciler able to reclaim it. The list below is therefore
        // derived rather than written out — every non-terminal state a worker can own is included by
        // construction, so adding a phase cannot quietly skip this check the way adding one skipped it before.
        var owned = java.util.EnumSet.of(
                RunLifecycle.CLAIMED, RunLifecycle.PROVISIONING, RunLifecycle.RUNNING,
                RunLifecycle.COLLECTING_RESULTS, RunLifecycle.PROCESSING_RESULTS);
        assertThat(owned)
                .as("every non-terminal state after CLAIMED is owned work and must be listed here")
                .containsExactlyInAnyOrderElementsOf(java.util.EnumSet.allOf(RunLifecycle.class).stream()
                        .filter(state -> !state.terminal())
                        .filter(state -> state != RunLifecycle.CREATED && state != RunLifecycle.QUEUED
                                && state != RunLifecycle.STOPPING)
                        .toList());
        for (RunLifecycle state : owned) {
            assertThat(state.canTransitionTo(RunLifecycle.STOPPING))
                    .as("%s must be stoppable, or it is a state nothing can reclaim", state)
                    .isTrue();
        }

        // Reaching COMPLETED directly is a run declaring itself finished without either evidence or fencing.
        // PROCESSING_RESULTS is the one exception, because submitting the evidence is what that transition is.
        for (RunLifecycle state : java.util.EnumSet.of(
                RunLifecycle.CLAIMED, RunLifecycle.PROVISIONING, RunLifecycle.RUNNING,
                RunLifecycle.COLLECTING_RESULTS)) {
            assertThat(state.canTransitionTo(RunLifecycle.COMPLETED))
                    .as("%s must not reach COMPLETED without evidence or fencing", state)
                    .isFalse();
        }

        // RUNNING reaches PROCESSING_RESULTS only through COLLECTING_RESULTS. No code drives a shortcut, and
        // an edge no code drives is an edge no test covers.
        assertThat(RunLifecycle.RUNNING.canTransitionTo(RunLifecycle.PROCESSING_RESULTS)).isFalse();
    }

    @Test
    void terminationIsRefusedFromEveryPhaseAWorkerWouldOwn() {
        // The guard is in the aggregate as well as in the database, because a phase past QUEUED belongs to a
        // worker and stopping it needs a protocol this slice deliberately does not invent.
        TestRun queued = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(301));
        assertThat(queued.cancelled(NOW.plusSeconds(2), NOW.plusSeconds(3)).lifecycleState())
                .isEqualTo(RunLifecycle.COMPLETED);
        // A run that has already been asked to stop cannot be asked again.
        assertThatThrownBy(() -> queued.cancelled(NOW.plusSeconds(2), NOW.plusSeconds(3))
                        .cancelled(NOW.plusSeconds(4), NOW.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
        // An acknowledgement can never precede the request that caused it.
        assertThatThrownBy(() -> queued.cancelled(NOW.plusSeconds(5), NOW.plusSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
        // Expiry needs a deadline, and a CREATED run has none.
        assertThatThrownBy(() -> TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW).expired(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        // And it cannot happen before the deadline it is enforcing.
        assertThatThrownBy(() -> queued.expired(NOW.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        // A run's own audit stamps only move forward. The service clamps for this, but an aggregate that relies
        // on its caller to hold an invariant surfaces the violation as a trigger exception rather than a domain
        // error the moment that clamp is refactored away.
        assertThatThrownBy(() -> queued.cancelled(NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot end before its own last update");

        // The phases a worker owns are refused by the aggregate itself, not only by the repository predicate that
        // happens to filter them out first. Reaching this needs the canonical constructor, because no mutator can
        // put a run into STOPPING — which is exactly why the branch would otherwise never be executed by a test.
        TestRun stopping = new TestRun(
                RUN, PROJECT, 5, RunLifecycle.STOPPING, CancellationStatus.NOT_REQUESTED, null, null,
                QualityGateStatus.NOT_EVALUATED, null, null, StopReason.LEASE_LOST, DIGEST, NOW.plusSeconds(1),
                NOW.plusSeconds(301), null, null, null, "creator", NOW, NOW.plusSeconds(2), null, null);
        assertThat(stopping.lifecycleState().canTransitionTo(RunLifecycle.COMPLETED)).isTrue();
        assertThatThrownBy(() -> stopping.cancelled(NOW.plusSeconds(3), NOW.plusSeconds(4)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only unowned work can be terminated early.");
    }

    @Test
    void aQueueTimeoutIsNeverReportedAsACancellation() {
        TestRun expired = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(301))
                .expired(NOW.plusSeconds(301));

        assertThat(expired.terminationReason()).isEqualTo(TerminationReason.QUEUE_DEADLINE);
        assertThat(expired.terminationPhase()).isEqualTo(TerminationPhase.QUEUE);
        assertThat(expired.infrastructureOutcome()).isEqualTo(InfrastructureOutcome.TIMED_OUT);
        // Nobody asked for it, so nothing about it may claim anybody did.
        assertThat(expired.cancellationStatus()).isEqualTo(CancellationStatus.NOT_REQUESTED);
        assertThat(expired.cancellationRequestedAt()).isNull();
        assertThat(expired.cancellationAcknowledgedAt()).isNull();
        // Nothing ran, so there is no test result and nothing for a quality gate to judge.
        assertThat(expired.testOutcome()).isEqualTo(TestOutcome.NOT_AVAILABLE);
        assertThat(expired.qualityGateStatus()).isEqualTo(QualityGateStatus.NOT_EVALUATED);
        // The scheduling history it is ending is preserved, not rewritten.
        assertThat(expired.queueStartedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(expired.queueDeadlineAt()).isEqualTo(NOW.plusSeconds(301));
        assertThat(expired.runVersion()).isEqualTo(3);
    }

    @Test
    void queueingIncrementsTheSemanticVersionAndStartsServerOwnedQueueTiming() {
        TestRun created = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW);
        assertThat(created.runVersion()).isEqualTo(1);
        assertThat(created.lifecycleState()).isEqualTo(RunLifecycle.CREATED);
        assertThat(created.queueStartedAt()).isNull();
        assertThat(created.queueDeadlineAt()).isNull();

        Instant startedAt = NOW.plusSeconds(30);
        Instant deadlineAt = startedAt.plusSeconds(300);
        TestRun queued = created.queued(startedAt, deadlineAt);

        assertThat(queued.lifecycleState()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(queued.runVersion()).isEqualTo(2);
        assertThat(queued.queueStartedAt()).isEqualTo(startedAt);
        assertThat(queued.queueDeadlineAt()).isEqualTo(deadlineAt);
        assertThat(queued.updatedAt()).isEqualTo(startedAt);
        // Everything else is carried through untouched.
        assertThat(queued.runId()).isEqualTo(created.runId());
        assertThat(queued.projectId()).isEqualTo(created.projectId());
        assertThat(queued.snapshotDigest()).isEqualTo(created.snapshotDigest());
        assertThat(queued.createdBy()).isEqualTo(created.createdBy());
        assertThat(queued.createdAt()).isEqualTo(created.createdAt());
        assertThat(queued.cancellationStatus()).isEqualTo(created.cancellationStatus());
        assertThat(queued.qualityGateStatus()).isEqualTo(created.qualityGateStatus());
    }

    @Test
    void anAlreadyQueuedRunCannotBeQueuedAgainAndQueueTimingMustBeCoherent() {
        TestRun queued = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(300));

        assertThatThrownBy(() -> queued.queued(NOW.plusSeconds(2), NOW.plusSeconds(400)))
                .isInstanceOf(IllegalStateException.class);

        TestRun created = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW);
        assertThatThrownBy(() -> created.queued(null, NOW.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> created.queued(NOW, null)).isInstanceOf(IllegalArgumentException.class);
        // A deadline must strictly follow the moment queue timing started.
        assertThatThrownBy(() -> created.queued(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> created.queued(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theInitialAttemptIsBornUnassignedAndCannotCarryANonInitialNumber() {
        UUID attemptId = UUID.randomUUID();
        ExecutionAttempt attempt = ExecutionAttempt.waitingForClaim(attemptId, RUN, NOW);
        assertThat(attempt.state()).isEqualTo(ExecutionAttemptState.WAITING_FOR_CLAIM);
        assertThat(attempt.assignment()).isNull();
        // Infrastructure retry is out of scope for the MVP, so attempt two cannot be modelled yet.
        assertThatThrownBy(() -> new ExecutionAttempt(
                        attemptId, RUN, 2, ExecutionAttemptState.WAITING_FOR_CLAIM, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        // The state and the assignment are two views of one fact and may never disagree, in either direction.
        assertThatThrownBy(() -> new ExecutionAttempt(
                        attemptId, RUN, 1, ExecutionAttemptState.CLAIMED, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionAttempt(
                        attemptId, RUN, 1, ExecutionAttemptState.WAITING_FOR_CLAIM, NOW,
                        WorkerAssignment.claim("worker-1", NOW, LEASE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionAttempt(
                        attemptId, RUN, 1, ExecutionAttemptState.FENCED, NOW,
                        WorkerAssignment.claim("worker-1", NOW, LEASE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAssignmentIsHeldByExactlyOneWorkerUnderExactlyOneEpoch() {
        ExecutionAttempt claimed =
                ExecutionAttempt.waitingForClaim(UUID.randomUUID(), RUN, NOW).claimedBy("worker-1", NOW, LEASE);

        assertThat(claimed.assignment().epoch()).isEqualTo(WorkerAssignment.FIRST_EPOCH);

        // A CLAIMED-but-unacquired assignment is held by NOBODY. The worker id written at claim time comes from
        // the dispatch consumer's configuration and is one constant for the whole deployment, so treating it as
        // an owner meant every worker in the fleet matched every run — and the epoch, which exists to fence one
        // holder from another, fenced nothing.
        assertThat(claimed.assignment().acquired()).isFalse();
        assertThat(claimed.assignment().isHeldBy("worker-1", 1)).isFalse();

        ExecutionAttempt acquired = claimed.acquiredBy("worker-1", NOW);
        assertThat(acquired.assignment().isHeldBy("worker-1", 1)).isTrue();
        // Identity plus epoch, never one or the other. An epoch alone would let any worker act as the owner, and
        // an identity alone would let a restarted worker act under an assignment it has already lost.
        assertThat(acquired.assignment().isHeldBy("worker-2", 1)).isFalse();
        // Acquisition is write-once: a second worker cannot take an assignment somebody already holds.
        assertThatThrownBy(() -> acquired.acquiredBy("worker-2", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already held");
        assertThat(claimed.assignment().isHeldBy("worker-1", 2)).isFalse();

        // A renewal is the same assignment continuing, so it may move only the lease window.
        ExecutionAttempt renewed = claimed.heartbeat(NOW.plusSeconds(5), LEASE);
        assertThat(renewed.assignment().epoch()).isEqualTo(1);
        assertThat(renewed.assignment().workerId()).isEqualTo("worker-1");
        assertThat(renewed.assignment().leaseStartedAt()).isEqualTo(NOW);
        assertThat(renewed.assignment().leaseExpiresAt()).isEqualTo(NOW.plusSeconds(35));

        // An expired lease is fenced, never renewed: taking ownership back by being late rather than correct is
        // exactly what fencing exists to prevent.
        assertThatThrownBy(() -> claimed.heartbeat(NOW.plusSeconds(31), LEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired lease cannot be renewed");
        // And a heartbeat cannot run backwards.
        assertThatThrownBy(() -> renewed.heartbeat(NOW.plusSeconds(1), LEASE))
                .isInstanceOf(IllegalArgumentException.class);

        ExecutionAttempt fenced = renewed.fenced(NOW.plusSeconds(40));
        assertThat(fenced.state()).isEqualTo(ExecutionAttemptState.FENCED);
        // The epoch survives fencing, because a later assignment has to be strictly greater than it.
        assertThat(fenced.assignment().epoch()).isEqualTo(1);
        assertThat(fenced.assignment().isHeldBy("worker-1", 1)).isFalse();
        assertThatThrownBy(() -> fenced.heartbeat(NOW.plusSeconds(41), LEASE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fenced.fenced(NOW.plusSeconds(42))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimingAndStoppingAreTheOnlyWaysIntoAndOutOfOwnership() {
        TestRun queued = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(301));

        TestRun claimed = queued.claimed(NOW.plusSeconds(2));
        assertThat(claimed.lifecycleState()).isEqualTo(RunLifecycle.CLAIMED);
        assertThat(claimed.runVersion()).isEqualTo(3);
        // A claim takes ownership of the run; it decides nothing about its outcome.
        assertThat(claimed.testOutcome()).isNull();
        assertThat(claimed.infrastructureOutcome()).isNull();
        assertThat(claimed.completedAt()).isNull();
        // Claiming after the queue deadline would leave the reaper and the consumer each believing they hold it.
        assertThatThrownBy(() -> queued.claimed(NOW.plusSeconds(302)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after its queue deadline");
        // And a run already asked to stop cannot be taken.
        assertThatThrownBy(() -> queued.stopping(StopReason.USER_REQUESTED, NOW, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);

        // Owned work cannot finish in one step: the assignment has to be fenced first.
        assertThatThrownBy(() -> claimed.cancelled(NOW.plusSeconds(3), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);

        TestRun stopping = claimed.stopping(StopReason.USER_REQUESTED, NOW.plusSeconds(3), NOW.plusSeconds(3));
        assertThat(stopping.lifecycleState()).isEqualTo(RunLifecycle.STOPPING);
        assertThat(stopping.cancellationStatus()).isEqualTo(CancellationStatus.REQUESTED);
        // No outcome yet: the run has not finished, and writing one now would let a crash leave a run claiming a
        // result it never reached.
        assertThat(stopping.infrastructureOutcome()).isNull();
        assertThat(stopping.completedAt()).isNull();

        TestRun settled = stopping.settled(NOW.plusSeconds(4));
        assertThat(settled.lifecycleState()).isEqualTo(RunLifecycle.COMPLETED);
        assertThat(settled.runVersion()).isEqualTo(5);
        assertThat(settled.infrastructureOutcome()).isEqualTo(InfrastructureOutcome.CANCELLED);
        assertThat(settled.terminationReason()).isEqualTo(TerminationReason.USER_REQUESTED);
        assertThat(settled.cancellationStatus()).isEqualTo(CancellationStatus.ACKNOWLEDGED);
        assertThat(settled.testOutcome()).isEqualTo(TestOutcome.NOT_AVAILABLE);
        assertThat(settled.qualityGateStatus()).isEqualTo(QualityGateStatus.NOT_EVALUATED);
    }

    @Test
    void aLostLeaseIsAnInfrastructureFailureAndNeverACancellationOrATimeout() {
        TestRun settled = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(301))
                .claimed(NOW.plusSeconds(2))
                .stopping(StopReason.LEASE_LOST, null, NOW.plusSeconds(40))
                .settled(NOW.plusSeconds(41));

        assertThat(settled.terminationReason()).isEqualTo(TerminationReason.LEASE_LOST);
        assertThat(settled.terminationPhase()).isEqualTo(TerminationPhase.CLAIM);
        assertThat(settled.infrastructureOutcome()).isEqualTo(InfrastructureOutcome.FAILED);
        // Nobody asked, so nothing about it may claim anybody did — and it is not a timeout either, because the
        // platform did reach this run; it lost the worker that had it.
        assertThat(settled.cancellationStatus()).isEqualTo(CancellationStatus.NOT_REQUESTED);
        assertThat(settled.cancellationRequestedAt()).isNull();
        assertThat(settled.testOutcome()).isEqualTo(TestOutcome.NOT_AVAILABLE);

        // The two stop reasons each require the evidence that belongs to them.
        TestRun claimed = TestRun.created(RUN, PROJECT, DIGEST, "creator", NOW)
                .queued(NOW.plusSeconds(1), NOW.plusSeconds(301))
                .claimed(NOW.plusSeconds(2));
        assertThatThrownBy(() -> claimed.stopping(StopReason.LEASE_LOST, NOW.plusSeconds(3), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed.stopping(StopReason.USER_REQUESTED, null, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
