package com.sidam.connect.dlq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.order.OrderRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sidam.connect.route.Headers.DLQ_ID;
import static com.sidam.connect.route.Headers.ERROR_CODE;
import static com.sidam.connect.route.Headers.ERROR_REASON;
import static com.sidam.connect.route.Headers.REPLAY_ROUTING_KEY;

/**
 * Gestion de la Dead Letter Queue : index en base (affichage) et Replay.
 * Le message lui-même vit dans la file RabbitMQ sidam.orders.dlq.
 */
@Service
public class DeadLetterService {

    /** Résultat d'une demande de rejeu. */
    public enum ReplayResult { REPLAYED, ALREADY_REPLAYED, NOT_FOUND }

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final OrderRepository orders;
    private final EventLog events;

    @Value("${sidam.rabbit.exchange}")
    private String ordersExchange;
    @Value("${sidam.rabbit.orders-routing-key}")
    private String ordersRoutingKey;
    @Value("${sidam.rabbit.dead-letter-exchange}")
    private String deadLetterExchange;
    @Value("${sidam.rabbit.dead-letter-queue}")
    private String deadLetterQueue;
    @Value("${sidam.rabbit.dead-letter-routing-key}")
    private String deadLetterRoutingKey;

    public DeadLetterService(JdbcTemplate jdbc, RabbitTemplate rabbit, OrderRepository orders, EventLog events) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.orders = orders;
        this.events = events;
    }

    public void index(String dlqId, String orderId, String correlationId, String errorCode, String reason,
                      String failedRoute, String payload, String replayRoutingKey) {
        jdbc.update("""
                INSERT INTO bus.dead_letter
                  (dlq_id, order_id, correlation_id, error_code, reason, failed_route, payload, replay_routing_key)
                VALUES (?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?)
                """, dlqId, orderId, correlationId, errorCode, reason, failedRoute, payload, replayRoutingKey);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT dlq_id, order_id, correlation_id, error_code, reason, failed_route, replay_routing_key,
                       payload, created_at, replayed_at
                FROM bus.dead_letter
                ORDER BY replayed_at IS NOT NULL, created_at DESC
                """);
    }

    public long queueDepth() {
        return rabbit.execute(channel -> channel.messageCount(deadLetterQueue));
    }

    /**
     * Rejoue un message : il est retiré de la DLQ et republié dans la file des commandes,
     * sans ses en-têtes d'erreur. Les autres messages de la DLQ y sont remis en fin de file.
     * Chaque message n'est acquitté qu'après avoir été republié : rien ne peut être perdu.
     */
    public synchronized ReplayResult replay(String dlqId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, correlation_id, replayed_at, replay_routing_key FROM bus.dead_letter WHERE dlq_id = ?::uuid",
                dlqId);
        if (rows.isEmpty()) {
            return ReplayResult.NOT_FOUND;
        }
        if (rows.get(0).get("replayed_at") != null) {
            return ReplayResult.ALREADY_REPLAYED;
        }
        String replayRoutingKey = (String) rows.get(0).get("replay_routing_key");
        String orderId = (String) rows.get(0).get("order_id");
        Object correlationId = rows.get(0).get("correlation_id");

        // Tout est préparé AVANT de republier : sinon le bus pourrait déjà avoir traité le message
        // (statut INVOICED…) quand on remettrait la commande à RECEIVED.
        Runnable beforeRepublish = () -> {
            jdbc.update("UPDATE bus.dead_letter SET replayed_at = now() WHERE dlq_id = ?::uuid", dlqId);
            events.record(IntegrationEvent.of(orderId, correlationId == null ? null : correlationId.toString(), "REPLAYED")
                    .route("dlq-replay", "Message Store / Replay")
                    .flow("DLQ", "RABBITMQ")
                    .message("Message rejoué depuis la DLQ par l'opérateur"));
            if (orderId != null && ordersRoutingKey.equals(replayRoutingKey)) {
                orders.updateStatus(orderId, OrderStatus.RECEIVED, "Rejouée depuis la DLQ");
            }
        };

        boolean found = Boolean.TRUE.equals(rabbit.execute(channel -> {
            long count = channel.messageCount(deadLetterQueue);
            boolean match = false;
            for (long i = 0; i < count; i++) {
                GetResponse msg = channel.basicGet(deadLetterQueue, false);
                if (msg == null) {
                    break;
                }
                Map<String, Object> headers = msg.getProps().getHeaders() == null
                        ? new HashMap<>() : new HashMap<>(msg.getProps().getHeaders());
                if (!match && dlqId.equals(String.valueOf(headers.get(DLQ_ID)))) {
                    headers.remove(DLQ_ID);
                    headers.remove(ERROR_CODE);
                    headers.remove(ERROR_REASON);
                    headers.remove(REPLAY_ROUTING_KEY);
                    AMQP.BasicProperties props = msg.getProps().builder().headers(headers).deliveryMode(2).build();
                    beforeRepublish.run();
                    // Retour dans la file d'origine (commandes ou statuts de livraison).
                    channel.basicPublish(ordersExchange, replayRoutingKey, props, msg.getBody());
                    match = true;
                } else {
                    channel.basicPublish(deadLetterExchange, deadLetterRoutingKey, msg.getProps(), msg.getBody());
                }
                channel.basicAck(msg.getEnvelope().getDeliveryTag(), false);
            }
            return match;
        }));

        return found ? ReplayResult.REPLAYED : ReplayResult.NOT_FOUND;
    }

    public Optional<Map<String, Object>> find(String dlqId) {
        return jdbc.queryForList("SELECT * FROM bus.dead_letter WHERE dlq_id = ?::uuid", dlqId).stream().findFirst();
    }
}
