package com.kaas.api.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.OutboxMessage;
import com.kaas.api.outbox.domain.PublishStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Direct coverage of the transport edge. The integration suite proves the happy path and the unroutable path
 * against a real broker, but a NACK, a confirm timeout, and a connection failure cannot be produced reliably
 * against a healthy container — and this is the one class whose contract is that returning normally without a
 * confirmation must never be reported as published.
 */
class RabbitDispatchPublisherTest {
    private static final String EXCHANGE = "kaas.dispatch";
    private static final String ROUTING_KEY = "execution-dispatch";

    @Test
    void aMessageIsPublishedOnlyWhenTheBrokerPositivelyAcknowledgesIt() {
        var outcome = publishWith(correlation -> correlation.getFuture()
                .complete(new CorrelationData.Confirm(true, null)));

        assertThat(outcome.status()).isEqualTo(PublishStatus.CONFIRMED);
        assertThat(outcome.failureCode()).isNull();
    }

    @Test
    void aNegativeAcknowledgementIsTransientAndNeverCountsAsPublished() {
        var outcome = publishWith(correlation -> correlation.getFuture()
                .complete(new CorrelationData.Confirm(false, "NOT_FOUND - no exchange")));

        assertThat(outcome.status()).isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThat(outcome.failureCode()).isEqualTo(FailureCode.PUBLISH_NACKED);
    }

    @Test
    void aConfirmationThatNeverArrivesTimesOutInsteadOfBeingTreatedAsSuccess() {
        // The future is deliberately left uncompleted: this is exactly what happens when publisher confirms are
        // misconfigured, and it must never be mistaken for a successful publication.
        var outcome = publishWith(correlation -> {});

        assertThat(outcome.status()).isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThat(outcome.failureCode()).isEqualTo(FailureCode.CONFIRM_TIMEOUT);
    }

    @Test
    void aFailedConfirmationFutureIsTreatedAsABrokerOutage() {
        var outcome = publishWith(correlation ->
                correlation.getFuture().completeExceptionally(new IllegalStateException("channel closed")));

        assertThat(outcome.status()).isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThat(outcome.failureCode()).isEqualTo(FailureCode.BROKER_UNAVAILABLE);
    }

    @Test
    void anUnreachableBrokerIsTransientRatherThanLosingTheMessage() {
        RabbitTemplate template = template();
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        var outcome = publisher(template, Duration.ofSeconds(1)).publish(message());

        assertThat(outcome.status()).isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThat(outcome.failureCode()).isEqualTo(FailureCode.BROKER_UNAVAILABLE);
    }

    @Test
    void aReturnedMessageIsUnroutableEvenThoughTheBrokerAlsoAcknowledgedIt() {
        // The broker returns an unroutable message before acknowledging it, so the return must be checked after
        // the confirmation rather than instead of it. An ack alone would otherwise look like success.
        var outcome = publishWith(correlation -> {
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", EXCHANGE, ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThat(outcome.status()).isEqualTo(PublishStatus.TRANSIENT_FAILURE);
        assertThat(outcome.failureCode()).isEqualTo(FailureCode.UNROUTABLE);
    }

    @Test
    void theTransportMessageCarriesOnlyIdentityAndNoExecutionAuthority() {
        RabbitTemplate template = template();
        Message[] captured = new Message[1];
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(2, Message.class);
            invocation.getArgument(3, CorrelationData.class)
                    .getFuture()
                    .complete(new CorrelationData.Confirm(true, null));
            return null;
        })
                .when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxMessage message = message();
        publisher(template, Duration.ofSeconds(1)).publish(message);

        var properties = captured[0].getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(message.messageId().toString());
        assertThat(properties.getCorrelationId()).isEqualTo(message.runId().toString());
        assertThat(properties.getDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat(properties.getContentType()).isEqualTo("application/json; charset=utf-8");
        // Exactly the headers this contract declares. A denylist would not catch an accidental addition.
        assertThat(properties.getHeaders().keySet())
                .containsExactlyInAnyOrder("messageType", "schemaVersion", "payloadDigest");
    }

    @Test
    void theRelayRefusesToStartWithoutConfirmsReturnsOrAUsableTimeout() {
        RabbitTemplate withoutConfirms = template();
        when(withoutConfirms.getConnectionFactory().isPublisherConfirms()).thenReturn(false);
        assertThatThrownBy(() -> publisher(withoutConfirms, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirms");

        RabbitTemplate withoutReturns = template();
        when(withoutReturns.getConnectionFactory().isPublisherReturns()).thenReturn(false);
        assertThatThrownBy(() -> publisher(withoutReturns, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returns");

        assertThatThrownBy(() -> publisher(template(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private com.kaas.api.outbox.domain.PublishOutcome publishWith(Consumer<CorrelationData> brokerBehaviour) {
        RabbitTemplate template = template();
        doAnswer(invocation -> {
            brokerBehaviour.accept(invocation.getArgument(3, CorrelationData.class));
            return null;
        })
                .when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        return publisher(template, Duration.ofMillis(200)).publish(message());
    }

    private static RabbitDispatchPublisher publisher(RabbitTemplate template, Duration confirmTimeout) {
        return new RabbitDispatchPublisher(template, EXCHANGE, ROUTING_KEY, confirmTimeout);
    }

    private static RabbitTemplate template() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        when(connectionFactory.isPublisherReturns()).thenReturn(true);
        when(template.getConnectionFactory()).thenReturn(connectionFactory);
        return template;
    }

    private static OutboxMessage message() {
        UUID runId = UUID.randomUUID();
        return new OutboxMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "EXECUTION_DISPATCH",
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID(),
                runId,
                UUID.randomUUID(),
                "{\"runId\":\"" + runId + "\"}",
                "sha256:" + "a".repeat(64),
                Instant.parse("2026-08-28T12:00:00Z"),
                0);
    }
}
