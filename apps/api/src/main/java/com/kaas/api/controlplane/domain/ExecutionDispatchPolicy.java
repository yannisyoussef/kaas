package com.kaas.api.controlplane.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public final class ExecutionDispatchPolicy {
    private static final String FORMAT = "kaas.execution-dispatch.v1";

    /**
     * Instants are digested at exactly six fractional digits in UTC. {@code Instant#toString()} emits zero, three,
     * six, or nine digits depending on the value, so digesting it directly would make the same message hash
     * differently in another language that normalizes timestamps. Six digits is PostgreSQL's timestamptz precision.
     */
    private static final DateTimeFormatter CANONICAL_INSTANT =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);

    private ExecutionDispatchPolicy() {}

    public static ExecutionDispatch create(
            UUID messageId,
            UUID dispatchId,
            Instant occurredAt,
            UUID organizationId,
            UUID projectId,
            UUID runId,
            long runVersion,
            UUID attemptId,
            UUID runSnapshotId,
            String runSnapshotDigest,
            Instant queueDeadlineAt) {
        if (messageId == null || dispatchId == null || occurredAt == null || organizationId == null
                || projectId == null || runId == null || runVersion < 2 || attemptId == null
                || runSnapshotId == null || !runId.equals(runSnapshotId) || runSnapshotDigest == null
                || queueDeadlineAt == null || !queueDeadlineAt.isAfter(occurredAt)) {
            throw new IllegalArgumentException("Execution dispatch fields are invalid.");
        }
        var value = new ExecutionDispatch(
                "1.0", messageId, "EXECUTION_DISPATCH", dispatchId, occurredAt, "kaas.scheduler",
                organizationId, projectId, runId, runVersion, attemptId, 1, runSnapshotId,
                runSnapshotDigest, queueDeadlineAt, "");
        return withDigest(value, digest(value));
    }

    public static String digest(ExecutionDispatch value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FORMAT);
            update(digest, value.schemaVersion());
            update(digest, value.messageId().toString());
            update(digest, value.messageType());
            update(digest, value.dispatchId().toString());
            update(digest, CANONICAL_INSTANT.format(value.occurredAt()));
            update(digest, value.producer());
            update(digest, value.organizationId().toString());
            update(digest, value.projectId().toString());
            update(digest, value.runId().toString());
            update(digest, Long.toString(value.runVersion()));
            update(digest, value.attemptId().toString());
            update(digest, Integer.toString(value.attemptNumber()));
            update(digest, value.runSnapshotId().toString());
            update(digest, value.runSnapshotDigest());
            update(digest, CANONICAL_INSTANT.format(value.queueDeadlineAt()));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ExecutionDispatch withDigest(ExecutionDispatch value, String digest) {
        return new ExecutionDispatch(
                value.schemaVersion(), value.messageId(), value.messageType(), value.dispatchId(), value.occurredAt(),
                value.producer(), value.organizationId(), value.projectId(), value.runId(), value.runVersion(),
                value.attemptId(), value.attemptNumber(), value.runSnapshotId(), value.runSnapshotDigest(),
                value.queueDeadlineAt(), digest);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
