package com.sidam.connect.api;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.erp.LegacyErpAdapter;
import com.sidam.connect.model.StoreOrderMessage;
import com.sidam.connect.order.OrderRepository;
import org.apache.camel.ProducerTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** Outils réservés à la démonstration (actifs seulement si DEMO_MODE=true). */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final EventLog events;
    private final ProducerTemplate camel;
    private final LegacyErpAdapter erp;
    private final RabbitTemplate rabbit;
    private final boolean demoMode;

    @Value("${sidam.rabbit.exchange}")
    private String ordersExchange;
    @Value("${sidam.rabbit.orders-routing-key}")
    private String ordersRoutingKey;
    @Value("${sidam.rabbit.dead-letter-queue}")
    private String deadLetterQueue;

    public DemoController(JdbcTemplate jdbc, OrderRepository orders, EventLog events, ProducerTemplate camel,
                          LegacyErpAdapter erp, RabbitTemplate rabbit,
                          @Value("${sidam.demo-mode}") boolean demoMode) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.events = events;
        this.camel = camel;
        this.erp = erp;
        this.rabbit = rabbit;
        this.demoMode = demoMode;
    }

    /** Remet le stock initial et efface commandes, factures et journal. */
    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Map<String, String>> reset() {
        if (!demoMode) {
            return forbidden();
        }
        jdbc.execute("TRUNCATE erp.invoice, erp.erp_order_line, erp.erp_order RESTART IDENTITY");
        jdbc.update("UPDATE erp.inventory SET available = initial_quantity, reserved = 0, updated_at = now()");
        jdbc.execute("TRUNCATE bus.integration_event, bus.order_item, bus.orders, bus.processed_message, bus.dead_letter RESTART IDENTITY");
        jdbc.execute("ALTER SEQUENCE bus.order_number_seq RESTART WITH 1");
        jdbc.execute("TRUNCATE delivery.mission");
        jdbc.execute("ALTER SEQUENCE delivery.mission_seq RESTART WITH 1");
        rabbit.execute(channel -> channel.queuePurge(deadLetterQueue));
        erp.setAvailable(true);
        return ResponseEntity.ok(Map.of("status", "Jeu de démonstration réinitialisé"));
    }

    /** Simulation Lab : coupe l'ERP legacy (toute requête vers lui échoue). */
    @PostMapping("/erp/down")
    public ResponseEntity<Map<String, Object>> erpDown() {
        if (!demoMode) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "DEMO_MODE désactivé"));
        }
        erp.setAvailable(false);
        return erpStatus();
    }

    /** Simulation Lab : rétablit l'ERP legacy. */
    @PostMapping("/erp/up")
    public ResponseEntity<Map<String, Object>> erpUp() {
        erp.setAvailable(true);
        return erpStatus();
    }

    @GetMapping("/erp")
    public ResponseEntity<Map<String, Object>> erpStatus() {
        return ResponseEntity.ok(Map.of("erpAvailable", erp.isAvailable()));
    }

    /**
     * Injecte directement dans RabbitMQ un message corrompu, comme le ferait un système défaillant.
     * type=malformed : JSON cassé ; type=incomplete : JSON valide mais sans article.
     */
    @PostMapping("/corrupt-message")
    public ResponseEntity<Map<String, String>> corruptMessage(@RequestParam(defaultValue = "malformed") String type) {
        if (!demoMode) {
            return forbidden();
        }
        String payload = "incomplete".equals(type)
                ? "{\"orderId\":\"CMD-CORROMPU\",\"correlationId\":\"00000000-0000-0000-0000-000000000000\",\"items\":[]}"
                : "{\"orderId\": \"CMD-CORROMPU\", \"items\": [ {\"sku\": \"TV-SAM-55\", \"quantity\": ";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(ordersExchange, ordersRoutingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), props));
        return ResponseEntity.accepted().body(Map.of("status", "Message corrompu (" + type + ") injecté dans RabbitMQ"));
    }

    /**
     * Simule un doublon réseau : republie dans RabbitMQ exactement le même message
     * (même orderId, même correlationId). L'Idempotent Consumer doit l'ignorer.
     */
    @PostMapping("/orders/{orderId}/duplicate")
    public ResponseEntity<Map<String, String>> duplicate(@PathVariable String orderId) {
        if (!demoMode) {
            return forbidden();
        }
        Optional<StoreOrderMessage> msg = orders.findAsStoreMessage(orderId);
        if (msg.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        events.record(IntegrationEvent.of(orderId, msg.get().getCorrelationId(), "DUPLICATE_SENT")
                .route("demo", "Simulation")
                .flow("STORE", "BUS")
                .message("Simulation : le même message est renvoyé une seconde fois"));
        camel.sendBody("direct:submitOrder", msg.get());
        return ResponseEntity.accepted().body(Map.of("status", "Doublon publié pour " + orderId));
    }

    /** Prépare un stock de test, ex. 1 seul article pour démontrer la concurrence. */
    @PostMapping("/stock/{sku}")
    public ResponseEntity<?> setStock(@PathVariable String sku, @RequestParam int available) {
        if (!demoMode) {
            return forbidden();
        }
        if (available < 0) {
            throw new IllegalArgumentException("Le stock ne peut pas être négatif");
        }
        int updated = jdbc.update("UPDATE erp.inventory SET available = ?, updated_at = now() WHERE sku = ?", available, sku);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("sku", sku, "available", available));
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "DEMO_MODE désactivé"));
    }
}
