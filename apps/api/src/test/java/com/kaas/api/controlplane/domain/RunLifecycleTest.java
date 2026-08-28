package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Executable oracle for the documented run state machine. The machine defines the whole lifecycle, but only
 * CREATED to QUEUED is implemented; the later transitions must stay defined-yet-unreachable.
 */
class RunLifecycleTest {
    private static final UUID RUN = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("22222222-0000-4000-8000-000000000002");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void schedulingIsTheOnlyTransitionLeavingCreated() {
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.QUEUED)).isTrue();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.RUNNING)).isFalse();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.CLAIMED)).isFalse();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.CREATED)).isFalse();
    }

    @Test
    void theClaimTransitionRemainsDefinedButIsNotReachableFromAnyImplementedCode() {
        // Defined by the state machine so the contract stays honest about the target design.
        assertThat(RunLifecycle.QUEUED.canTransitionTo(RunLifecycle.CLAIMED)).isTrue();
        // But no domain operation performs it: queued() is the only mutator the aggregate exposes.
        assertThat(TestRun.class.getDeclaredMethods())
                .filteredOn(method -> method.getReturnType() == TestRun.class && !method.isSynthetic())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("created", "queued");
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
