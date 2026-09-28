package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.PublishOutcome;
import com.kaas.api.outbox.domain.PublishStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reconstructs transport delivery of a dispatch RabbitMQ lost after publication (KAAS-MSG-001, ADR-036).
 *
 * <h2>The same dispatch, again -- never a new one</h2>
 *
 * <p>Recovery republishes the outbox row the relay already published: the same message id, the same bytes, the
 * same headers, through the same publisher. No run, attempt, epoch, snapshot or payload is created or changed;
 * {@code execution_dispatches} and the outbox row are not written at all. A recovery is one more TRANSPORT
 * delivery attempt of one immutable logical dispatch.
 *
 * <h2>At least once, deliberately</h2>
 *
 * <p>Whether a message was lost cannot be known -- a broker count is a transient observation, and a message may be
 * merely late. So recovery does not try to know. It republishes when PostgreSQL shows a published dispatch that
 * the consumer has not durably decided after a grace period, and it relies on the property the platform has had
 * since the consumer slice: a message that arrives twice is decided once, keyed by its stable id and semantic
 * digest. A delayed original and its recovery converge on ONE decision, one deliverable run, one claim.
 *
 * <h2>Order of operations</h2>
 *
 * <pre>
 *   claim (short transaction, lease)  ->  re-check eligibility  ->  verify the persisted bytes  ->  publish and
 *   await the broker confirm (no transaction open)  ->  record the outcome (conditional on still owning the claim)
 * </pre>
 *
 * <p>Success is recorded only after the confirm, so a crash before it leaves the dispatch eligible and it is
 * republished when the lease expires. A crash after the confirm and before the record republishes it again --
 * a duplicate the consumer absorbs. Neither loses it.
 *
 * <h2>Bounds</h2>
 *
 * <p>The queue deadline is the terminal authority and always wins: an expired or cancelled run is never eligible,
 * and a copy that escapes just before either is refused by the consumer, which revalidates live state. Within the
 * deadline, republications are spaced by the grace period doubled after each one, and capped at
 * {@code max-publications}; failures back off exponentially. Nothing here ever terminates a run.
 */
@Component
public class DispatchRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchRecovery.class);

    /** Closed vocabulary for the skip reason tag. */
    static final Set<String> SKIP_REASONS = Set.of(
            "DELIVERED", "RUN_NOT_QUEUED", "ATTEMPT_NOT_WAITING", "QUEUE_DEADLINE_PASSED", "NOT_PUBLISHED",
            "LEASE_EXPIRING");

    /** Closed vocabulary for the failure reason tag and for {@code last_failure_code}: the publisher's own codes. */
    static final Set<String> FAILURE_CODES = Set.of(
            FailureCode.BROKER_UNAVAILABLE, FailureCode.PUBLISH_NACKED, FailureCode.CONFIRM_TIMEOUT,
            FailureCode.UNROUTABLE, FailureCode.INTEGRITY_DIGEST_MISMATCH, FailureCode.UNSUPPORTED_SCHEMA_VERSION,
            FailureCode.UNSUPPORTED_MESSAGE_TYPE, FailureCode.MALFORMED_PAYLOAD);

    private final DispatchRecoveryRepository recoveries;
    private final DispatchPublisher publisher;
    private final OutboxMessageVerifier verifier;
    private final MeterRegistry meters;
    private final String consumer;
    private final Duration grace;
    private final Duration maxInterval;
    private final int maxPublications;
    private final int batchSize;
    private final Duration claimTtl;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration confirmTimeout;
    private final AtomicReference<Instant> lastPass = new AtomicReference<>();
    private final boolean enabled;

    public DispatchRecovery(
            DispatchRecoveryRepository recoveries,
            DispatchPublisher publisher,
            OutboxMessageVerifier verifier,
            MeterRegistry meters,
            @Value("${kaas.dispatch.recovery.enabled}") boolean enabled,
            @Value("${kaas.consumer.name}") String consumer,
            @Value("${kaas.dispatch.recovery.grace}") Duration grace,
            @Value("${kaas.dispatch.recovery.max-interval}") Duration maxInterval,
            @Value("${kaas.dispatch.recovery.max-publications}") int maxPublications,
            @Value("${kaas.dispatch.recovery.batch-size}") int batchSize,
            @Value("${kaas.dispatch.recovery.claim-ttl}") Duration claimTtl,
            @Value("${kaas.dispatch.recovery.base-backoff}") Duration baseBackoff,
            @Value("${kaas.dispatch.recovery.max-backoff}") Duration maxBackoff,
            @Value("${kaas.outbox.rabbit.confirm-timeout}") Duration confirmTimeout,
            @Value("${kaas.scheduling.queue-timeout}") Duration queueTimeout) {
        this.enabled = enabled;
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("Dispatch recovery grace must be positive.");
        }
        // Recovery is only useful if there is time left to use it: a grace that consumes half the queue budget or
        // more would republish a lost dispatch so late that the run had little chance of being claimed in time.
        if (enabled && grace.multipliedBy(2).compareTo(queueTimeout) >= 0) {
            throw new IllegalArgumentException("Dispatch recovery grace must be shorter than half the queue timeout.");
        }
        // A claim held by an instance that died is reclaimable only when it expires. A lease that can outlast the
        // run's queue budget turns one crash in the confirm window into a lost run -- the failure recovery exists
        // to prevent.
        if (enabled && claimTtl.plus(grace).compareTo(queueTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "Dispatch recovery claim TTL plus grace must be shorter than the queue timeout.");
        }
        if (confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("The publisher confirm timeout must be positive.");
        }
        if (maxInterval.compareTo(grace) < 0) {
            throw new IllegalArgumentException("Dispatch recovery max interval must not be shorter than its grace.");
        }
        if (maxPublications < 1 || maxPublications > 100) {
            throw new IllegalArgumentException("Dispatch recovery max publications must be between 1 and 100.");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Dispatch recovery batch size must be between 1 and 1000.");
        }
        // As for the relay: a batch that can outlive its own lease lets another instance reclaim rows this one is
        // still publishing, multiplying duplicates exactly when the broker is unhealthy. Like every cross-check with
        // another subsystem's settings, enforced when recovery runs; a disabled recovery constrains nothing.
        if (enabled && confirmTimeout.multipliedBy(batchSize).compareTo(claimTtl) > 0) {
            throw new IllegalArgumentException(
                    "Dispatch recovery claim TTL must exceed batch size multiplied by the publisher confirm timeout.");
        }
        if (baseBackoff.isNegative() || baseBackoff.isZero() || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("Dispatch recovery backoff must be positive and bounded.");
        }
        this.recoveries = recoveries;
        this.publisher = publisher;
        this.verifier = verifier;
        this.meters = meters;
        this.consumer = consumer;
        this.grace = grace;
        this.maxInterval = maxInterval;
        this.maxPublications = maxPublications;
        this.batchSize = batchSize;
        this.claimTtl = claimTtl;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.confirmTimeout = confirmTimeout;
        meters.gauge("kaas.dispatch.recovery.eligible", this, recovery -> recovery.eligibleNow());
        meters.gauge("kaas.dispatch.recovery.capped", this, recovery -> recovery.cappedNow());
    }

    /**
     * One bounded pass: claim, re-check, republish, record. Safe in any number of instances at once.
     *
     * @return how many republications the broker confirmed
     */
    public int recoverOnce() {
        UUID claimId = UUID.randomUUID();
        List<DispatchRecoveryRepository.Claimed> claimed =
                recoveries.claimEligible(consumer, claimId, batchSize, grace, maxPublications, claimTtl);
        int confirmed = 0;
        for (DispatchRecoveryRepository.Claimed candidate : claimed) {
            count("kaas.dispatch.recovery.claimed", null, null);
            OutboxMessage message = candidate.message();
            // The lease the DATABASE enforces, returned by the claim itself -- not one computed here afterwards,
            // which would be later by however long the claim transaction took. Room is left for the confirm and
            // for the re-check and the record around it.
            if (Thread.currentThread().isInterrupted() || recoveries.currentDatabaseTime()
                    .plus(confirmTimeout.multipliedBy(2)).isAfter(candidate.leaseExpiresAt())) {
                // Never publish past the lease: the claim would already be someone else's.
                recoveries.release(message.messageId(), claimId);
                continue;
            }
            try {
                if (recover(claimId, candidate)) {
                    confirmed++;
                }
            } catch (RuntimeException failure) {
                // One dispatch must not abandon the rest; its claim expires and the next pass retries it.
                LOGGER.atWarn()
                        .addKeyValue("event", "DISPATCH_RECOVERY_FAILED")
                        .addKeyValue("messageId", message.messageId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("A dispatch recovery could not be processed");
            }
        }
        lastPass.set(recoveries.currentDatabaseTime());
        return confirmed;
    }

    private boolean recover(UUID claimId, DispatchRecoveryRepository.Claimed candidate) {
        OutboxMessage message = candidate.message();
        // Immediately before publishing, the live answer: a cancellation, a deadline or a delivery that
        // committed since the claim wins, and nothing is published.
        Optional<String> ineligible = recoveries.ineligibility(consumer, message.messageId());
        if (ineligible.isPresent()) {
            recoveries.release(message.messageId(), claimId);
            count("kaas.dispatch.recovery.skipped", "reason", ineligible.orElseThrow());
            return false;
        }
        // The persisted bytes are verified again, independently of how they were verified at first publication:
        // recovery must publish exactly the dispatch it claims to, or nothing.
        Optional<String> rejection = verifier.verify(message);
        if (rejection.isPresent()) {
            fail(claimId, candidate, rejection.orElseThrow());
            return false;
        }
        // And the lease, once more, immediately before the broker is asked: the re-check and the verification took
        // time, and a publication that could outlast the claim would race whichever instance reclaims it.
        if (recoveries.currentDatabaseTime().plus(confirmTimeout).isAfter(candidate.leaseExpiresAt())) {
            recoveries.release(message.messageId(), claimId);
            count("kaas.dispatch.recovery.skipped", "reason", "LEASE_EXPIRING");
            return false;
        }
        PublishOutcome outcome;
        try {
            outcome = publisher.publish(message);
        } catch (RuntimeException unexpected) {
            outcome = PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        }
        if (outcome.status() != PublishStatus.CONFIRMED) {
            fail(claimId, candidate, outcome.failureCode());
            return false;
        }
        // Confirmed. Only now is it recorded -- and the next republication, should even this one be lost, waits
        // for a longer interval than the last.
        Instant now = recoveries.currentDatabaseTime();
        Instant next = now.plus(interval(candidate.recoveryPublications() + 1));
        if (!recoveries.recordPublished(message.messageId(), claimId, next)) {
            count("kaas.dispatch.recovery.claim_lost", null, null);
            return false;
        }
        count("kaas.dispatch.recovery.published", null, null);
        LOGGER.atInfo()
                .addKeyValue("event", "DISPATCH_RECOVERED")
                .addKeyValue("messageId", message.messageId())
                .addKeyValue("runId", message.runId())
                .addKeyValue("recoveryPublication", candidate.recoveryPublications() + 1)
                .log("Republished a published dispatch the consumer never recorded");
        return true;
    }

    private void fail(UUID claimId, DispatchRecoveryRepository.Claimed candidate, String failureCode) {
        String code = failureCode != null && FAILURE_CODES.contains(failureCode) ? failureCode : "UNKNOWN";
        Instant next = recoveries.currentDatabaseTime().plus(backoff(candidate.recoveryAttempts() + 1));
        if (!recoveries.recordFailed(candidate.message().messageId(), claimId, code, next)) {
            count("kaas.dispatch.recovery.claim_lost", null, null);
            return;
        }
        count("kaas.dispatch.recovery.failed", "reason", code);
        LOGGER.atWarn()
                .addKeyValue("event", "DISPATCH_RECOVERY_PUBLISH_FAILED")
                .addKeyValue("messageId", candidate.message().messageId())
                .addKeyValue("failureCode", code)
                .log("A dispatch republication failed and will be retried after backoff");
    }

    /** The wait before republication number {@code publications + 1}: the grace, doubled each time, capped. */
    Duration interval(int publications) {
        Duration interval = grace;
        for (int index = 1; index < publications && interval.compareTo(maxInterval) < 0; index++) {
            interval = interval.multipliedBy(2);
        }
        return interval.compareTo(maxInterval) > 0 ? maxInterval : interval;
    }

    Duration backoff(int attempts) {
        Duration backoff = baseBackoff;
        for (int index = 1; index < attempts && backoff.compareTo(maxBackoff) < 0; index++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /** When the last pass completed on this instance, on the database clock; empty before the first. */
    public Optional<Instant> lastPass() {
        return Optional.ofNullable(lastPass.get());
    }

    /** Published dispatches the consumer has not recorded, past their grace, right now. */
    public long eligibleNow() {
        try {
            return recoveries.countEligible(consumer, grace, maxPublications);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /** Waiting dispatches at the republication cap; -1 when the database cannot be read. */
    public long cappedNow() {
        try {
            return recoveries.countCapped(consumer, maxPublications);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    public Duration grace() {
        return grace;
    }

    /** Whether this instance runs recovery on a timer. */
    public boolean enabled() {
        return enabled;
    }

    /** Dimensions are closed vocabularies: never a run, tenant, project, message or dispatch identity. */
    private void count(String name, String tag, String value) {
        Counter.Builder counter = Counter.builder(name);
        if (tag != null) {
            counter.tag(tag, "reason".equals(tag) && name.endsWith("skipped") && !SKIP_REASONS.contains(value)
                    ? "OTHER" : value);
        }
        counter.register(meters).increment();
    }
}
