package com.sidam.connect.audit;

import com.sidam.connect.live.LiveEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit du bus : chaque étape est enregistrée dans bus.integration_event,
 * puis diffusée en temps réel aux écrans.
 */
@Service
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);

    private static final String COLUMNS = """
            id, event_id, order_id, correlation_id, source, destination, route_id, pattern,
            status, message, payload, payload_format, attempt, duration_ms, error_code, created_at
            """;

    private final JdbcTemplate jdbc;
    private final LiveEventBroadcaster live;

    public EventLog(JdbcTemplate jdbc, LiveEventBroadcaster live) {
        this.jdbc = jdbc;
        this.live = live;
    }

    public void record(IntegrationEvent e) {
        Map<String, Object> saved = jdbc.queryForMap("""
                INSERT INTO bus.integration_event
                  (event_id, order_id, correlation_id, source, destination, route_id, pattern,
                   status, message, payload, payload_format, attempt, duration_ms, error_code)
                VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + " RETURNING " + COLUMNS,   // hors du bloc de texte : Java y supprime les espaces de fin de ligne
                UUID.randomUUID(), e.orderId, e.correlationId, e.source, e.destination, e.routeId,
                e.pattern, e.status, e.message, e.payload, e.payloadFormat, e.attempt, e.durationMs,
                e.errorCode);
        log.info("[{}] {} | {} | {}", e.orderId, e.status, e.pattern, e.message);
        live.broadcast(saved);
    }

    public List<Map<String, Object>> forOrder(String orderId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM bus.integration_event WHERE order_id = ? ORDER BY id", orderId);
    }

    /** Derniers événements, du plus ancien au plus récent (chargement initial des écrans). */
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT * FROM (SELECT " + COLUMNS
                + " FROM bus.integration_event ORDER BY id DESC LIMIT ?) e ORDER BY id", limit);
    }
}
