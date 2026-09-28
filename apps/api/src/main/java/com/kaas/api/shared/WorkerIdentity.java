package com.kaas.api.shared;

import java.util.regex.Pattern;

/**
 * The worker namespace: which authenticated service subjects are execution workers.
 *
 * <p>A worker's id is the subject of the service token it presented, and nothing else -- never a field in a
 * request body. This is the one definition of the shape that id must have, shared by every internal endpoint
 * that acts for a worker (claiming, submitting evidence) and mirrored by the CHECK constraints on the tables
 * that record one, so a platform service outside the namespace (the egress proxy, say) cannot act as a worker
 * anywhere.
 */
public final class WorkerIdentity {
    private static final Pattern WORKER = Pattern.compile("^kaas\\.worker\\.[A-Za-z0-9._-]{1,200}$");

    private WorkerIdentity() {}

    /** Whether an authenticated subject is a worker identity at all. */
    public static boolean isWorker(String subject) {
        return subject != null && WORKER.matcher(subject).matches();
    }
}
