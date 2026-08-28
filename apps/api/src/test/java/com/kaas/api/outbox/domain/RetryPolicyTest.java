package com.kaas.api.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Publication retry is bounded and deterministic; the database, not this policy, remembers when to retry. */
class RetryPolicyTest {
    private static final RetryPolicy POLICY =
            new RetryPolicy(5, Duration.ofSeconds(5), Duration.ofMinutes(1));

    @Test
    void backoffDoublesPerAttemptAndIsCapped() {
        assertThat(POLICY.backoffAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(POLICY.backoffAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.backoffAfter(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(POLICY.backoffAfter(4)).isEqualTo(Duration.ofSeconds(40));
        // Capped rather than growing without bound.
        assertThat(POLICY.backoffAfter(5)).isEqualTo(Duration.ofMinutes(1));
        assertThat(POLICY.backoffAfter(30)).isEqualTo(Duration.ofMinutes(1));
        // No jitter: the same attempt always yields the same delay, which is what keeps failure tests reproducible.
        assertThat(POLICY.backoffAfter(3)).isEqualTo(POLICY.backoffAfter(3));
    }

    @Test
    void theAttemptBudgetIsBoundedSoAPoisonMessageCannotRetryForever() {
        assertThat(POLICY.exhausted(4)).isFalse();
        assertThat(POLICY.exhausted(5)).isTrue();
        assertThat(POLICY.exhausted(6)).isTrue();
    }

    @Test
    void anUnusablePolicyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(5), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(101, Duration.ofSeconds(5), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(5, Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        // A maximum below the base would silently shrink the first backoff.
        assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofMinutes(1), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPublishOutcomeMustCarryExactlyOneBoundedFailureCode() {
        assertThat(PublishOutcome.confirmed().failureCode()).isNull();
        assertThat(PublishOutcome.transientFailure(FailureCode.CONFIRM_TIMEOUT).status())
                .isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThatThrownBy(() -> new PublishOutcome(PublishStatus.CONFIRMED, FailureCode.UNROUTABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishOutcome(PublishStatus.TRANSIENT_FAILURE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyExecutionDispatchIsPublishableToday() {
        assertThat(MessageType.isPublishable("EXECUTION_DISPATCH")).isTrue();
        // Declared so the outbox demonstrably generalizes, but it has no publisher and must not be guessed at.
        assertThat(MessageType.isPublishable(MessageType.RUN_STATE_CHANGED.name())).isFalse();
        assertThat(MessageType.isPublishable("ARBITRARY")).isFalse();
    }
}
