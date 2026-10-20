package com.sidam.connect.delivery;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Point d'entrée de l'app livreur : elle consomme les missions publiées par le bus.
 * (Côté système externe : ce n'est pas une route du bus.)
 */
@Component
public class DeliveryAppRoutes extends RouteBuilder {

    private final DeliveryApp app;

    @Value("${sidam.rabbit.exchange}")
    private String exchange;
    @Value("${sidam.rabbit.delivery-assigned-queue}")
    private String queue;
    @Value("${sidam.rabbit.delivery-assigned-routing-key}")
    private String routingKey;

    public DeliveryAppRoutes(DeliveryApp app) {
        this.app = app;
    }

    @Override
    public void configure() {
        from("spring-rabbitmq:" + exchange + "?queues=" + queue + "&routingKey=" + routingKey
                + "&autoDeclare=true&arg.queue.durable=true")
                .routeId("delivery-app-inbox")
                .convertBodyTo(String.class)
                .bean(app, "receiveMission");
    }
}
