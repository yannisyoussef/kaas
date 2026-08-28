package com.kaas.api.consumer.infrastructure;

import com.kaas.api.consumer.application.DispatchConsumptionService;
import com.kaas.api.consumer.application.DispatchMessage;
import com.kaas.api.consumer.domain.InboxDisposition;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The broker-facing edge of consumption. It owns exactly two decisions: what to hand the control plane, and what
 * to tell the broker afterwards.
 *
 * <p>Acknowledgement is manual and always follows the database. Spring acknowledges when this method returns
 * normally, so returning means a durable decision exists; throwing means it does not. That ordering is the whole
 * safety property — acknowledging first would let a process death between the two lose the work while the broker
 * believed it was handled, whereas committing first turns the same death into a redelivery the inbox absorbs.
 */
@Component
@ConditionalOnProperty(name = "kaas.consumer.enabled", havingValue = "true", matchIfMissing = true)
class RabbitDispatchListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitDispatchListener.class);

    private final DispatchConsumptionService consumption;
    private final long transientBackoffMillis;

    RabbitDispatchListener(
            DispatchConsumptionService consumption,
            @Value("${kaas.consumer.transient-backoff}") Duration transientBackoff) {
        if (transientBackoff.isNegative() || transientBackoff.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Transient backoff must be between zero and one minute.");
        }
        this.consumption = consumption;
        this.transientBackoffMillis = transientBackoff.toMillis();
    }

    @RabbitListener(queues = "${kaas.outbox.rabbit.queue}", concurrency = "${kaas.consumer.concurrency}")
    void receive(Message message) {
        InboxDisposition disposition;
        try {
            disposition = consumption.consume(new DispatchMessage(
                    transportMessageId(message),
                    stringHeader(message, "messageType"),
                    stringHeader(message, "schemaVersion"),
                    message.getBody()));
        } catch (DataAccessException | TransactionException ours) {
            // Ours: the database or the transaction failed, nothing was decided, and nothing committed. Requeue,
            // because the work is real. This is the ONLY class of failure that may come back.
            throw deferToBroker(ours);
        } catch (RuntimeException theirs) {
            // Anything else is a fault in this code or in the message, and no redelivery will change the answer.
            // The previous version treated every RuntimeException as retryable, which meant one programming
            // error — or one hostile header — became an unbounded requeue loop occupying a consumer thread.
            LOGGER.atError()
                    .addKeyValue("event", "DISPATCH_CONSUMPTION_FAILED")
                    .addKeyValue("exceptionType", theirs.getClass().getName())
                    .log("Refused a delivery permanently after a non-retryable failure");
            throw new AmqpRejectAndDontRequeueException("dispatch consumption failed non-retryably", theirs);
        }

        if (disposition.expected()) {
            // Claimed and stale are ordinary. Acknowledged and forgotten; a stale cancellation or queue timeout
            // is the distributed system working, not poison, and dead-lettering it would fill an operator's
            // queue with events that need no operator.
            return;
        }
        // Rejected and conflicting messages are decided, so requeueing them would loop forever. They are refused
        // without requeue instead, which routes them to the dead-letter exchange for someone to look at.
        throw new AmqpRejectAndDontRequeueException(
                "dispatch permanently refused with disposition " + disposition.name());
    }

    /**
     * Returns a delivery to the broker after a failure of ours.
     *
     * <p>The pause is what stops that becoming a hot loop. While the database is unreachable every delivery
     * fails immediately, and an immediate requeue would spin the consumer, the broker, and the log at full speed
     * for the length of the outage. Dead-lettering instead would be worse: there is nothing wrong with the
     * message, and discarding real work to protect a loop is the wrong trade.
     */
    private RuntimeException deferToBroker(RuntimeException failure) {
        LOGGER.atWarn()
                .addKeyValue("event", "DISPATCH_CONSUMPTION_DEFERRED")
                .addKeyValue("exceptionType", failure.getClass().getName())
                .log("Returned a delivery to the broker after a transient failure");
        pauseBeforeRequeue();
        return failure;
    }

    /**
     * A transport header as a string, whatever the publisher actually sent.
     *
     * <p>AMQP field-table values are not necessarily strings — an integer, or any value over the converter's
     * long-string threshold, arrives as something else — and {@code getHeader} is an unchecked cast, so reading
     * one directly throws before the message is ever a message. A hostile header would then destroy the delivery
     * with no durable decision, no metric, and a log line describing the opposite of what happened. Coercing here
     * turns it into an ordinary recorded refusal.
     */
    private static String stringHeader(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        return NOT_A_HEADER;
    }

    /** Matches no supported value, so a non-string header fails the contract check like any other wrong value. */
    private static final String NOT_A_HEADER = "\u0000";

    private void pauseBeforeRequeue() {
        if (transientBackoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(transientBackoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The transport's claimed identity, used only to correlate and to record an unparseable message. It is never
     * preferred over the body: the body is what the semantic digest covers, and a header that disagrees with it
     * is a reason to reject the message rather than to believe the header.
     */
    private static UUID transportMessageId(Message message) {
        String candidate = message.getMessageProperties().getMessageId();
        if (candidate == null) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
