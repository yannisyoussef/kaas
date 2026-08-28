package com.kaas.api.outbox.application;

import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.PublishOutcome;
import com.kaas.api.outbox.domain.PublishStatus;
import com.kaas.api.outbox.domain.RetryPolicy;
import com.kaas.api.outbox.domain.TerminalDisposition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Moves already-durable messages from the PostgreSQL outbox to the broker.
 *
 * <p>Publication is <strong>at least once</strong>. The relay claims a bounded batch in one short transaction,
 * publishes outside any transaction, then records the outcome in another short transaction. A crash between the
 * broker's confirmation and that final write republishes the message on the next pass. That duplicate window is
 * inherent and deliberately not "solved": stable message identity plus the semantic payload digest are what make
 * a duplicate safe for the future consumer.
 *
 * <p>Database transactions are never held open across broker network I/O, so a slow or hung broker cannot pin
 * connections or block other relays.
 */
@Component
public class OutboxRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final DispatchPublisher publisher;
    private final OutboxMessageVerifier verifier;
    private final RetryPolicy retryPolicy;
    private final int batchSize;
    private final Duration claimTtl;
    private final double backoffJitter;
    private final Duration confirmTimeout;
    private final MeterRegistry meters;
    private final Timer publishTimer;

    public OutboxRelay(
            OutboxRepository outbox,
            DispatchPublisher publisher,
            OutboxMessageVerifier verifier,
            MeterRegistry meters,
            @Value("${kaas.outbox.relay.batch-size}") int batchSize,
            @Value("${kaas.outbox.relay.claim-ttl}") Duration claimTtl,
            @Value("${kaas.outbox.relay.max-attempts}") int maxAttempts,
            @Value("${kaas.outbox.relay.base-backoff}") Duration baseBackoff,
            @Value("${kaas.outbox.relay.max-backoff}") Duration maxBackoff,
            @Value("${kaas.outbox.relay.backoff-jitter}") double backoffJitter,
            @Value("${kaas.outbox.rabbit.confirm-timeout}") Duration confirmTimeout) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Relay batch size must be bounded between 1 and 1000.");
        }
        // The claim TTL is persisted in whole seconds, so anything shorter would round to an already-expired
        // lease and every claim would be rejected by the database.
        if (claimTtl.compareTo(Duration.ofSeconds(1)) < 0 || claimTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Relay claim TTL must be between one second and one hour.");
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw new IllegalArgumentException("Backoff jitter must be a fraction between 0 and 1.");
        }
        // A batch that can outlive its own lease lets another relay reclaim rows this one is still publishing,
        // which multiplies duplicates exactly when the broker is unhealthy.
        if (confirmTimeout.multipliedBy(batchSize).compareTo(claimTtl) > 0) {
            throw new IllegalArgumentException(
                    "Relay claim TTL must exceed batch size multiplied by the publisher confirm timeout.");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.verifier = verifier;
        this.retryPolicy = new RetryPolicy(maxAttempts, baseBackoff, maxBackoff);
        this.batchSize = batchSize;
        this.claimTtl = claimTtl;
        this.backoffJitter = backoffJitter;
        this.confirmTimeout = confirmTimeout;
        this.meters = meters;
        this.publishTimer = Timer.builder("kaas.outbox.publish.duration")
                .description("Time spent publishing one outbox message and awaiting its broker confirmation")
                .register(meters);
        meters.gauge("kaas.outbox.pending", this, relay -> relay.outbox.countPending());
        meters.gauge("kaas.outbox.terminal", this, relay -> relay.outbox.countTerminal());
    }

    /**
     * Claims and attempts one bounded batch. Safe to run concurrently in multiple instances: claiming skips rows
     * another relay holds, and every outcome write is conditional on still owning the claim.
     *
     * @return how many messages the broker confirmed during this pass
     */
    public int drainOnce() {
        UUID relayClaimId = UUID.randomUUID();
        List<OutboxMessage> claimed = outbox.claimPending(relayClaimId, batchSize, claimTtl);
        if (claimed.isEmpty()) {
            return 0;
        }
        // The lease started when the claim statement ran. Publishing past its end would let another relay reclaim
        // rows this pass is still working on, so the batch is abandoned instead: the untouched rows keep their
        // attempt count and simply become claimable again when the lease expires.
        Instant leaseEnd = outbox.currentDatabaseTime().plus(claimTtl);
        int confirmed = 0;
        for (int index = 0; index < claimed.size(); index++) {
            OutboxMessage message = claimed.get(index);
            // Shutdown must not burn an attempt on every remaining message without touching the broker, and a
            // batch must never outlive its own lease: another relay would reclaim rows this pass is still
            // publishing, multiplying duplicates exactly when the broker is unhealthy.
            boolean interrupted = Thread.currentThread().isInterrupted();
            if (interrupted || outbox.currentDatabaseTime().plus(confirmTimeout).isAfter(leaseEnd)) {
                LOGGER.atWarn()
                        .addKeyValue("event", "OUTBOX_BATCH_ABANDONED")
                        .addKeyValue("reason", interrupted ? "interrupted" : "lease-expiring")
                        .addKeyValue("remaining", claimed.size() - index)
                        .log("Relay abandoned the rest of this batch");
                release(relayClaimId, claimed.subList(index, claimed.size()));
                break;
            }
            try {
                if (attempt(relayClaimId, message)) {
                    confirmed++;
                }
            } catch (RuntimeException failure) {
                // One message must not abandon the rest of the batch; this row recovers on lease expiry.
                LOGGER.atWarn()
                        .addKeyValue("event", "OUTBOX_MESSAGE_FAILED")
                        .addKeyValue("messageId", message.messageId())
                        .addKeyValue("exceptionType", failure.getClass().getName())
                        .log("Outbox message could not be processed");
            }
        }
        return confirmed;
    }

    /** Hands unprocessed rows back immediately rather than stranding them for the remainder of the lease. */
    private void release(UUID relayClaimId, List<OutboxMessage> remaining) {
        for (OutboxMessage message : remaining) {
            try {
                outbox.releaseClaim(message.outboxId(), relayClaimId);
            } catch (RuntimeException ignored) {
                // The lease expires on its own, so a failed release costs latency rather than correctness.
            }
        }
    }

    private boolean attempt(UUID relayClaimId, OutboxMessage message) {
        // Fail closed before the broker sees anything. An integrity mismatch is a security event, not a hiccup.
        var rejection = verifier.verify(message);
        if (rejection.isPresent()) {
            terminate(relayClaimId, message, TerminalDisposition.PERMANENT_FAILURE, rejection.orElseThrow());
            return false;
        }

        PublishOutcome outcome;
        Timer.Sample sample = Timer.start();
        try {
            outcome = publisher.publish(message);
        } catch (RuntimeException unexpected) {
            // An unclassified transport error is treated as transient; the attempt budget still bounds it.
            LOGGER.atWarn()
                    .addKeyValue("event", "OUTBOX_PUBLISH_ERROR")
                    .addKeyValue("messageId", message.messageId())
                    .addKeyValue("exceptionType", unexpected.getClass().getName())
                    .log("Outbox publication failed before confirmation");
            outcome = PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        } finally {
            sample.stop(publishTimer);
        }

        if (outcome.status() == PublishStatus.CONFIRMED) {
            return succeed(relayClaimId, message);
        }
        if (outcome.status() == PublishStatus.PERMANENT_FAILURE) {
            terminate(relayClaimId, message, TerminalDisposition.PERMANENT_FAILURE, outcome.failureCode());
            return false;
        }
        retryOrExhaust(relayClaimId, message, outcome.failureCode());
        return false;
    }

    private boolean succeed(UUID relayClaimId, OutboxMessage message) {
        Instant now = outbox.currentDatabaseTime();
        if (!outbox.recordPublished(message.outboxId(), relayClaimId, now)) {
            claimLost(message, "published");
            return false;
        }
        count("kaas.outbox.published", message.messageType(), "confirmed");
        LOGGER.atInfo()
                .addKeyValue("event", "OUTBOX_PUBLISHED")
                .addKeyValue("messageId", message.messageId())
                .addKeyValue("messageType", message.messageType())
                .addKeyValue("runId", message.runId())
                .addKeyValue("attempt", message.publishAttempts() + 1)
                .log("Outbox message published and confirmed");
        return true;
    }

    private void retryOrExhaust(UUID relayClaimId, OutboxMessage message, String failureCode) {
        int attemptsMade = message.publishAttempts() + 1;
        Instant now = outbox.currentDatabaseTime();
        if (retryPolicy.exhausted(attemptsMade)) {
            terminate(relayClaimId, message, TerminalDisposition.RETRIES_EXHAUSTED, failureCode);
            return;
        }
        Instant availableAt = now.plus(jittered(retryPolicy.backoffAfter(attemptsMade)));
        if (!outbox.recordRetry(message.outboxId(), relayClaimId, now, availableAt, failureCode)) {
            claimLost(message, "retry");
            return;
        }
        count("kaas.outbox.retry", message.messageType(), failureCode);
        count("kaas.outbox.publish.failed", message.messageType(), failureCode);
        LOGGER.atWarn()
                .addKeyValue("event", "OUTBOX_PUBLISH_RETRY")
                .addKeyValue("messageId", message.messageId())
                .addKeyValue("failureCode", failureCode)
                .addKeyValue("attempt", attemptsMade)
                .log("Outbox publication deferred for retry");
    }

    private void terminate(
            UUID relayClaimId, OutboxMessage message, TerminalDisposition disposition, String failureCode) {
        Instant now = outbox.currentDatabaseTime();
        if (!outbox.recordTerminal(message.outboxId(), relayClaimId, now, disposition, failureCode)) {
            claimLost(message, "terminal");
            return;
        }
        count("kaas.outbox.publish.failed", message.messageType(), failureCode);
        LOGGER.atError()
                .addKeyValue("event", "OUTBOX_PUBLISH_TERMINAL")
                .addKeyValue("messageId", message.messageId())
                .addKeyValue("messageType", message.messageType())
                .addKeyValue("runId", message.runId())
                .addKeyValue("disposition", disposition.name())
                .addKeyValue("failureCode", failureCode)
                .log(disposition == TerminalDisposition.PERMANENT_FAILURE
                        ? "Outbox message was rejected before publication and will not be retried"
                        // A confirm timeout or an ambiguous nack may already have reached the broker, so an
                        // exhausted message must be treated as delivery-unknown, never as proven undelivered.
                        : "Outbox message exhausted its publication attempts; delivery is unknown");
    }

    /**
     * A lost claim means another relay legitimately reclaimed the row. It is expected under lease expiry, but it
     * must be visible: it is also the signature of a batch that outran its own lease, in which case the attempt
     * count never advances and the message can loop without backoff.
     */
    private void claimLost(OutboxMessage message, String stage) {
        count("kaas.outbox.claim.lost", message.messageType(), stage);
        LOGGER.atWarn()
                .addKeyValue("event", "OUTBOX_CLAIM_LOST")
                .addKeyValue("messageId", message.messageId())
                .addKeyValue("stage", stage)
                .log("Relay claim was no longer held when the outcome was recorded");
    }

    /** Spreads a backlog that all failed in the same tick, so it does not re-converge on a single instant. */
    private Duration jittered(Duration backoff) {
        if (backoffJitter == 0) {
            return backoff;
        }
        long spread = (long) (backoff.toMillis() * backoffJitter);
        return spread <= 0 ? backoff : backoff.plusMillis(ThreadLocalRandom.current().nextLong(spread + 1));
    }

    /** Dimensions stay low cardinality: never run, project, organization, message, or dispatch identity. */
    private void count(String name, String messageType, String result) {
        Counter.builder(name)
                .tag("messageType", messageType)
                .tag("result", result)
                .register(meters)
                .increment();
    }
}
