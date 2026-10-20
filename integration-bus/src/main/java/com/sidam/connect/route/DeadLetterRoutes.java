package com.sidam.connect.route;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.dlq.DeadLetterService;
import com.sidam.connect.erp.ErpUnavailableException;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.order.OrderRepository;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sidam.connect.route.Headers.CORRELATION_ID;
import static com.sidam.connect.route.Headers.DLQ_ID;
import static com.sidam.connect.route.Headers.ERROR_CODE;
import static com.sidam.connect.route.Headers.ERROR_REASON;
import static com.sidam.connect.route.Headers.ERR_ERP_UNAVAILABLE;
import static com.sidam.connect.route.Headers.ERR_TECHNICAL;
import static com.sidam.connect.route.Headers.ORDER_ID;
import static com.sidam.connect.route.Headers.PROP_REPLAY_ROUTING_KEY;
import static com.sidam.connect.route.Headers.REPLAY_ROUTING_KEY;

/**
 * EIP Dead Letter Channel : le message en échec est isolé dans la file RabbitMQ
 * sidam.orders.dlq avec son motif. Il pourra être rejoué depuis la console (Replay).
 */
@Component
public class DeadLetterRoutes extends RouteBuilder {

    private static final Pattern ORDER_ID_IN_JSON = Pattern.compile("\"orderId\"\\s*:\\s*\"(CMD-[0-9-]+)\"");

    private final DeadLetterService deadLetters;
    private final OrderRepository orders;
    private final EventLog events;
    private final JdbcIdempotentRepository idempotentRepository;

    @Value("${sidam.rabbit.dead-letter-exchange}")
    private String deadLetterExchange;
    @Value("${sidam.rabbit.dead-letter-routing-key}")
    private String deadLetterRoutingKey;
    @Value("${sidam.rabbit.dead-letter-queue}")
    private String deadLetterQueue;
    @Value("${sidam.rabbit.orders-routing-key}")
    private String ordersRoutingKey;

    public DeadLetterRoutes(DeadLetterService deadLetters, OrderRepository orders, EventLog events,
                            JdbcIdempotentRepository idempotentRepository) {
        this.deadLetters = deadLetters;
        this.orders = orders;
        this.events = events;
        this.idempotentRepository = idempotentRepository;
    }

    @Override
    public void configure() {
        from("direct:deadLetter").routeId("dead-letter")
                .convertBodyTo(String.class)
                .process(this::prepare)
                .to("spring-rabbitmq:" + deadLetterExchange + "?routingKey=" + deadLetterRoutingKey)
                .process(this::record);
    }

    private void prepare(Exchange e) {
        Throwable cause = e.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String code = e.getIn().getHeader(ERROR_CODE, String.class);
        if (code == null) {
            code = cause instanceof ErpUnavailableException ? ERR_ERP_UNAVAILABLE : ERR_TECHNICAL;
        }
        String reason = cause == null ? "erreur inconnue" : cause.getClass().getSimpleName() + " : " + cause.getMessage();

        // Un message illisible n'a pas forcément d'en-tête orderId : on tente de le retrouver dans le texte.
        if (e.getIn().getHeader(ORDER_ID) == null) {
            Matcher m = ORDER_ID_IN_JSON.matcher(e.getIn().getBody(String.class));
            if (m.find()) {
                e.getIn().setHeader(ORDER_ID, m.group(1));
            }
        }
        e.getIn().setHeader(DLQ_ID, UUID.randomUUID().toString());
        e.getIn().setHeader(ERROR_CODE, code);
        e.getIn().setHeader(ERROR_REASON, reason);
        // File d'origine : c'est là que le message retournera s'il est rejoué.
        String replayKey = e.getProperty(PROP_REPLAY_ROUTING_KEY, String.class);
        e.getIn().setHeader(REPLAY_ROUTING_KEY, replayKey == null ? ordersRoutingKey : replayKey);
    }

    private void record(Exchange e) {
        String dlqId = e.getIn().getHeader(DLQ_ID, String.class);
        String orderId = e.getIn().getHeader(ORDER_ID, String.class);
        String correlationId = e.getIn().getHeader(CORRELATION_ID, String.class);
        String code = e.getIn().getHeader(ERROR_CODE, String.class);
        String reason = e.getIn().getHeader(ERROR_REASON, String.class);
        String failedRoute = e.getProperty(Exchange.FAILURE_ROUTE_ID, String.class);
        String replayKey = e.getIn().getHeader(REPLAY_ROUTING_KEY, String.class);

        deadLetters.index(dlqId, orderId, correlationId, code, reason, failedRoute, e.getIn().getBody(String.class), replayKey);

        // Le motif est journalisé avant le changement de statut : qui voit FAILED voit aussi pourquoi.
        events.record(IntegrationEvent.of(orderId, correlationId, "DEAD_LETTERED")
                .route(failedRoute, "Dead Letter Channel")
                .flow("BUS", "DLQ")
                .message("Message isolé dans " + deadLetterQueue + " (" + code + ") : " + reason)
                .payload(e.getIn().getBody(String.class), "JSON")
                .error(code));

        // Seul l'échec du traitement de la commande la met en FAILED. Un statut de livraison
        // en échec laisse la commande à son état actuel : il sera rejoué tel quel.
        if (orderId != null && ordersRoutingKey.equals(replayKey)) {
            // Le message n'a pas été traité : il doit pouvoir l'être lors du Replay.
            idempotentRepository.remove("ORDER_SUBMITTED:" + orderId);
            orders.updateStatus(orderId, OrderStatus.FAILED, reason);
        }
    }
}
