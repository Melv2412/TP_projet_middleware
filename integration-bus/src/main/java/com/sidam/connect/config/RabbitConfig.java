package com.sidam.connect.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Déclare la Dead Letter Queue au démarrage (exchange sidam.dlx → file durable sidam.orders.dlq).
 * La file principale est déclarée par la route Camel elle-même.
 */
@Configuration
public class RabbitConfig {

    @Bean
    Declarables deadLetterTopology(@Value("${sidam.rabbit.dead-letter-exchange}") String exchange,
                                   @Value("${sidam.rabbit.dead-letter-queue}") String queue,
                                   @Value("${sidam.rabbit.dead-letter-routing-key}") String routingKey) {
        DirectExchange dlx = new DirectExchange(exchange, true, false);
        Queue dlq = QueueBuilder.durable(queue).build();
        Binding binding = BindingBuilder.bind(dlq).to(dlx).with(routingKey);
        return new Declarables(dlx, dlq, binding);
    }
}
