package com.kaas.api.outbox.infrastructure;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The minimum topology dispatch transport needs: one durable direct exchange, one durable queue, one routing key.
 *
 * <p>Deliberately absent: per-tenant or per-project queues, dynamic queue creation, topic taxonomies, priority
 * queues, the delayed-message plugin, and quorum or stream queues. None has a use case yet, and every one of them
 * would be a migration to undo. Retry timing lives in PostgreSQL, not in a broker delay mechanism, so no optional
 * plugin is required.
 *
 * <p>Declaration happens when a connection is first established, not at application startup, so a broker outage
 * does not prevent the API from serving control-plane reads.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfiguration {

    @Bean
    DirectExchange dispatchExchange(@Value("${kaas.outbox.rabbit.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue executionDispatchQueue(@Value("${kaas.outbox.rabbit.queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding executionDispatchBinding(
            DirectExchange dispatchExchange,
            Queue executionDispatchQueue,
            @Value("${kaas.outbox.rabbit.routing-key}") String routingKey) {
        return BindingBuilder.bind(executionDispatchQueue).to(dispatchExchange).with(routingKey);
    }
}
