package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The two policies that bound tenant amplification and scheduler retry. Both are pure and server-owned. */
class AdmissionAndBackoffPolicyTest {

    @Test
    void capacityIsExclusiveSoTheCeilingIsTheLastAdmittedRun() {
        AdmissionPolicy policy = new AdmissionPolicy(5, 2);

        assertThat(policy.admitsAnotherActiveRun(4)).isTrue();
        // At five the organization already holds its capacity; the sixth is the one refused.
        assertThat(policy.admitsAnotherActiveRun(5)).isFalse();
        assertThat(policy.admitsAnotherActiveRun(6)).isFalse();
        assertThat(policy.admitsAnotherQueuedRun(1)).isTrue();
        assertThat(policy.admitsAnotherQueuedRun(2)).isFalse();
    }

    @Test
    void anUnusableCapacityIsRejectedAtStartupRatherThanSilentlyApplied() {
        assertThatThrownBy(() -> new AdmissionPolicy(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(200_000, 1)).isInstanceOf(IllegalArgumentException.class);
        // Queued runs are a subset of active runs, so a larger queue ceiling could never be reached and would
        // quietly misrepresent the policy.
        assertThatThrownBy(() -> new AdmissionPolicy(5, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AdmissionPolicy(5, 5)).doesNotThrowAnyException();
    }

    @Test
    void onlyRealFailuresCountTowardQuarantine() {
        // A run held back because its organization's queue is full has nothing wrong with it, so waiting must
        // never accumulate failures or eventually quarantine it.
        assertThat(SchedulingFailure.QUEUE_CAPACITY.counted()).isFalse();
        assertThat(SchedulingFailure.TRANSIENT.counted()).isTrue();
        assertThat(SchedulingFailure.PERMANENT.counted()).isTrue();
    }

    /**
     * The backoff curve itself is applied in SQL, in the same statement that records the attempt, so it is
     * asserted against a real database rather than here. Re-implementing it in Java to unit test it would create
     * a second source of truth that nothing executes.
     */
    @Test
    void anUnusableBackoffIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SchedulingBackoff(0, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SchedulingBackoff(3, Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SchedulingBackoff(3, Duration.ofMinutes(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
