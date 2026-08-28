package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ClaimDisposition;
import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.TestRun;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes authoritative ownership of a run on behalf of a worker instance.
 *
 * <p>A broker message is transport, not authority. Every fact this needs — that the run exists, that it is still
 * QUEUED, that it names this attempt at this version, that its deadline has not passed — is read from PostgreSQL
 * under a lock, and the claim is a compare-and-set against that state. The message contributes an identity to
 * look up and nothing else; it cannot decide, extend, or override any of it.
 *
 * <p>What a claim grants is deliberately narrow: it records who owns the infrastructure attempt. It issues no
 * execution command, no source capability, and no secret capability, and it does not advance the run toward
 * provisioning. Ownership and permission to execute are different things, and only the first exists here.
 */
@Service
public class RunClaimService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunClaimService.class);

    /** The claim's audit actor. The worker instance it is claiming *for* is recorded on the attempt. */
    public static final String CONSUMER_ACTOR = "kaas.dispatch-consumer";

    private final RunClaimRepository claims;
    private final Duration leaseDuration;

    public RunClaimService(
            RunClaimRepository claims, @Value("${kaas.claim.lease-duration}") Duration leaseDuration) {
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Lease duration must be between one nanosecond and 30 minutes.");
        }
        if (leaseDuration.getNano() % 1000 != 0) {
            // PostgreSQL timestamptz keeps microseconds; a finer lease would not survive a round trip intact.
            throw new IllegalArgumentException("Lease duration must be a whole number of microseconds.");
        }
        this.claims = claims;
        this.leaseDuration = leaseDuration;
    }

    /**
     * Attempts one claim.
     *
     * <p>READ COMMITTED is pinned for the same reason scheduling and termination pin it: the locked row must be
     * re-qualified after a competing writer commits, so a loser's predicate stops matching rather than raising a
     * serialization failure there is no retry for.
     *
     * @param expectedAttemptId the attempt the message believes is current, checked rather than trusted
     * @param expectedRunVersion the version the message was produced at, likewise checked
     * @param workerId the server-controlled worker instance the assignment is recorded against
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClaimOutcome claim(ExecutionDispatch message, String workerId) {
        if (message == null) {
            throw new IllegalArgumentException("A claim is made about a specific dispatch.");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("A claim records the worker instance it is made for.");
        }
        UUID organizationId = message.organizationId();
        UUID runId = message.runId();

        // The message has to be one this control plane actually produced. A well-formed dispatch whose identity
        // is unknown here was minted by somebody else, and every field in it — tenancy included — is therefore
        // just an assertion. Looking the identity up first is what turns the rest of the checks into
        // corroboration rather than trust.
        var published = claims.findDispatch(organizationId, message.messageId());
        if (published.isEmpty()) {
            return new ClaimOutcome(ClaimDisposition.NOT_CLAIMABLE, "UNKNOWN_DISPATCH", null);
        }
        var trusted = published.orElseThrow();
        // Every identity is compared against the durable row rather than taken from the body. Cross-tenant and
        // cross-project substitution both die here: a payload that names another organization's run cannot match
        // the dispatch that its own message identity resolves to.
        if (!trusted.payloadDigest().equals(message.payloadDigest())
                || !trusted.organizationId().equals(organizationId)
                || !trusted.projectId().equals(message.projectId())
                || !trusted.runId().equals(runId)
                || !trusted.attemptId().equals(message.attemptId())
                || trusted.runVersion() != message.runVersion()
                || !trusted.runSnapshotId().equals(message.runSnapshotId())
                || !trusted.runSnapshotDigest().equals(message.runSnapshotDigest())) {
            return new ClaimOutcome(ClaimDisposition.NOT_CLAIMABLE, "DISPATCH_IDENTITY_MISMATCH", null);
        }

        UUID expectedAttemptId = message.attemptId();
        long expectedRunVersion = message.runVersion();
        // The next three refusals are not reachable while the database's own constraints hold: the dispatch row
        // carries a foreign key to its run, and the scheduling bundle binds that run's attempt and snapshot
        // digest to the dispatch at the moment it was produced. They are kept because this code must not proceed
        // on an inconsistent read, and a refusal is a better answer than dereferencing an empty result — but no
        // test claims to exercise them, because none honestly can.
        var locked = claims.lockClaimable(organizationId, runId);
        if (locked.isEmpty()) {
            return new ClaimOutcome(ClaimDisposition.NOT_CLAIMABLE, "RUN_NOT_FOUND", null);
        }
        TestRun previous = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();

        if (!expectedAttemptId.equals(attempt.attemptId())) {
            return new ClaimOutcome(ClaimDisposition.NOT_CLAIMABLE, "ATTEMPT_MISMATCH", previous);
        }
        // Somebody already owns it. That is a duplicate delivery of a message that was already acted on, and it
        // is checked before the lifecycle so the answer says *why* rather than only that the run moved.
        if (attempt.state() != ExecutionAttemptState.WAITING_FOR_CLAIM) {
            return new ClaimOutcome(ClaimDisposition.ALREADY_CLAIMED, "ATTEMPT_ALREADY_ASSIGNED", previous);
        }
        if (previous.lifecycleState() != RunLifecycle.QUEUED) {
            // Cancelled, expired, or never queued. The broker had no way to know.
            return new ClaimOutcome(ClaimDisposition.STALE, "RUN_NOT_QUEUED", previous);
        }
        if (previous.runVersion() != expectedRunVersion) {
            return new ClaimOutcome(ClaimDisposition.STALE, "RUN_VERSION_MOVED", previous);
        }
        if (!previous.snapshotDigest().equals(message.runSnapshotDigest())) {
            return new ClaimOutcome(ClaimDisposition.NOT_CLAIMABLE, "SNAPSHOT_MISMATCH", previous);
        }

        Instant at = claimInstant(previous);
        if (at.isAfter(previous.queueDeadlineAt())) {
            // The reaper is entitled to end this run. Claiming now would leave the two of us each believing we
            // hold it, and the database would reject the write anyway.
            return new ClaimOutcome(ClaimDisposition.STALE, "QUEUE_DEADLINE_PASSED", previous);
        }

        TestRun claimedRun = previous.claimed(at);
        ExecutionAttempt claimedAttempt = attempt.claimedBy(workerId, at, leaseDuration);
        claims.persistClaim(organizationId, previous, claimedRun, claimedAttempt, UUID.randomUUID());
        LOGGER.atInfo()
                .addKeyValue("event", "RUN_CLAIMED")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("projectId", claimedRun.projectId())
                .addKeyValue("runId", runId)
                .addKeyValue("attemptId", attempt.attemptId())
                .addKeyValue("assignmentEpoch", claimedAttempt.assignment().epoch())
                .addKeyValue("runVersion", claimedRun.runVersion())
                .log("Assigned an execution attempt to a worker instance");
        return new ClaimOutcome(ClaimDisposition.CLAIMED, "CLAIMED", claimedRun);
    }

    /**
     * The claim instant is owned by the database clock, which is the same authority that stamped the queue
     * deadline it is checked against. It is clamped so it can never precede the run's own last update.
     */
    private Instant claimInstant(TestRun previous) {
        Instant databaseTime = claims.currentDatabaseTime();
        return databaseTime.isBefore(previous.updatedAt()) ? previous.updatedAt() : databaseTime;
    }

    /** What a claim attempt decided, and the run as it stood. Reason codes are bounded and low-cardinality. */
    public record ClaimOutcome(ClaimDisposition disposition, String reason, TestRun run) {}
}
