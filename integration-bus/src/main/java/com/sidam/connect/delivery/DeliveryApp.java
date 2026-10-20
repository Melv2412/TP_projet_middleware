package com.sidam.connect.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sidam.connect.model.DeliveryMissionMessage;
import com.sidam.connect.model.DeliveryStatusMessage;
import com.sidam.connect.model.DeliveryStatusMessage.Status;
import com.sidam.connect.translate.OrderTranslator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simulation du back-end de l'application mobile SIDAM Delivery.
 * C'est un système externe : il reçoit ses missions et renvoie ses statuts uniquement
 * par RabbitMQ (JSON), jamais en appelant le bus directement.
 */
@Component
public class DeliveryApp {

    /** Résultat d'une action du livreur. */
    public enum ActionResult { DONE, NOT_FOUND, INVALID_TRANSITION }

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final OrderTranslator translator;

    @Value("${sidam.rabbit.exchange}")
    private String exchange;
    @Value("${sidam.rabbit.delivery-status-routing-key}")
    private String statusRoutingKey;

    public DeliveryApp(JdbcTemplate jdbc, RabbitTemplate rabbit, OrderTranslator translator) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.translator = translator;
    }

    /** Réception d'une mission (consommée depuis sidam.delivery.assigned). Idempotent par commande. */
    @Transactional
    public void receiveMission(String json) throws JsonProcessingException {
        DeliveryMissionMessage mission = translator.parseJson(json, DeliveryMissionMessage.class);
        Long n = jdbc.queryForObject("SELECT nextval('delivery.mission_seq')", Long.class);
        String missionId = String.format("LIV-%d-%04d", Year.now().getValue(), n);
        int created = jdbc.update("""
                INSERT INTO delivery.mission (mission_id, order_id, correlation_id, customer_id, address, parcels, amount, status)
                VALUES (?, ?, ?::uuid, ?, ?, ?, ?, 'ASSIGNED')
                ON CONFLICT (order_id) DO NOTHING
                """, missionId, mission.getOrderId(), mission.getCorrelationId(), mission.getClient(),
                mission.getAdresse(), translator.toJson(mission.getColis()), mission.getMontant());
        if (created == 1) {
            publish(new DeliveryStatusMessage(missionId, mission.getOrderId(), mission.getCorrelationId(),
                    Status.ASSIGNED, null, Instant.now()));
        }
    }

    /** Le livreur prend en charge la mission. */
    @Transactional
    public ActionResult pickUp(String missionId, String driver) {
        return transition(missionId, Status.ASSIGNED, Status.IN_DELIVERY, driver,
                "UPDATE delivery.mission SET status = 'IN_DELIVERY', driver = ?, picked_up_at = now() WHERE mission_id = ? AND status = 'ASSIGNED'");
    }

    /** Le livreur confirme la remise au client. */
    @Transactional
    public ActionResult confirm(String missionId) {
        return transition(missionId, Status.IN_DELIVERY, Status.DELIVERED, null,
                "UPDATE delivery.mission SET status = 'DELIVERED', delivered_at = now() WHERE mission_id = ? AND status = 'IN_DELIVERY'");
    }

    private ActionResult transition(String missionId, Status from, Status to, String driver, String sql) {
        Optional<Map<String, Object>> mission = find(missionId);
        if (mission.isEmpty()) {
            return ActionResult.NOT_FOUND;
        }
        int updated = driver != null ? jdbc.update(sql, driver, missionId) : jdbc.update(sql, missionId);
        if (updated == 0) {
            return ActionResult.INVALID_TRANSITION;
        }
        Map<String, Object> m = find(missionId).orElseThrow();
        publish(new DeliveryStatusMessage(missionId, (String) m.get("order_id"), m.get("correlation_id").toString(),
                to, (String) m.get("driver"), Instant.now()));
        return ActionResult.DONE;
    }

    /** Envoie un statut au bus (sidam.delivery.status), comme le ferait l'app mobile. */
    public void publish(DeliveryStatusMessage status) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader("orderId", status.getOrderId());
        props.setHeader("correlationId", status.getCorrelationId());
        rabbit.send(exchange, statusRoutingKey,
                new Message(translator.toJson(status).getBytes(StandardCharsets.UTF_8), props));
    }

    public List<Map<String, Object>> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.queryForList("SELECT * FROM delivery.mission ORDER BY assigned_at DESC");
        }
        return jdbc.queryForList("SELECT * FROM delivery.mission WHERE status = ? ORDER BY assigned_at DESC", status);
    }

    public Optional<Map<String, Object>> find(String missionId) {
        return jdbc.queryForList("SELECT * FROM delivery.mission WHERE mission_id = ?", missionId).stream().findFirst();
    }

    public Optional<Map<String, Object>> findByOrder(String orderId) {
        return jdbc.queryForList("SELECT * FROM delivery.mission WHERE order_id = ?", orderId).stream().findFirst();
    }
}
