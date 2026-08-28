package com.kaas.api.outbox.infrastructure;

import com.kaas.api.outbox.application.DispatchPublisher;
import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.PublishOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes one already-durable message and waits for a positive broker confirmation.
 *
 * <p>A normal return from {@code send} means nothing: the message is only treated as published once the broker
 * acknowledges it. Messages are persistent and published with the mandatory flag, so a message that no queue
 * would accept comes back as a return rather than being silently discarded.
 */
@Component
class RabbitDispatchPublisher implements DispatchPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitDispatchPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final Duration confirmTimeout;

    RabbitDispatchPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${kaas.outbox.rabbit.exchange}") String exchange,
            @Value("${kaas.outbox.rabbit.routing-key}") String routingKey,
            @Value("${kaas.outbox.rabbit.confirm-timeout}") Duration confirmTimeout) {
        if (confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("A publisher confirm timeout is required.");
        }
        // Without correlated confirms the confirmation future is never completed, so every message would wait the
        // full timeout and the whole backlog would dead-letter with no obvious cause. The entire reliability
        // story rests on this being enabled, so it is asserted at startup rather than assumed.
        var connectionFactory = rabbitTemplate.getConnectionFactory();
        if (!connectionFactory.isPublisherConfirms()) {
            throw new IllegalStateException(
                    "Correlated publisher confirms must be enabled; a message is only published once confirmed.");
        }
        if (!connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException(
                    "Publisher returns must be enabled; an unroutable message must be detected, not discarded.");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public PublishOutcome publish(OutboxMessage message) {
        CorrelationData correlation = new CorrelationData(message.messageId().toString());
        try {
            rabbitTemplate.send(exchange, routingKey, transportMessage(message), correlation);
        } catch (AmqpException brokerUnreachable) {
            return PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // The broker may still have accepted it. Retrying is safe precisely because delivery is at least once.
            return PublishOutcome.transientFailure(FailureCode.CONFIRM_TIMEOUT);
        } catch (ExecutionException failed) {
            return PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PublishOutcome.transientFailure(FailureCode.BROKER_UNAVAILABLE);
        }

        // A mandatory message that reached no queue is returned before it is acknowledged, so this is checked
        // after the confirmation rather than instead of it.
        if (correlation.getReturned() != null) {
            return PublishOutcome.transientFailure(FailureCode.UNROUTABLE);
        }
        if (confirm == null || !confirm.ack()) {
            // The broker's reason is the only diagnostic a nack carries. It is unbounded text, so it is logged
            // and never persisted or used as a metric dimension.
            LOGGER.atWarn()
                    .addKeyValue("event", "OUTBOX_PUBLISH_NACKED")
                    .addKeyValue("messageId", message.messageId())
                    .addKeyValue("brokerReason", confirm == null ? "unknown" : confirm.reason())
                    .log("Broker refused the publication");
            return PublishOutcome.transientFailure(FailureCode.PUBLISH_NACKED);
        }
        return PublishOutcome.confirmed();
    }

    /**
     * The body is the immutable DispatchIntent exactly as persisted. Properties carry only transport metadata and
     * grant no execution authority: no assignment epoch, worker identity, lease, capability, or credential.
     */
    private Message transportMessage(OutboxMessage message) {
        return MessageBuilder.withBody(message.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json; charset=utf-8")
                .setMessageId(message.messageId().toString())
                .setCorrelationId(message.runId().toString())
                .setTimestamp(java.util.Date.from(message.occurredAt()))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("messageType", message.messageType())
                .setHeader("schemaVersion", message.schemaVersion())
                .setHeader("payloadDigest", message.payloadDigest())
                .build();
    }
}
