package com.kaas.api.consumer.infrastructure;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes a topology the consumer cannot safely use a startup failure rather than a silent one.
 *
 * <p>Spring's default is to log a mismatched queue and carry on. That is the wrong default here: if the dispatch
 * queue was declared without the dead-letter exchange this code assumes, the container consumes happily and every
 * message it refuses is discarded by the broker instead of being dead-lettered. The consumer would look healthy
 * while quietly destroying exactly the messages an operator needs to see.
 *
 * <p>Refusing to start says the same thing loudly, at the only moment it can still be acted on.
 */
@Configuration(proxyBeanMethods = false)
class DispatchListenerConfiguration {

    @Bean
    @Primary
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMismatchedQueuesFatal(true);
        return factory;
    }
}
