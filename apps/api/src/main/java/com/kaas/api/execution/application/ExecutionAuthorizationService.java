package com.kaas.api.execution.application;

import com.kaas.api.controlplane.application.WorkerLeaseRepository;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.execution.domain.CapabilityToken;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.EgressDestination;
import com.kaas.api.execution.domain.ExecutionAuthorization;
import com.kaas.api.execution.domain.ExecutionCapability;
import com.kaas.api.execution.domain.ExecutionCommand;
import com.kaas.api.execution.domain.ExecutionCommandPolicy;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import com.kaas.api.execution.domain.NetworkPolicyType;
import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import com.kaas.api.execution.domain.SecretValueProvider;
import com.kaas.api.execution.domain.SourceBundlePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether one specific assignment may execute, and issues what it needs if so.
 *
 * <p>Owning an attempt and being allowed to execute it are separate decisions. Claiming established the first;
 * this establishes the second, and it is deliberately not implied by the first because the conditions that make
 * execution safe can all stop being true while ownership continues. A worker can hold an attempt perfectly
 * legitimately at a moment when the run has been cancelled, the lease has lapsed, the sandbox has no verified
 * security posture, or the run needs secrets the platform cannot supply.
 *
 * <p>Every check below fails closed, and the order is chosen so that the cheapest and most specific refusals
 * come first — a stale assignment should not be told about the deployment's security posture.
 *
 * <p><strong>Nothing here executes anything.</strong> The command this produces is written to a table and goes
 * no further: it is not published to a broker, not handed to the sandbox launcher, and not reachable from the
 * dispatch consumer. The run stays in {@code CLAIMED}; there is no transition to {@code PROVISIONING}.
 */
@Service
public class ExecutionAuthorizationService {

    /** Only a principal in this namespace may hold an assignment. */
    private static final String WORKER_NAMESPACE = "kaas.worker.";
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionAuthorizationService.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final ExecutionAuthorizationRepository repository;
    private final WorkerLeaseRepository leases;
    private final SandboxSecurityAttestationSource attestations;
    private final SecretValueProvider secrets;
    private final MeterRegistry meters;
    private final Duration authorizationTtl;
    private final Duration capabilityTtl;
    private final Duration attestationMaxAge;
    private final String expectedProfileVersion;

    public ExecutionAuthorizationService(
            ExecutionAuthorizationRepository repository,
            WorkerLeaseRepository leases,
            SandboxSecurityAttestationSource attestations,
            SecretValueProvider secrets,
            MeterRegistry meters,
            @Value("${kaas.execution.authorization-ttl}") Duration authorizationTtl,
            @Value("${kaas.execution.capability-ttl}") Duration capabilityTtl,
            @Value("${kaas.execution.attestation-max-age}") Duration attestationMaxAge,
            @Value("${kaas.execution.security-profile-version}") String expectedProfileVersion) {
        this.repository = repository;
        this.leases = leases;
        this.attestations = attestations;
        this.secrets = secrets;
        this.meters = meters;
        this.authorizationTtl = authorizationTtl;
        this.capabilityTtl = capabilityTtl;
        this.attestationMaxAge = attestationMaxAge;
        this.expectedProfileVersion = expectedProfileVersion;
        requireBounded("kaas.execution.authorization-ttl", authorizationTtl, Duration.ofSeconds(30), MAX_TTL);
        requireBounded("kaas.execution.capability-ttl", capabilityTtl, Duration.ofSeconds(30), MAX_TTL);
        // The one setting that can silently disable the only security gate this platform has. The two TTLs above
        // are backstopped by database CHECKs at thirty minutes, so a runaway value there fails loudly on the
        // first request; this one has no backstop anywhere, and P3650D would turn freshness off with no error
        // and no log. A deployment may widen it deliberately, within a range someone has to have thought about.
        requireBounded(
                "kaas.execution.attestation-max-age", attestationMaxAge, Duration.ofHours(1), Duration.ofDays(7));
    }

    /** The database's own ceiling on an authorization or capability window, restated so a bad value fails here. */
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    private static void requireBounded(String property, Duration value, Duration floor, Duration ceiling) {
        if (value == null || value.compareTo(floor) < 0 || value.compareTo(ceiling) > 0) {
            throw new IllegalStateException(
                    property + " must be between " + floor + " and " + ceiling + " but was " + value);
        }
    }

    /**
     * Authorizes one assignment, or says why not.
     *
     * <p>Serializable would be stronger, but the row lock taken by {@code lockForAuthorization} already
     * serializes every writer that touches this run's ownership — the claim path, the heartbeat, the reconciler,
     * and cancellation all take it in the same order. Read-committed under that lock gives the same decision
     * with none of the retry churn.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome authorize(UUID runId, UUID attemptId, int assignmentEpoch, String workerId) {
        // The same lock, in the same order, that the claim path, the heartbeat, the reconciler, and cancellation
        // all take. A run that is not owned at all comes back empty, which is the same answer a worker gets for a
        // run that is queued or finished: this is not executable.
        var locked = leases.lockOwnedByRun(runId);
        if (locked.isEmpty()) {
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();

        if (run.lifecycleState() != RunLifecycle.CLAIMED) {
            // ONLY A GENUINELY ENDED RUN HAS ITS AUTHORITY WITHDRAWN.
            //
            // This branch used to revoke for every state that is not CLAIMED, and lockOwnedByRun now returns the
            // four execution phases — so an authorize call arriving mid-execution revoked the authorization and
            // every capability of a run that was executing normally, and stamped it RUN_TERMINATED. That is the
            // inverse of the property the revocation exists to preserve: a durable record that disagrees with
            // the decision it records. A worker re-authorizing after a lost response did it to itself.
            boolean genuinelyOver = run.lifecycleState() == RunLifecycle.STOPPING
                    || run.lifecycleState() == RunLifecycle.COMPLETED;
            if (!genuinelyOver) {
                // Executing, not finished. Refuse without touching anything: this call is out of order, and an
                // out-of-order call is not evidence that the run has ended.
                return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
            }
            // Covers QUEUED, STOPPING, and COMPLETED with one answer. A worker learns that this run is not
            // executable, not which of several states it happens to be in.
            //
            // Withdrawing any authority the run still carries, here, under the lock. Fencing ends an assignment
            // but wrote nothing to these rows, so an authorization for a cancelled run kept reading as live to
            // anything querying `revoked_at IS NULL AND expires_at > now()`. Nothing could USE it — every
            // redemption revalidates — but a durable record that disagrees with the decision it records is a
            // record that will eventually be believed. This closes it at the first moment anyone asks; a
            // reconciler that closes it without being asked is named in the residual risks.
            repository.revokeForRun(runId, "RUN_TERMINATED", repository.currentDatabaseTime());
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        if (!attempt.attemptId().equals(attemptId)
                || attempt.state() != ExecutionAttemptState.CLAIMED
                || attempt.assignment().epoch() != assignmentEpoch
                || attempt.assignment().fenced()) {
            // Identity and epoch together, exactly as the heartbeat checks them. Either alone leaves a hole:
            // an epoch alone lets any worker act as the owner, an identity alone lets a replaced worker act
            // under an assignment it has lost.
            return denied(ExecutionDenial.ASSIGNMENT_STALE);
        }
        // Only a worker may hold an assignment. Checked here because this is where holding begins.
        if (workerId == null || !workerId.startsWith(WORKER_NAMESPACE)) {
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        if (attempt.assignment().acquired() && !attempt.assignment().workerId().equals(workerId)) {
            // Somebody else holds it. This is the check that the claim-time worker id could never perform,
            // because that value is one constant for the whole deployment.
            return denied(ExecutionDenial.ASSIGNMENT_STALE);
        }

        Instant now = repository.currentDatabaseTime();
        if (!attempt.assignment().acquired() && !attempt.assignment().expiredAt(now)) {
            // FIRST AUTHORIZATION BINDS THE ASSIGNMENT. Under the same lock every ownership writer takes, and
            // write-once in the database, so two workers racing here produce one holder and one refusal rather
            // than two holders that both satisfy every later check.
            attempt = attempt.acquiredBy(workerId, now);
            repository.persistAcquisition(organizationId, run.projectId(), runId, attempt);
            LOGGER.atInfo()
                    .addKeyValue("event", "ASSIGNMENT_ACQUIRED")
                    .addKeyValue("runId", runId)
                    .addKeyValue("attemptId", attemptId)
                    .addKeyValue("assignmentEpoch", assignmentEpoch)
                    .log("A worker bound this assignment to itself");
        }
        if (attempt.assignment().expiredAt(now)) {
            // The reconciler has not fenced it yet, but it will. Authorizing here would hand out authority whose
            // basis has already lapsed, and would race the fencing that is about to happen.
            return denied(ExecutionDenial.LEASE_EXPIRED);
        }
        var snapshot = repository.loadSnapshot(organizationId, run.projectId(), runId);
        if (snapshot.isEmpty()
                || !snapshot.orElseThrow().sealed()
                || snapshot.orElseThrow().runSnapshotSha256() == null
                || snapshot.orElseThrow().features().isEmpty()) {
            return denied(ExecutionDenial.RUN_SNAPSHOT_INVALID);
        }
        var context = snapshot.orElseThrow();
        if (context.totalSourceBytes() > SourceBundlePolicy.MAX_TOTAL_BYTES
                || context.features().size() > SourceBundlePolicy.MAX_FEATURES) {
            // Checked here rather than left to the bundle builder. The ceilings did not compose: a run may pin a
            // thousand revisions of half a megabyte against a sixty-four megabyte bundle, and issuance could not
            // see the size because it digests paths rather than content. The result was an authorization that
            // always succeeded followed by a redemption that always threw — rolling back the redemption counter,
            // so the amplification ceiling was unreachable on exactly the input that cost the most, while each
            // attempt pulled the whole snapshot out of the database under the run lock.
            return denied(ExecutionDenial.RUN_SNAPSHOT_INVALID);
        }

        Optional<SandboxSecurityAttestation> attestation = attestations.attestation();
        if (attestation.isEmpty()) {
            return denied(ExecutionDenial.SECURITY_GATE_UNAVAILABLE);
        }
        Optional<String> untrustworthy =
                attestation.orElseThrow().reasonItCannotBeTrusted(now, attestationMaxAge, expectedProfileVersion);
        if (untrustworthy.isPresent()) {
            LOGGER.atWarn()
                    .addKeyValue("event", "SANDBOX_ATTESTATION_REJECTED")
                    .addKeyValue("runId", runId)
                    .addKeyValue("reason", untrustworthy.orElseThrow())
                    .log("Refused execution because the sandbox security assessment cannot be relied on");
            return denied(ExecutionDenial.SECURITY_GATE_FAILED);
        }

        // The policy this run was SEALED with, not a constant. While DENY_ALL was the only policy that
        // existed, looking it up by its well-known identifier was equivalent and simpler; with a second
        // enforceable type it would mean every run silently executing under a policy nobody selected.
        Optional<NetworkPolicyRevision> policy = repository.findNetworkPolicy(context.networkPolicyRevisionId());
        if (policy.isEmpty()
                || !policy.orElseThrow().policyType().enforceable()
                || !policy.orElseThrow().digestMatchesContent()) {
            // A policy whose digest no longer matches its own content has been tampered with in the database,
            // and one whose type nothing can enforce would be a promise the runtime does not keep.
            //
            return denied(ExecutionDenial.NETWORK_POLICY_NOT_ENFORCEABLE);
        }
        if (policy.orElseThrow().policyType() == NetworkPolicyType.ALLOWLIST) {
            // Having the mechanism is not the same as this host being able to run it.
            //
            // The mandatory controls above say the sandbox confines what it runs. These say the deployment can
            // build the proxy image, create an isolated network, verify it is isolated, bring a proxy up on
            // it, and leave a sandbox with no route of its own. An attestation produced before those controls
            // existed carries none of them, and the fail-closed reading of "no evidence" is "not enforceable"
            // — which is why this is asked of the assessment rather than of a configuration flag. A flag would
            // be an operator's optimism; this is a measurement of the machine.
            //
            // Asked only for ALLOWLIST. A DENY_ALL run refused because the egress subsystem is unhealthy would
            // be a run refused for a subsystem it does not use.
            Optional<String> unenforceable = attestation.orElseThrow().reasonEgressCannotBeEnforced();
            if (unenforceable.isPresent()) {
                LOGGER.atWarn()
                        .addKeyValue("event", "EGRESS_NOT_ENFORCEABLE")
                        .addKeyValue("runId", runId)
                        .addKeyValue("reason", unenforceable.orElseThrow())
                        .log("Refused an allowlist execution because this deployment cannot enforce egress");
                // A refusal, never a downgrade to DENY_ALL. A run that appeared to have egress control nothing
                // was applying would be worse than one that has none and says so.
                return denied(ExecutionDenial.NETWORK_POLICY_NOT_ENFORCEABLE);
            }
        }

        if (!context.secretBindings().isEmpty() && !secrets.available()) {
            // The run needs secrets and nothing can supply them. Refusing here rather than at redemption means
            // the failure happens before a sandbox exists, with a reason that names the actual problem.
            LOGGER.atInfo()
                    .addKeyValue("event", "EXECUTION_DENIED")
                    .addKeyValue("runId", runId)
                    .addKeyValue("attemptId", attemptId)
                    .addKeyValue("reason", ExecutionDenial.SECRET_PROVIDER_UNAVAILABLE.name())
                    .log("Refused execution for a secret-bearing run with no secret provider");
            return denied(ExecutionDenial.SECRET_PROVIDER_UNAVAILABLE);
        }

        // The authorization may never outlive the lease that justifies it. Taking the earlier of the two is what
        // makes "authorization.expiresAt <= lease.expiresAt" true by construction rather than by a check that
        // could be forgotten.
        Instant expiresAt = earlier(now.plus(authorizationTtl), attempt.assignment().leaseExpiresAt());
        if (!expiresAt.isAfter(now)) {
            // A window that computes empty. Mutation testing showed this subsumes the explicit lease check
            // above for an expired lease — removing that check alone leaves the suite green, and removing both
            // turns it red — so the two are recorded as jointly covered rather than individually. They are kept
            // separate anyway because they answer different questions: that one asks whether the lease has
            // lapsed, this one whether any usable window remains, which a misconfigured zero TTL would also
            // make false while the lease was perfectly healthy.
            return denied(ExecutionDenial.LEASE_EXPIRED);
        }

        Optional<ExecutionAuthorization> existing = repository.findAuthorization(attemptId, assignmentEpoch);
        if (existing.isPresent()) {
            return reissue(existing.orElseThrow(), attempt, workerId, policy.orElseThrow(), now, expiresAt);
        }
        return issue(
                organizationId, run, attempt, context, attestation.orElseThrow(), policy.orElseThrow(), now, expiresAt);
    }

    /**
     * Returns the authorization that already exists for this assignment, with fresh capability tokens.
     *
     * <p>The authorization itself is never re-created: one per attempt and epoch, enforced by a unique
     * constraint, so a retrying worker cannot accumulate parallel authorities or shift its own expiry outward by
     * asking again.
     *
     * <p>Capabilities <em>are</em> rotated, and the previous ones are revoked in the same transaction. The
     * alternative — returning the same still-valid tokens — would mean the server holding plaintext it cannot
     * hold, since only the hash is stored. Rotation keeps the invariant that at most one live capability of each
     * type exists per authorization, so ten retries produce ten tokens of which nine are already dead rather
     * than ten that all work.
     */
    private Outcome reissue(
            ExecutionAuthorization authorization,
            ExecutionAttempt attempt,
            String workerId,
            NetworkPolicyRevision policy,
            Instant now,
            Instant expiresAt) {
        if (authorization.revokedAt() != null) {
            // Revocation is terminal. It is the one state a re-anchor must not rescue.
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        if (!authorization.describes(attempt.attemptId(), attempt.assignment().epoch(), workerId)) {
            // The caller is not who this authorization was issued to.
            //
            // Jointly covered with the assignment check in authorize() rather than independently, and recorded
            // as such rather than claimed: authorize() validates the live assignment before it ever reaches
            // here, and an epoch is allocated exactly once per attempt, so no test can present a caller this
            // would be the first to reject. Mutation testing confirms it — removing this line alone leaves the
            // suite green. It is kept because the check that makes it unreachable lives in another method, and
            // a reissue that trusted the row rather than the caller would be one refactor away from handing a
            // fresh live token to the wrong worker.
            return denied(ExecutionDenial.ASSIGNMENT_STALE);
        }
        // Re-anchor to the lease as it stands now.
        //
        // Freezing the window at issuance was a liveness dead end: an authorization is bounded by the lease that
        // justifies it, a lease is renewed indefinitely by heartbeat, and the unique constraint on the assignment
        // makes a replacement impossible. So a healthy worker became permanently unauthorizable one lease-period
        // after its first request, with the run's only exit being FAILED. Re-anchoring cannot widen authority:
        // the value is recomputed as the earlier of the TTL and the CURRENT lease expiry, the trigger refuses a
        // backwards move, and every redemption revalidates the live assignment regardless.
        if (expiresAt.isAfter(authorization.expiresAt())
                && !repository.reanchorAuthorization(authorization.authorizationId(), expiresAt)) {
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        authorization = new ExecutionAuthorization(
                authorization.authorizationId(), authorization.organizationId(), authorization.projectId(),
                authorization.runId(), authorization.runVersion(), authorization.attemptId(),
                authorization.attemptNumber(), authorization.assignmentEpoch(), authorization.workerId(),
                authorization.runSnapshotSha256(), authorization.securityProfileVersion(),
                authorization.securityAssessmentDigest(), authorization.probeImageDigest(),
                authorization.networkPolicyRevisionId(), authorization.issuedAt(),
                expiresAt.isAfter(authorization.expiresAt()) ? expiresAt : authorization.expiresAt(),
                null, null);
        if (!authorization.withinWindow(now)) {
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        var command = repository.findCommand(authorization.authorizationId());
        if (command.isEmpty()) {
            // An authorization without its command is a state nothing writes: the two are inserted in one
            // transaction. Refusing rather than reconstructing means a corrupted pair fails loudly.
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }
        Instant capabilityExpiry = earlier(now.plus(capabilityTtl), authorization.expiresAt());
        if (!capabilityExpiry.isAfter(now)) {
            return denied(ExecutionDenial.CAPABILITY_EXPIRED);
        }
        Minted source = mintSource(authorization.authorizationId(), now, capabilityExpiry);
        Optional<Minted> egress = mintEgress(policy, authorization.authorizationId(), now, capabilityExpiry);
        // Rotated together, in one transaction, with whatever was there before revoked. Rotating one and
        // leaving the other would leave a live token from a previous delivery usable alongside a fresh one,
        // which is exactly the accumulation the at-most-one-live rule exists to prevent.
        List<ExecutionCapability> replacements = new java.util.ArrayList<>();
        replacements.add(source.capability());
        egress.ifPresent(minted -> replacements.add(minted.capability()));
        repository.rotateCapabilities(authorization.authorizationId(), List.copyOf(replacements), now);
        count("kaas.execution.authorization", "REISSUED");
        var stored = command.orElseThrow();
        return new Outcome(
                Optional.of(new Delivery(
                        authorization,
                        stored.commandId(),
                        stored.commandDigest(),
                        MAPPER.readTree(stored.document()),
                        stored.expiresAt(),
                        source.capability().capabilityId(),
                        source.token(),
                        List.of(),
                        egress.map(Minted::token),
                        // Named only when a capability was minted, so the two cannot disagree: a destination
                        // list beside an absent credential would describe an allowlist nothing can use.
                        egress.isPresent() ? policy.destinations() : List.of())),
                Optional.empty());
    }

    private Outcome issue(
            UUID organizationId,
            TestRun run,
            ExecutionAttempt attempt,
            ExecutionAuthorizationRepository.SnapshotContext context,
            SandboxSecurityAttestation attestation,
            NetworkPolicyRevision policy,
            Instant now,
            Instant expiresAt) {
        UUID authorizationId = UUID.randomUUID();

        List<SnapshotFeature> features = ExecutionCommandPolicy.canonicalFeatures(context.features());
        // Re-validated here even though these paths were checked when each revision was created. The boundary
        // that builds a filesystem layout is the right place to refuse a path that would escape one.
        SourceBundlePolicy.requireSafePaths(features.stream().map(SnapshotFeature::logicalPath).toList());
        String bundleDigest = SourceBundlePolicy.digest(features.stream()
                .map(feature -> new SourceBundlePolicy.BundleEntry(
                        feature.logicalPath(), feature.sourceDigest(), new byte[0]))
                .toList());

        Instant capabilityExpiry = earlier(now.plus(capabilityTtl), expiresAt);
        if (!capabilityExpiry.isAfter(now)) {
            // The guard reissue() already had. Without it a zero-length window reaches the database and fails
            // the window CHECK as a 500, where the honest answer is that there is no usable window left.
            return denied(ExecutionDenial.CAPABILITY_EXPIRED);
        }
        Minted source = mintSource(authorizationId, now, capabilityExpiry);
        Optional<Minted> egress = mintEgress(policy, authorizationId, now, capabilityExpiry);

        var authorization = new ExecutionAuthorization(
                authorizationId,
                organizationId,
                run.projectId(),
                run.runId(),
                run.runVersion(),
                attempt.attemptId(),
                attempt.attemptNumber(),
                attempt.assignment().epoch(),
                attempt.assignment().workerId(),
                context.runSnapshotSha256(),
                attestation.securityProfileVersion(),
                attestation.digest(),
                attestation.probeImageDigest(),
                policy.policyRevisionId(),
                now,
                expiresAt,
                null,
                null);

        var command = command(context, authorization, policy, attestation, bundleDigest, now);
        if (!context.secretBindings().isEmpty() && command.secretCapabilities().isEmpty()) {
            // A server bug, not a denial. Today the secret refusal above makes this unreachable; the moment a
            // real provider reports available(), that refusal stops firing and nothing else would notice a
            // command issued with every secret silently dropped — which is the outcome
            // UnavailableSecretValueProvider's own documentation names as the worst of the alternatives. This
            // is the invariant that has to fail loudly instead.
            throw new IllegalStateException(
                    "A secret-bearing run produced a command with no secret capabilities.");
        }
        String document = ExecutionCommandPolicy.document(command, MAPPER).toString();
        List<ExecutionCapability> issued = new java.util.ArrayList<>();
        issued.add(source.capability());
        egress.ifPresent(minted -> issued.add(minted.capability()));
        if (!repository.persistIssuance(new ExecutionAuthorizationRepository.Issuance(
                authorization, List.copyOf(issued), command, document))) {
            // A concurrent request for the same assignment won the unique constraint. The loser does not retry
            // in a loop: it reports the same refusal a stale caller gets, and the winner's authorization stands.
            // One semantic authorization per assignment is the invariant; two racers must not produce two.
            return denied(ExecutionDenial.EXECUTION_NOT_AUTHORIZED);
        }

        count("kaas.execution.authorization", "ISSUED");
        count("kaas.execution.command.created", "CREATED");
        LOGGER.atInfo()
                .addKeyValue("event", "EXECUTION_AUTHORIZED")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("runId", run.runId())
                .addKeyValue("attemptId", attempt.attemptId())
                .addKeyValue("assignmentEpoch", attempt.assignment().epoch())
                .addKeyValue("authorizationId", authorizationId)
                .addKeyValue("commandId", command.commandId())
                .log("Authorized one assignment and issued its execution command; nothing executes it");
        return new Outcome(
                Optional.of(new Delivery(
                        authorization,
                        command.commandId(),
                        command.commandDigest(),
                        MAPPER.readTree(document),
                        command.expiresAt(),
                        source.capability().capabilityId(),
                        source.token(),
                        List.of(),
                        egress.map(Minted::token),
                        // Named only when a capability was minted, so the two cannot disagree: a destination
                        // list beside an absent credential would describe an allowlist nothing can use.
                        egress.isPresent() ? policy.destinations() : List.of())),
                Optional.empty());
    }

    private ExecutionCommand command(
            ExecutionAuthorizationRepository.SnapshotContext context,
            ExecutionAuthorization authorization,
            NetworkPolicyRevision policy,
            SandboxSecurityAttestation attestation,
            String bundleDigest,
            Instant now) {
        var draft = new ExecutionCommand(
                UUID.randomUUID(),
                authorization.authorizationId(),
                authorization.organizationId(),
                authorization.projectId(),
                authorization.runId(),
                authorization.runVersion(),
                authorization.attemptId(),
                authorization.attemptNumber(),
                authorization.assignmentEpoch(),
                authorization.runSnapshotSha256(),
                context.engine(),
                new ExecutionCommand.SourceBundleReference(
                        bundleDigest, ExecutionCommandPolicy.canonicalFeatures(context.features())),
                // Empty by construction in this slice: a run that binds secrets never reaches here, because
                // there is no provider to satisfy it and authorization refused above.
                List.of(),
                new ExecutionCommand.NetworkPolicyReference(
                        policy.policyRevisionId(),
                        policy.policyType(),
                        policy.policyVersion(),
                        policy.canonicalDigest()),
                new ExecutionCommand.SandboxSecurityProfileReference(
                        attestation.securityProfileVersion(), attestation.digest()),
                context.configuration(),
                context.selection(),
                context.parallelism(),
                context.scenarioRetry(),
                context.executionTimeoutSeconds(),
                context.artifactPolicy(),
                now,
                authorization.expiresAt(),
                "");
        return withDigest(draft, ExecutionCommandPolicy.digest(draft));
    }

    private Minted mintSource(UUID authorizationId, Instant now, Instant expiresAt) {
        return mint(CapabilityType.SOURCE, authorizationId, now, expiresAt);
    }

    /**
     * Mints an egress capability when the policy is one the sandbox has to ask permission under.
     *
     * <p>DENY_ALL executions get none, and that is the point of the branch: a sandbox with no network has
     * nothing to present a credential to, and issuing one anyway would put a live bearer token into an
     * environment for no reason at all. It is also why DENY_ALL keeps the simpler proven path — a capability
     * that exists is a capability that can leak.
     */
    private Optional<Minted> mintEgress(
            NetworkPolicyRevision policy, UUID authorizationId, Instant now, Instant expiresAt) {
        if (policy.policyType() != NetworkPolicyType.ALLOWLIST) {
            return Optional.empty();
        }
        return Optional.of(mint(CapabilityType.EGRESS, authorizationId, now, expiresAt));
    }

    private Minted mint(CapabilityType type, UUID authorizationId, Instant now, Instant expiresAt) {
        String token = CapabilityToken.issue(type);
        return new Minted(
                token,
                new ExecutionCapability(
                        UUID.randomUUID(),
                        authorizationId,
                        type,
                        CapabilityToken.hash(token),
                        now,
                        expiresAt,
                        0,
                        null,
                        null,
                        List.of()));
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private Outcome denied(ExecutionDenial denial) {
        count("kaas.execution.authorization.denied", denial.name());
        return new Outcome(Optional.empty(), Optional.of(denial));
    }

    private void count(String name, String result) {
        Counter.builder(name).tag("result", result).register(meters).increment();
    }

    private static ExecutionCommand withDigest(ExecutionCommand command, String digest) {
        return new ExecutionCommand(
                command.commandId(),
                command.authorizationId(),
                command.organizationId(),
                command.projectId(),
                command.runId(),
                command.runVersion(),
                command.attemptId(),
                command.attemptNumber(),
                command.assignmentEpoch(),
                command.runSnapshotSha256(),
                command.engine(),
                command.sourceBundle(),
                command.secretCapabilities(),
                command.networkPolicy(),
                command.sandboxSecurityProfile(),
                command.configuration(),
                command.selection(),
                command.parallelism(),
                command.scenarioRetry(),
                command.executionTimeoutSeconds(),
                command.artifactPolicy(),
                command.issuedAt(),
                command.expiresAt(),
                digest);
    }

    private record Minted(String token, ExecutionCapability capability) {}

    /** Either a delivery or a refusal, never both and never neither. */
    public record Outcome(Optional<Delivery> delivery, Optional<ExecutionDenial> denial) {}

    /**
     * What a worker receives.
     *
     * <p>The bearer tokens live in this object and nowhere else. They are assembled at response time from
     * material that existed only in memory, and the persisted command carries capability identifiers instead —
     * an identifier lets you name a capability, not use it.
     */
    public record Delivery(
            ExecutionAuthorization authorization,
            UUID commandId,
            String commandDigest,
            tools.jackson.databind.JsonNode commandDocument,
            Instant commandExpiresAt,
            UUID sourceCapabilityId,
            String sourceCapabilityToken,
            List<String> secretCapabilityTokens,
            /**
             * Present only for a policy that needs one.
             *
             * <p>Like every other token here it exists in this object and nowhere else: it was never written
             * to a database, a log, a metric, a container label, or the persisted command. It is deliberately
             * NOT part of the command's semantic digest either — the command is immutable and this rotates on
             * every delivery, so a digest covering it would be stale from the second request onward, and the
             * rule the digest enforces is that a field it cannot cover must not be in the document.
             */
            Optional<String> egressCapabilityToken,
            /**
             * The destinations the run's pinned policy permits, delivered so the worker's platform-owned
             * workload knows where to aim.
             *
             * <p><strong>Not authority.</strong> The proxy resolves the policy from authoritative state on
             * every request and every tunnel revalidation, so this list enforces nothing and a copy of it
             * altered in transit changes nothing about what may be reached. It travels beside the command
             * rather than inside it for that reason: putting a second copy of the policy into an immutable,
             * digested artifact that nothing enforces from would be a field the runtime ignores, which is a
             * claim with no evidence behind it. What the command binds — and what the worker independently
             * verifies — is the policy's revision id and canonical digest.
             *
             * <p>Empty for a policy that names none, which is every {@code DENY_ALL}.
             */
            List<EgressDestination> egressDestinations) {}
}
