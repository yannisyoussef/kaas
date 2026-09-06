package com.kaas.api.execution.application;

import com.kaas.api.controlplane.application.WorkerLeaseRepository;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.execution.domain.CapabilityToken;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.SourceBundlePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redeems a source capability for the exact immutable sources one run's snapshot pinned.
 *
 * <p><strong>Every redemption revalidates authoritative state.</strong> The token being unexpired is necessary
 * and nowhere near sufficient, and this is the method where that principle either holds or the whole design is
 * decoration. A capability issued a second before a run was cancelled has an unexpired TTL and must fail. So
 * must one whose lease lapsed, whose assignment was fenced, or whose epoch has been superseded. Expiry bounds
 * the damage from a leaked token; revalidation is what makes fencing effective.
 *
 * <p>The sources come from the snapshot's pinned revisions, never from the feature's current state. A run that
 * was created against revision 3 executes revision 3 for as long as it exists, even if revision 4 was published
 * a moment later — otherwise a run's meaning would change underneath it.
 */
@Service
public class SourceCapabilityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceCapabilityService.class);

    private final ExecutionAuthorizationRepository repository;
    private final WorkerLeaseRepository leases;
    private final MeterRegistry meters;

    public SourceCapabilityService(
            ExecutionAuthorizationRepository repository, WorkerLeaseRepository leases, MeterRegistry meters) {
        this.repository = repository;
        this.leases = leases;
        this.meters = meters;
    }

    /**
     * Exchanges a presented token for the source bundle it authorizes.
     *
     * @param workerId the authenticated service principal, never a value from the request body
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Redemption redeem(String presentedToken, String workerId) {
        if (!CapabilityToken.hasShapeOf(presentedToken, CapabilityType.SOURCE)) {
            // Refused on shape before anything is looked up, so a secret token presented here is never searched
            // for in the source population and a malformed one costs no query.
            return refused(ExecutionDenial.CAPABILITY_INVALID);
        }
        var found = repository.findRedeemable(CapabilityToken.hash(presentedToken), CapabilityType.SOURCE);
        if (found.isEmpty()) {
            return refused(ExecutionDenial.CAPABILITY_INVALID);
        }
        var capability = found.orElseThrow().capability();
        var authorization = found.orElseThrow().authorization();

        // The lock comes FIRST, and the clock is read under it.
        //
        // Reading the clock before taking the lock was the one place in this codebase that got this backwards,
        // and it was not academic: `lockOwnedByRun` blocks for as long as any other writer holds the run row, so
        // every window check below was evaluated against an instant from before that wait. A capability was
        // demonstrably served 550ms after it had expired, with the run still CLAIMED and the attempt unfenced —
        // the attempt row is re-read fresh on lock acquisition, so the comparison had a current lease expiry on
        // one side and a stale instant on the other. Every other writer in the system reads the clock after its
        // lock; this now does too.
        var locked = leases.lockOwnedByRun(authorization.runId());
        if (locked.isEmpty()) {
            // Not owned any more: cancelled, stopping, settled, or never claimed. The token is irrelevant.
            return refused(ExecutionDenial.CAPABILITY_FENCED);
        }
        Instant now = repository.currentDatabaseTime();
        if (!capability.withinWindow(now) || !authorization.withinWindow(now)) {
            return refused(ExecutionDenial.CAPABILITY_EXPIRED);
        }

        var run = locked.orElseThrow().run();
        var attempt = locked.orElseThrow().attempt();
        if (run.lifecycleState() != RunLifecycle.CLAIMED) {
            // Jointly covered with the assignment check below rather than independently: every reachable state
            // where a run has left CLAIMED also fences its attempt in the same transaction, so no test can move
            // one without the other. Mutation testing confirmed the pair — removing either alone leaves the
            // suite green, removing both turns it red. Both are kept because they are different claims, and a
            // future state that separated them would otherwise arrive with neither being checked.
            return refused(ExecutionDenial.CAPABILITY_FENCED);
        }
        if (attempt.state() != ExecutionAttemptState.CLAIMED
                || !attempt.assignment().isHeldBy(workerId, authorization.assignmentEpoch())
                || !authorization.describes(attempt.attemptId(), attempt.assignment().epoch(), workerId)) {
            // The assignment moved: a different worker, a later epoch, or a fenced attempt. All three mean this
            // capability's basis is gone, and all three answer with the same thing.
            return refused(ExecutionDenial.CAPABILITY_FENCED);
        }
        if (attempt.assignment().expiredAt(now)) {
            // Expired but not yet fenced by the reconciler. Serving here would hand out sources on the strength
            // of a lease that has already lapsed.
            return refused(ExecutionDenial.CAPABILITY_FENCED);
        }
        if (!repository.recordRedemption(capability.capabilityId(), now)) {
            // The compare-and-set lost, or the ceiling was reached between the read and the write.
            return refused(ExecutionDenial.CAPABILITY_EXPIRED);
        }

        var sources = repository.loadSnapshotSources(
                locked.orElseThrow().organizationId(), run.projectId(), run.runId());
        if (sources.isEmpty()) {
            return refused(ExecutionDenial.CAPABILITY_INVALID);
        }
        List<SourceBundlePolicy.BundleEntry> entries = sources.stream()
                .map(source -> new SourceBundlePolicy.BundleEntry(
                        source.logicalPath(),
                        // PREFIXED, like every other digest this system exchanges.
                        //
                        // This read returns the raw column, and the bundle digest computed from it therefore
                        // differed from the one the ExecutionCommand carries -- which is computed from
                        // SnapshotFeature.sourceDigest, and that IS prefixed. Two authoritative descriptions
                        // of the same bundle, disagreeing.
                        //
                        // Nothing noticed until a worker actually redeemed a bundle and compared it against
                        // the command that authorized it: every such comparison would have failed, and the
                        // only reason it had not is that nothing had ever made one.
                        prefixed(source.sourceSha256()),
                        source.source().getBytes(StandardCharsets.UTF_8)))
                .toList();
        byte[] archive = SourceBundlePolicy.archive(entries);
        String digest = SourceBundlePolicy.digest(entries);

        count("kaas.source.capability.redemption", "GRANTED");
        LOGGER.atInfo()
                .addKeyValue("event", "SOURCE_CAPABILITY_REDEEMED")
                .addKeyValue("runId", run.runId())
                .addKeyValue("attemptId", attempt.attemptId())
                .addKeyValue("assignmentEpoch", authorization.assignmentEpoch())
                .addKeyValue("capabilityId", capability.capabilityId())
                .addKeyValue("bundleDigest", digest)
                .log("Released the pinned source bundle for an active assignment");
        return new Redemption(Optional.of(new Bundle(archive, digest)), Optional.empty());
    }

    /** The digest form every other part of this system exchanges. */
    private static String prefixed(String hex) {
        return hex != null && hex.startsWith("sha256:") ? hex : "sha256:" + hex;
    }

    private Redemption refused(ExecutionDenial denial) {
        count("kaas.source.capability.redemption", denial.name());
        return new Redemption(Optional.empty(), Optional.of(denial));
    }

    private void count(String name, String result) {
        Counter.builder(name).tag("result", result).register(meters).increment();
    }

    /** The archive and the semantic digest of what it contains. */
    public record Bundle(byte[] archive, String contentDigest) {
        public Bundle {
            archive = archive.clone();
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }
    }

    public record Redemption(Optional<Bundle> bundle, Optional<ExecutionDenial> denial) {}
}
