package com.sidam.connect.api;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.erp.LegacyErpAdapter;
import com.sidam.connect.live.LiveEventBroadcaster;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Données de la Control Tower. Toutes les métriques sont calculées à partir
 * des enregistrements réels (commandes, événements, files RabbitMQ) — rien n'est codé en dur.
 */
@RestController
@RequestMapping("/api/tower")
public class TowerController {

    private final JdbcTemplate jdbc;
    private final EventLog events;
    private final RabbitTemplate rabbit;
    private final LegacyErpAdapter erp;
    private final CamelContext camel;
    private final LiveEventBroadcaster live;

    @Value("${sidam.rabbit.orders-queue}")
    private String ordersQueue;
    @Value("${sidam.rabbit.delivery-assigned-queue}")
    private String deliveryAssignedQueue;
    @Value("${sidam.rabbit.delivery-status-queue}")
    private String deliveryStatusQueue;
    @Value("${sidam.rabbit.dead-letter-queue}")
    private String deadLetterQueue;

    public TowerController(JdbcTemplate jdbc, EventLog events, RabbitTemplate rabbit, LegacyErpAdapter erp,
                           CamelContext camel, LiveEventBroadcaster live) {
        this.jdbc = jdbc;
        this.events = events;
        this.rabbit = rabbit;
        this.erp = erp;
        this.camel = camel;
        this.live = live;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> recentEvents(@RequestParam(defaultValue = "150") int limit) {
        return events.recent(Math.min(limit, 500));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT count(*)                                                                         AS total,
                       count(*) FILTER (WHERE status IN ('INVOICED','DELIVERY_ASSIGNED','IN_DELIVERY','DELIVERED')) AS accepted,
                       count(*) FILTER (WHERE status = 'DELIVERED')                                     AS delivered,
                       count(*) FILTER (WHERE status = 'REJECTED_OUT_OF_STOCK')                         AS rejected,
                       count(*) FILTER (WHERE status = 'FAILED')                                        AS failed,
                       count(*) FILTER (WHERE status IN ('RECEIVED','VALIDATING','RETRYING','STOCK_RESERVED')) AS in_transit,
                       count(*) FILTER (WHERE status IN ('DELIVERY_ASSIGNED','IN_DELIVERY'))            AS in_delivery
                FROM bus.orders
                """);
        m.putAll(counts);

        long total = ((Number) counts.get("total")).longValue();
        long failed = ((Number) counts.get("failed")).longValue();
        // Taux de traitement : commandes menées à une décision (acceptée ou refusée à raison) sans erreur technique.
        m.put("successRate", total == 0 ? null : Math.round(1000.0 * (total - failed) / total) / 10.0);

        // Latence réelle : réception par le bus → facture émise par l'ERP.
        m.put("avgLatencyMs", jdbc.queryForObject("""
                SELECT round(avg(extract(epoch FROM (i.created_at - r.created_at)) * 1000))
                FROM bus.integration_event r
                JOIN bus.integration_event i ON i.order_id = r.order_id AND i.status = 'INVOICED'
                WHERE r.status = 'RECEIVED'
                """, Long.class));
        m.put("events", jdbc.queryForObject("SELECT count(*) FROM bus.integration_event", Long.class));
        m.put("dlqPending", jdbc.queryForObject("SELECT count(*) FROM bus.dead_letter WHERE replayed_at IS NULL", Long.class));
        m.put("byStatus", jdbc.queryForList("SELECT status, count(*) AS n FROM bus.orders GROUP BY status ORDER BY status"));
        return m;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("erpAvailable", erp.isAvailable());
        h.put("database", safe(() -> jdbc.queryForObject("SELECT 1", Integer.class) == 1));

        Map<String, Object> queues = new LinkedHashMap<>();
        boolean rabbitUp = true;
        for (String q : List.of(ordersQueue, deliveryAssignedQueue, deliveryStatusQueue, deadLetterQueue)) {
            try {
                queues.put(q, rabbit.execute(channel -> channel.messageCount(q)));
            } catch (Exception e) {
                queues.put(q, null);
                rabbitUp = false;
            }
        }
        h.put("rabbitmq", rabbitUp);
        h.put("queues", queues);

        List<Map<String, Object>> routes = new ArrayList<>();
        for (Route r : camel.getRoutes()) {
            routes.add(Map.of("id", r.getRouteId(),
                    "from", r.getEndpoint().getEndpointBaseUri(),
                    "status", String.valueOf(camel.getRouteController().getRouteStatus(r.getRouteId()))));
        }
        routes.sort((a, b) -> ((String) a.get("id")).compareTo((String) b.get("id")));
        h.put("camelRoutes", routes);
        h.put("connectedScreens", live.connectedScreens());
        return h;
    }

    private static boolean safe(java.util.function.BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
