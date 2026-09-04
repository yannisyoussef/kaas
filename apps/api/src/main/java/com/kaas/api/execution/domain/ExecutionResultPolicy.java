package com.kaas.api.execution.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The rules a submitted result must satisfy, and the digest that identifies it.
 *
 * <p>The digest is computed the same way the command's is — SHA-256 over length-prefixed components — and for
 * the same reason. Concatenating variable-length fields without their lengths is not injective: two different
 * results can produce one preimage by moving a delimiter into a value. Prefixing each component with its byte
 * length makes the encoding injective, so a digest collision requires breaking SHA-256 rather than choosing a
 * clever run identifier.
 *
 * <p>The digest covers the identity binding AND the document body. Covering identity alone would let a worker
 * resubmit a different document under a digest the control plane had already accepted; covering the body alone
 * would let one execution's document be replayed against another assignment.
 */
public final class ExecutionResultPolicy {

    private static final String FORMAT = "kaas.execution-result.v1";

    /** The wire schema this digest is defined over. Part of the preimage, so a format change cannot collide. */
    public static final String SCHEMA_VERSION = "1.0";

    /**
     * The largest result document the control plane will accept, matching the database's own bound.
     *
     * <p>Checked at the boundary as well as in the database because the two protect different things: the
     * database protects the table from a row nothing can read back, and this protects the API from parsing and
     * digesting a body it has already decided to refuse.
     */
    public static final int MAX_DOCUMENT_BYTES = 262_144;

    private ExecutionResultPolicy() {}

    public static String digest(
            java.util.UUID resultId,
            java.util.UUID organizationId,
            java.util.UUID projectId,
            java.util.UUID runId,
            java.util.UUID attemptId,
            int assignmentEpoch,
            java.util.UUID commandId,
            String runSnapshotSha256,
            String document) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, FORMAT);
            update(sha, "SCHEMA_VERSION");
            update(sha, SCHEMA_VERSION);
            update(sha, "RESULT");
            update(sha, resultId.toString());
            update(sha, "ORGANIZATION");
            update(sha, organizationId.toString());
            update(sha, "PROJECT");
            update(sha, projectId.toString());
            update(sha, "RUN");
            update(sha, runId.toString());
            update(sha, "ATTEMPT");
            update(sha, attemptId.toString());
            update(sha, "ASSIGNMENT_EPOCH");
            update(sha, Integer.toString(assignmentEpoch));
            update(sha, "COMMAND");
            update(sha, commandId.toString());
            update(sha, "RUN_SNAPSHOT");
            update(sha, runSnapshotSha256);
            update(sha, "DOCUMENT");
            update(sha, document);
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM.", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
