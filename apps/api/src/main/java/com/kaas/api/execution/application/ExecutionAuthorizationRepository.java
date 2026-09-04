package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SecretBinding;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionAuthorization;
import com.kaas.api.execution.domain.ExecutionCapability;
import com.kaas.api.execution.domain.ExecutionCommand;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable state for the authorization decision and everything issued under it.
 *
 * <p>Deliberately does not load the run or its assignment. Those come from {@code WorkerLeaseRepository}, whose
 * {@code lockOwnedByRun} already takes the row lock every writer that touches ownership takes, in the order they
 * all take it. Re-implementing that read here would mean a second lock order and a second row mapper for facts
 * that already have one — and a lock order that varies by caller is how two correct transactions deadlock.
 */
public interface ExecutionAuthorizationRepository {

    /**
     * The one authoritative clock.
     *
     * <p>Every instant this slice writes or compares comes from here. The application host, the database, and a
     * container runtime all drift relative to each other, and an earlier slice produced four separate defects by
     * comparing an instant sourced from one against a bound sourced from another. Lease expiry already lives in
     * this domain; authorization and capability expiry join it rather than opening a second one.
     */
    Instant currentDatabaseTime();

    /** The sealed snapshot a run pinned, or empty when there is none or it is not sealed. */
    Optional<SnapshotContext> loadSnapshot(UUID organizationId, UUID projectId, UUID runId);

    /** The platform-owned egress policy an execution would run under. */
    /**
     * Binds an assignment to the worker acquiring it. Write-once; the guard refuses a second acquisition.
     */
    void persistAcquisition(UUID organizationId, UUID projectId, UUID runId, ExecutionAttempt attempt);

    Optional<NetworkPolicyRevision> findNetworkPolicy(UUID policyRevisionId);

    /** An authorization already issued for this exact assignment, if there is one. */
    Optional<ExecutionAuthorization> findAuthorization(UUID attemptId, int assignmentEpoch);

    /**
     * Persists a new authorization together with its capabilities and command.
     *
     * <p>One transaction, because a command referring to a capability that was not written, or a capability
     * outliving the authorization that justified it, are both states nothing downstream could interpret.
     *
     * @return false when a competing request won the unique constraint on (attempt, epoch) first
     */
    boolean persistIssuance(Issuance issuance);

    /** Rotates capabilities for an authorization that already exists, revoking whatever it had before. */
    void rotateCapabilities(UUID authorizationId, List<ExecutionCapability> replacements, Instant at);

    /**
     * Moves a live authorization's expiry forward to follow the lease that justifies it.
     *
     * <p>Without this an authorization is frozen at the window it was issued with, which for a thirty-second
     * lease means it dies half a minute after issuance while the worker holding it is perfectly healthy — and
     * the unique constraint on the assignment makes a replacement impossible. Re-anchoring cannot widen
     * authority: the caller recomputes the value as the earlier of the TTL and the <em>current</em> lease
     * expiry, and redemption revalidates the live assignment either way.
     *
     * @return false when the authorization was revoked or the new expiry would move backwards
     */
    boolean reanchorAuthorization(UUID authorizationId, Instant expiresAt);

    /** Withdraws an authorization and every capability under it. Used when the assignment it named is over. */
    int revokeForRun(UUID runId, String reason, Instant at);

    /**
     * The command issued under an authorization, as it was stored.
     *
     * <p>Returned as its document rather than rebuilt into a record. The document is the artifact: reconstructing
     * it from its own serialization would create a second definition of what a command means, and the two would
     * eventually disagree about something that matters.
     */
    Optional<StoredCommand> findCommand(UUID authorizationId);

    /**
     * Looks a capability up by the hash of a presented token.
     *
     * <p>By hash alone. A caller never supplies an identifier alongside a token, because a lookup keyed on an
     * attacker-chosen identifier and merely <em>compared</em> against a token is how a confused deputy is built.
     */
    Optional<Redeemable> findRedeemable(String tokenSha256, CapabilityType expectedType);

    /** Records that a capability was redeemed, refusing past the row's own ceiling. */
    boolean recordRedemption(UUID capabilityId, Instant at);

    /** The immutable feature sources one snapshot names, with the content each revision pinned. */
    List<FeatureSource> loadSnapshotSources(UUID organizationId, UUID projectId, UUID runId);

    /** What the command needs from the sealed snapshot, and what the decision needs to know about secrets. */
    record SnapshotContext(
            String runSnapshotSha256,
            boolean sealed,
            /** Total UTF-8 bytes of the pinned sources, so issuance can refuse what redemption could not build. */
            long totalSourceBytes,
            List<SnapshotFeature> features,
            List<SecretBinding> secretBindings,
            List<ConfigurationVariable> configuration,
            RunSelection selection,
            int parallelism,
            ScenarioRetry scenarioRetry,
            int executionTimeoutSeconds,
            ArtifactPolicy artifactPolicy,
            EngineDescriptor engine) {}

    /** An authorization and everything issued under it, written together. */
    record Issuance(
            ExecutionAuthorization authorization,
            List<ExecutionCapability> capabilities,
            ExecutionCommand command,
            String commandDocument) {}

    /** A persisted command: its identity, its digest, its window, and the document itself. */
    record StoredCommand(UUID commandId, String commandDigest, String document, Instant issuedAt, Instant expiresAt) {}

    /** A capability found by token, with the authorization that justified it. */
    record Redeemable(ExecutionCapability capability, ExecutionAuthorization authorization) {}

    /** One feature's immutable content, as the snapshot pinned it. */
    record FeatureSource(
            UUID featureId, UUID revisionId, String logicalPath, String sourceSha256, String source) {}
}
