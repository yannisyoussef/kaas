package com.kaas.api.execution.domain;

import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The immutable description of what would be executed.
 *
 * <p>Would be. Nothing in this slice executes it: it is not published to a broker, not handed to the sandbox
 * launcher, and not reachable from the dispatch consumer. A command exists and has nowhere to go, which is the
 * deliberate end state — the authority composition can be proven correct before anything acts on it, and there
 * is no window where an incomplete design is one configuration change away from running user content.
 *
 * <p><strong>No bearer token appears here, and no source capability identifier either.</strong> The token is
 * obvious; the identifier is the subtler one. Capabilities rotate on every delivery, so an identifier baked
 * into an immutable document is stale from the second request onward — and a field the digest cannot cover is a
 * field an attacker chooses and a consumer cannot verify. Capability identity lives in the delivery envelope
 * instead. The distinction between the semantic command and its delivery is the reason a database dump of this
 * table is not a set of live credentials, and it is also why the two carry different things.
 */
public record ExecutionCommand(
        UUID commandId,
        UUID authorizationId,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        long runVersion,
        UUID attemptId,
        int attemptNumber,
        int assignmentEpoch,
        String runSnapshotSha256,
        EngineDescriptor engine,
        SourceBundleReference sourceBundle,
        List<SecretCapabilityReference> secretCapabilities,
        NetworkPolicyReference networkPolicy,
        SandboxSecurityProfileReference sandboxSecurityProfile,
        List<ConfigurationVariable> configuration,
        RunSelection selection,
        int parallelism,
        ScenarioRetry scenarioRetry,
        int executionTimeoutSeconds,
        ArtifactPolicy artifactPolicy,
        Instant issuedAt,
        Instant expiresAt,
        String commandDigest) {

    public ExecutionCommand {
        secretCapabilities = List.copyOf(secretCapabilities);
        configuration = List.copyOf(configuration);
        if (issuedAt != null && expiresAt != null && !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A command must expire after it is issued.");
        }
    }

    /**
     * What the source bundle is, without saying where it is or which credential fetches it.
     *
     * <p>It deliberately carries <strong>no capability identifier</strong>. It used to, and that was wrong in
     * two directions at once. Capabilities rotate on every delivery — a retry mints a fresh one and revokes the
     * previous — so the identifier baked into an immutable document named a revoked capability from the second
     * request onward, while the token actually delivered belonged to a capability the command never mentioned.
     * And it was excluded from the digest, so it was a field an attacker could choose and a consumer could not
     * verify. Capability identity is per-delivery, so it belongs in the delivery envelope, not in the artifact.
     *
     * <p>No URL either: a deployment hostname baked into an immutable document becomes wrong when the
     * deployment moves, and it would put infrastructure topology into an artifact that outlives it.
     */
    public record SourceBundleReference(String contentDigest, List<SnapshotFeature> features) {
        public SourceBundleReference {
            features = List.copyOf(features);
        }
    }

    /** A secret capability the worker may redeem. Never a value, a path, or a provider credential. */
    public record SecretCapabilityReference(
            UUID capabilityId, String provider, String referenceId, String bindingKey, Instant expiresAt) {}

    /** Which egress policy applies, by identity and digest. Not launcher configuration. */
    public record NetworkPolicyReference(UUID policyRevisionId, NetworkPolicyType type, int version, String digest) {}

    /**
     * Which sandbox boundary the platform expected when it authorized this.
     *
     * <p>The profile version and the assessment digest, and nothing else. Host-sensitive diagnostics — which
     * controls a particular deployment cannot enforce, what its kernel reported — are exactly what an attacker
     * would want and are deliberately absent from an artifact that travels.
     */
    /**
     * The sandbox boundary this execution was authorized for.
     *
     * <p>{@code sandboxRuntime} is implied by {@code profileVersion} and carried anyway. The runner compares
     * it by name against the runtime it is about to instantiate, so a command authorized for one boundary and
     * dispatched to a worker configured for the other is refused as a runtime mismatch — rather than as an
     * unrecognised profile string, which is the same refusal wearing a misleading name.
     *
     * <p>It is compared, never resolved: nothing turns this string into a runtime. A command that could name
     * the runtime a worker instantiates would be a command that chooses which program a daemon executes.
     */
    public record SandboxSecurityProfileReference(
            String profileVersion, String sandboxRuntime, String assessmentDigest) {}
}
