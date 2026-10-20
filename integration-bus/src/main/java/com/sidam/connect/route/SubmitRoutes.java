package com.sidam.connect.route;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.model.StoreOrderMessage;
import com.sidam.connect.translate.OrderTranslator;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.sidam.connect.route.Headers.CORRELATION_ID;
import static com.sidam.connect.route.Headers.ORDER_ID;

/**
 * Publication d'une commande dans RabbitMQ (EIP Message Channel).
 * Pas de gestionnaire d'erreur : si RabbitMQ est injoignable, l'erreur remonte
 * directement à l'API, qui répond 503 au Store.
 */
@Component
public class SubmitRoutes extends RouteBuilder {

    private final OrderTranslator translator;
    private final EventLog events;

    @Value("${sidam.rabbit.exchange}")
    private String exchangeName;
    @Value("${sidam.rabbit.orders-queue}")
    private String ordersQueue;
    @Value("${sidam.rabbit.orders-routing-key}")
    private String ordersRoutingKey;

    public SubmitRoutes(OrderTranslator translator, EventLog events) {
        this.translator = translator;
        this.events = events;
    }

    @Override
    public void configure() {
        errorHandler(noErrorHandler());

        from("direct:submitOrder").routeId("order-submit")
                .process(e -> {
                    StoreOrderMessage msg = e.getIn().getBody(StoreOrderMessage.class);
                    e.getIn().setHeader(ORDER_ID, msg.getOrderId());
                    e.getIn().setHeader(CORRELATION_ID, msg.getCorrelationId());
                    e.getIn().setBody(translator.toJson(msg));
                })
                // Envoi simple (fire-and-forget) : sans cela, un appelant en mode requête-réponse
                // (ex. import CSV) ferait attendre une réponse RabbitMQ qui n'arrive jamais.
                .setExchangePattern(ExchangePattern.InOnly)
                .to("spring-rabbitmq:" + exchangeName + "?routingKey=" + ordersRoutingKey)
                .process(e -> events.record(IntegrationEvent.of(
                                e.getIn().getHeader(ORDER_ID, String.class),
                                e.getIn().getHeader(CORRELATION_ID, String.class), "PUBLISHED")
                        .route("order-submit", "Message Channel")
                        .flow("BUS", "RABBITMQ")
                        .message("Commande publiée dans la file " + ordersQueue)));
    }
}
