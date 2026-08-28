package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionDispatchPolicyTest {
    private static final UUID MESSAGE = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DISPATCH = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ORGANIZATION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PROJECT = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID RUN = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID ATTEMPT = UUID.fromString("60000000-0000-4000-8000-000000000006");
    private static final Instant START = Instant.parse("2026-08-28T12:00:00.123456Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-28T12:05:00.123456Z");
    private static final String SNAPSHOT = "sha256:" + "7".repeat(64);

    @Test
    void canonicalDispatchHasAGoldenDigestAndEverySemanticGroupIsBound() {
        ExecutionDispatch baseline = dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 2, ATTEMPT, SNAPSHOT, DEADLINE);

        assertThat(baseline.payloadDigest())
                .isEqualTo("sha256:c30ae0b3f489b4922b4f32bd12d2ef99325821c7a61e7121e1da640d1c3d6acf");
        assertThat(List.of(
                        dispatch(UUID.randomUUID(), DISPATCH, ORGANIZATION, PROJECT, RUN, 2, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, UUID.randomUUID(), ORGANIZATION, PROJECT, RUN, 2, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, UUID.randomUUID(), PROJECT, RUN, 2, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, UUID.randomUUID(), RUN, 2, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, UUID.randomUUID(), 2, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 3, ATTEMPT, SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 2, UUID.randomUUID(), SNAPSHOT, DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 2, ATTEMPT,
                                "sha256:" + "8".repeat(64), DEADLINE),
                        dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 2, ATTEMPT, SNAPSHOT,
                                DEADLINE.plusSeconds(1))))
                .allSatisfy(changed -> assertThat(changed.payloadDigest()).isNotEqualTo(baseline.payloadDigest()));
    }

    @Test
    void timestampsAreDigestedAtAFixedPrecisionSoOtherLanguagesReproduceTheDigest() {
        // Instant#toString() emits zero, three, six, or nine fractional digits depending on the value, so a
        // whole-second instant would hash differently from a consumer that normalizes to microseconds. This vector
        // is the whole-second case, independently reproduced from the published canonicalization rules.
        ExecutionDispatch wholeSecond = ExecutionDispatchPolicy.create(
                MESSAGE, DISPATCH, Instant.parse("2026-08-28T12:00:00Z"), ORGANIZATION, PROJECT, RUN, 2, ATTEMPT,
                RUN, SNAPSHOT, Instant.parse("2026-08-28T12:05:00Z"));

        assertThat(wholeSecond.payloadDigest())
                .isEqualTo("sha256:3d61597a9fa443ad54cf22e8bc2fa933e8ef13d883bfe6fb9c74b9b26a3b9b8f");
        // Trailing-zero microseconds are the same instant and must therefore be the same message.
        assertThat(ExecutionDispatchPolicy.create(
                                MESSAGE, DISPATCH, Instant.parse("2026-08-28T12:00:00.000000Z"), ORGANIZATION,
                                PROJECT, RUN, 2, ATTEMPT, RUN, SNAPSHOT,
                                Instant.parse("2026-08-28T12:05:00.000Z"))
                        .payloadDigest())
                .isEqualTo(wholeSecond.payloadDigest());
    }

    @Test
    void anIncompleteDispatchCannotBeDigested() {
        assertThatThrownBy(() -> new ExecutionDispatch(
                        "1.0", null, "EXECUTION_DISPATCH", DISPATCH, START, "kaas.scheduler", ORGANIZATION, PROJECT,
                        RUN, 2, ATTEMPT, 1, RUN, SNAPSHOT, DEADLINE, "sha256:" + "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignmentAndDeadlineAreNotInventedByInvalidInputs() {
        assertThatThrownBy(() -> dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 1, ATTEMPT, SNAPSHOT, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatch(MESSAGE, DISPATCH, ORGANIZATION, PROJECT, RUN, 2, ATTEMPT, SNAPSHOT, START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExecutionDispatch dispatch(
            UUID messageId,
            UUID dispatchId,
            UUID organizationId,
            UUID projectId,
            UUID runId,
            long runVersion,
            UUID attemptId,
            String snapshotDigest,
            Instant deadline) {
        return ExecutionDispatchPolicy.create(
                messageId, dispatchId, START, organizationId, projectId, runId, runVersion, attemptId, runId,
                snapshotDigest, deadline);
    }
}
