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
    void theClaimTransitionRemainsDefinedButIsNotReachableFromAnyImplementedCode() {
        // Defined by the state machine so the contract stays honest about the target design.
        assertThat(RunLifecycle.QUEUED.canTransitionTo(RunLifecycle.CLAIMED)).isTrue();
        // But no domain operation performs it. Every mutator the aggregate exposes is named here, so adding one
        // that reaches a worker-owned phase cannot happen quietly.
        assertThat(TestRun.class.getDeclaredMethods())
                .filteredOn(method -> method.getReturnType() == TestRun.class && !method.isSynthetic())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("created", "queued", "cancelled", "expired", "terminated");
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
                QualityGateStatus.NOT_EVALUATED, null, null, DIGEST, NOW.plusSeconds(1), NOW.plusSeconds(301),
                null, null, null, "creator", NOW, NOW.plusSeconds(2));
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
    void theInitialAttemptCannotCarryAnAssignmentOrANonInitialNumber() {
        UUID attemptId = UUID.randomUUID();
        assertThat(new ExecutionAttempt(attemptId, RUN, 1, ExecutionAttemptState.WAITING_FOR_CLAIM, NOW).state())
                .isEqualTo(ExecutionAttemptState.WAITING_FOR_CLAIM);
        // Infrastructure retry is out of scope for the MVP, so attempt two cannot be modelled yet.
        assertThatThrownBy(() ->
                        new ExecutionAttempt(attemptId, RUN, 2, ExecutionAttemptState.WAITING_FOR_CLAIM, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        // There is no claimed or assigned state to reach before a worker exists.
        assertThat(ExecutionAttemptState.values()).containsExactly(ExecutionAttemptState.WAITING_FOR_CLAIM);
    }
}
