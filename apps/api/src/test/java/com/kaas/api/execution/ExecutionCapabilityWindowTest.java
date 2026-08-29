package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionCapability;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * When a capability is inside its window, and every way it can be outside one.
 *
 * <p>Split out as a unit test because each of these is a decision about one record, and because the redemption
 * ceiling had no purposeful test at all — a mutation removing it was caught only by an unrelated integration
 * test that happened to notice. A control covered by accident is a control that stops being covered the moment
 * the accident is refactored away.
 */
class ExecutionCapabilityWindowTest {
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    void aFreshUnredeemedCapabilityIsInsideItsWindow() {
        // Without this the tests below would be satisfied by a window that was never open.
        assertThat(capability(NOW.plusSeconds(300), 0, null).withinWindow(NOW)).isTrue();
    }

    @Test
    void anExpiredCapabilityIsOutsideIt() {
        assertThat(capability(NOW.minusSeconds(1), 0, null).withinWindow(NOW)).isFalse();
    }

    @Test
    void aRevokedCapabilityIsOutsideItEvenWithTimeRemaining() {
        // Rotation revokes the previous capability, and revocation has to bite on its own rather than relying on
        // the redemption statement's SQL predicate to refuse it.
        assertThat(capability(NOW.plusSeconds(300), 0, NOW.minusSeconds(1)).withinWindow(NOW))
                .isFalse();
    }

    @Test
    void aSpentCapabilityIsOutsideItEvenWithTimeRemaining() {
        // The ceiling bounds amplification rather than being the security control, and it still has to hold: a
        // capability is a retry allowance, not an unlimited download licence.
        // Not revoked, not expired. The ceiling has to be the only reason this is refused, or the assertion is
        // satisfied by revocation and the ceiling stays untested — which is exactly how it got here.
        assertThat(capability(NOW.plusSeconds(300), ExecutionCapability.MAX_REDEMPTIONS, null).withinWindow(NOW))
                .isFalse();
        assertThat(capability(NOW.plusSeconds(300), ExecutionCapability.MAX_REDEMPTIONS - 1, null)
                        .withinWindow(NOW))
                .isTrue();
    }

    private static ExecutionCapability capability(Instant expiresAt, int redemptions, Instant revokedAt) {
        return new ExecutionCapability(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CapabilityType.SOURCE,
                "a".repeat(64),
                NOW.minusSeconds(60),
                expiresAt,
                redemptions,
                redemptions == 0 ? null : NOW.minusSeconds(30),
                revokedAt,
                List.of());
    }
}
