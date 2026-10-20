package com.sidam.connect.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidam.connect.live.LiveEventBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Le journal d'audit écrit réellement dans PostgreSQL et diffuse l'événement enregistré.
 * Nécessite la base de docker-compose (localhost:5432) ; ignoré si elle est absente.
 */
class EventLogTest {

    private static final String ORDER = "TEST-EVENTLOG";

    private JdbcTemplate jdbc;
    private EventLog log;
    private final List<Map<String, Object>> broadcasted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/sidam_db", "admin", "password");
        assumeTrue(isReachable(ds), "PostgreSQL non démarré : lancer docker compose up");
        jdbc = new JdbcTemplate(ds);
        LiveEventBroadcaster spy = new LiveEventBroadcaster(new ObjectMapper()) {
            @Override
            public void broadcast(Map<String, Object> event) {
                broadcasted.add(event);
            }
        };
        log = new EventLog(jdbc, spy);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM bus.integration_event WHERE order_id = ?", ORDER);
        }
    }

    @Test
    void enregistreEtDiffuseLEvenement() {
        log.record(IntegrationEvent.of(ORDER, "6f1c2a43-8b0e-4a7e-9d38-2a1f0c7b9e11", "TRANSFORMED")
                .route("test", "Message Translator")
                .flow("BUS", "ERP")
                .message("test")
                .payload("<commande/>", "XML"));

        List<Map<String, Object>> stored = log.forOrder(ORDER);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).containsEntry("pattern", "Message Translator").containsEntry("payload_format", "XML");

        assertThat(broadcasted).hasSize(1);
        assertThat(broadcasted.get(0)).containsEntry("status", "TRANSFORMED").containsKey("created_at").containsKey("event_id");
    }

    private static boolean isReachable(DriverManagerDataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
