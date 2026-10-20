package com.sidam.connect.route;

import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.service.ServiceSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Mémoire de l'Idempotent Consumer, stockée dans PostgreSQL (bus.processed_message).
 * Elle survit aux redémarrages du bus et est partagée par tous les consommateurs.
 */
@Component
public class JdbcIdempotentRepository extends ServiceSupport implements IdempotentRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true si la clé est nouvelle (message à traiter), false si déjà vue. */
    @Override
    public boolean add(String key) {
        return jdbc.update("INSERT INTO bus.processed_message (message_key) VALUES (?) ON CONFLICT DO NOTHING", key) == 1;
    }

    @Override
    public boolean contains(String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM bus.processed_message WHERE message_key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    /** Appelé par Camel si le traitement échoue : le message pourra être retraité. */
    @Override
    public boolean remove(String key) {
        return jdbc.update("DELETE FROM bus.processed_message WHERE message_key = ?", key) == 1;
    }

    @Override
    public boolean confirm(String key) {
        return true;
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM bus.processed_message");
    }
}
