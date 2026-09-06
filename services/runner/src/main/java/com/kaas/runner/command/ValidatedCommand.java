package com.kaas.runner.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A command the runner has independently checked and is willing to act on.
 *
 * <p>Nothing constructs this except {@link CommandValidator}. That is the point of the type existing at all:
 * the execution loop takes a {@code ValidatedCommand} rather than a JSON document, so "did anyone check this"
 * is answered by the type system instead of by reading the call chain.
 */
public record ValidatedCommand(
        UUID commandId,
        String commandDigest,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        long runVersion,
        UUID attemptId,
        int attemptNumber,
        int assignmentEpoch,
        String runSnapshotSha256,
        Instant issuedAt,
        Instant expiresAt,
        String engineType,
        String engineVersion,
        String networkPolicyType,
        /** The sandbox profile the command was authorized under, which the launcher must run. */
        String sandboxProfileVersion,
        /**
         * The runtime the platform authorized this execution to run under.
         *
         * <p>Compared by name against the runtime this worker is configured to instantiate, and never turned
         * into one. A command that could select the runtime would be a command that selects which program the
         * daemon executes, which is a larger privilege than anything else a command carries.
         */
        String sandboxRuntime,
        List<String> tags,
        /**
         * The bundle identity the command authorizes, and the exact features it must contain.
         *
         * <p>Identity and digests only. There is no source content here and there cannot be: this record is
         * what the execution loop holds, and a field carrying tenant bytes would mean the loop had them to
         * pass somewhere. What it carries is enough to REFUSE a bundle that is not the authorized one, which
         * is the whole of its job.
         */
        SourceBundleAuthorization sourceBundle) {

    /** What the command says the bundle must be. Covered by the command digest, like every other field. */
    public record SourceBundleAuthorization(String contentDigest, List<Feature> features) {
        public SourceBundleAuthorization {
            features = List.copyOf(features);
        }
    }

    /** One authorized feature: where its bytes go and what they must hash to. */
    public record Feature(String featureId, String revisionId, String logicalPath, String contentDigest) {}
}
