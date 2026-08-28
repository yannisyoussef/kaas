package com.kaas.api.outbox.infrastructure;

import java.time.Duration;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
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
 *
 * <h2>Dead lettering, and a deployment hazard</h2>
 *
 * <p>Consumer-side dead lettering is a different problem from publisher terminal failure, and the two must not
 * share a destination. A publisher dead letter means the control plane could not get a message onto the broker;
 * it lives in {@code outbox_messages.terminal_disposition} and never reaches RabbitMQ at all. A consumer dead
 * letter means a message arrived and could not be believed — malformed, unsupported, or an identity conflict.
 * Only the second is routed here.
 *
 * <p>Ordinary stale messages are deliberately <em>not</em> dead-lettered. A dispatch that arrives for a run
 * that was cancelled or timed out while it was in flight is expected distributed-system behaviour, not poison,
 * and routing it to an operator queue would bury the real failures under events nobody needs to look at.
 *
 * <p><strong>Adding the dead-letter arguments changes the dispatch queue's declaration.</strong> RabbitMQ refuses
 * to redeclare an existing queue with different arguments and fails the channel with {@code PRECONDITION_FAILED},
 * so this is not a transparent addition: an environment that already declared the queue without them must delete
 * and recreate it. No deployment exists yet, which is the only reason this is safe to do now rather than under a
 * new queue name.
 *
 * <p>The failure mode of getting that wrong is worse than not consuming, which is why two things below are not
 * optional. {@link org.springframework.amqp.rabbit.core.RabbitAdmin} declares everything on one channel, so a
 * rejected queue redeclaration kills the channel and silently abandons every declaration after it — including the
 * dead-letter queue and its binding. The consumer then starts anyway, refuses a message, and the broker drops it
 * on the floor because the exchange it was dead-lettered to has nothing bound. Dead-lettering would fail open,
 * silently, in exactly the situation it exists for. So the dead-letter topology is declared through its own
 * {@link Declarables} set, and a mismatched dispatch queue stops the listener from starting at all.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfiguration {

    @Bean
    DirectExchange dispatchExchange(@Value("${kaas.outbox.rabbit.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue executionDispatchQueue(
            @Value("${kaas.outbox.rabbit.queue}") String queue,
            @Value("${kaas.outbox.rabbit.dead-letter-exchange}") String deadLetterExchange) {
        return QueueBuilder.durable(queue).deadLetterExchange(deadLetterExchange).build();
    }

    /**
     * The dead-letter topology, declared as one unit and independently of the dispatch queue.
     *
     * <p>Grouped deliberately: these must survive a failed redeclaration of the dispatch queue, because that is
     * precisely when a dead letter is about to be produced and has nowhere to go.
     */
    @Bean
    Declarables dispatchDeadLetterTopology(
            @Value("${kaas.outbox.rabbit.dead-letter-exchange}") String deadLetterExchange,
            @Value("${kaas.outbox.rabbit.dead-letter-queue}") String deadLetterQueue,
            @Value("${kaas.outbox.rabbit.dead-letter-max-messages}") int maxMessages,
            @Value("${kaas.outbox.rabbit.dead-letter-ttl}") Duration ttl) {
        // Fanout, because a dead letter has no routing decision left to make: it failed, and it goes to the one
        // place an operator looks. A direct exchange would need the original routing key to survive rejection,
        // which is a detail to get wrong for no benefit.
        FanoutExchange exchange = new FanoutExchange(deadLetterExchange, true, false);
        Queue queue = QueueBuilder.durable(deadLetterQueue)
                // Bounded, because anyone who can publish to the dispatch exchange can fill this queue — an
                // oversized body is refused before parsing but still arrives, and is dead-lettered whole. An
                // unbounded queue turns that into a broker disk alarm, which blocks every publisher on the node
                // including the relay, and the control plane stops dispatching entirely.
                .withArgument("x-max-length", maxMessages)
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-message-ttl", (int) ttl.toMillis())
                // No dead-letter exchange of its own: a dead letter that could itself be dead-lettered is a loop.
                // Nothing consumes this queue automatically; it is read by a person.
                .build();
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange));
    }



    @Bean
    Binding executionDispatchBinding(
            DirectExchange dispatchExchange,
            Queue executionDispatchQueue,
            @Value("${kaas.outbox.rabbit.routing-key}") String routingKey) {
        return BindingBuilder.bind(executionDispatchQueue).to(dispatchExchange).with(routingKey);
    }
}
