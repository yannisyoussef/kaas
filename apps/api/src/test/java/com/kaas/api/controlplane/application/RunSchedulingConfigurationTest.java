package com.kaas.api.controlplane.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The queue timeout is the only configuration that can move a server-owned deadline, so its bounds are enforced. */
class RunSchedulingConfigurationTest {
    @Test
    void anUnusableQueueTimeoutIsRejectedAtStartupRatherThanAtScheduleTime() {
        assertThatThrownBy(() -> service(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofHours(24).plusNanos(1000)))
                .isInstanceOf(IllegalArgumentException.class);
        // PostgreSQL keeps microseconds; a finer timeout would not survive a round trip intact.
        assertThatThrownBy(() -> service(Duration.ofMinutes(5).plusNanos(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWholeMicrosecondTimeoutWithinTwentyFourHoursIsAccepted() {
        assertThatCode(() -> service(Duration.ofMinutes(5))).doesNotThrowAnyException();
        assertThatCode(() -> service(Duration.ofHours(24))).doesNotThrowAnyException();
        assertThatCode(() -> service(Duration.ofNanos(1000))).doesNotThrowAnyException();
    }

    private static RunSchedulingService service(Duration queueTimeout) {
        return new RunSchedulingService(null, null, queueTimeout);
    }
}
