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
        List<String> tags) {}
