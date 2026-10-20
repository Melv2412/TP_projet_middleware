package com.sidam.connect.erp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sidam.connect.model.ErpAck;
import com.sidam.connect.model.ErpCommandeXml;
import com.sidam.connect.model.ErpLivraisonXml;
import com.sidam.connect.model.StockCheckResult;
import com.sidam.connect.translate.OrderTranslator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adaptateur vers l'ERP legacy (simulé dans le schéma PostgreSQL "erp").
 * L'ERP n'est pas remplacé : le bus lui parle uniquement par ce point d'entrée, en XML.
 */
@Component
public class LegacyErpAdapter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderTranslator translator;

    /** Interrupteur de démo : false = l'ERP ne répond plus (panne simulée). */
    private final AtomicBoolean available = new AtomicBoolean(true);

    public LegacyErpAdapter(JdbcTemplate jdbc, TransactionTemplate tx, OrderTranslator translator) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.translator = translator;
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    /** Chaque appel à l'ERP commence par là : simule une connexion refusée en cas de panne. */
    private void connect() {
        if (!available.get()) {
            throw new ErpUnavailableException("ERP legacy injoignable (panne simulée)");
        }
    }

    /** Catalogue des prix (utilisé par le Content Enricher). */
    public Map<String, BigDecimal> priceCatalog() {
        connect();
        Map<String, BigDecimal> prices = new HashMap<>();
        jdbc.query("SELECT sku, unit_price FROM erp.product",
                rs -> { prices.put(rs.getString("sku"), rs.getBigDecimal("unit_price")); });
        return prices;
    }

    /** Désignations des produits (pour enrichir la mission du livreur). */
    public Map<String, String> productNames() {
        connect();
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT sku, name FROM erp.product",
                rs -> { names.put(rs.getString("sku"), rs.getString("name")); });
        return names;
    }

    /**
     * Réception d'un avis de livraison XML : la commande passe à DELIVERED dans l'ERP
     * et la marchandise réservée sort du stock. Sans effet si l'avis a déjà été reçu.
     *
     * @return true si l'avis a été appliqué, false s'il était déjà connu
     */
    public boolean receiveDeliveryXml(String xmlPayload) throws JsonProcessingException {
        connect();
        ErpLivraisonXml livraison = translator.parseErpDeliveryXml(xmlPayload);
        return Boolean.TRUE.equals(tx.execute(status -> {
            int updated = jdbc.update("UPDATE erp.erp_order SET status = 'DELIVERED' WHERE ref = ? AND status <> 'DELIVERED'",
                    livraison.getRef());
            if (updated == 0) {
                return false;
            }
            jdbc.update("""
                    UPDATE erp.inventory i
                       SET reserved = i.reserved - l.quantity, updated_at = now()
                      FROM erp.erp_order_line l
                     WHERE l.order_ref = ? AND l.sku = i.sku
                    """, livraison.getRef());
            return true;
        }));
    }

    /** Pré-vérification d'une ligne (appelée par le Splitter). Ne modifie rien. */
    public StockCheckResult checkLine(String sku, int requested) {
        List<Integer> rows = jdbc.queryForList("SELECT available FROM erp.inventory WHERE sku = ?", Integer.class, sku);
        if (rows.isEmpty()) {
            return new StockCheckResult(sku, requested, 0, false, sku + " : article inconnu");
        }
        int available = rows.get(0);
        boolean ok = available >= requested;
        String reason = ok ? null : sku + " : demandé " + requested + ", disponible " + available;
        return new StockCheckResult(sku, requested, available, ok, reason);
    }

    /**
     * Réception d'une commande XML par l'ERP.
     * Réservation atomique : toutes les lignes sont réservées dans une seule transaction,
     * avec "UPDATE ... WHERE available >= qty" pour qu'aucune vente concurrente ne passe en négatif.
     * Si une ligne échoue, la transaction est annulée et rien n'est débité.
     */
    public ErpAck receiveOrderXml(String xmlPayload) throws JsonProcessingException {
        connect();
        ErpCommandeXml cmd = translator.parseErpXml(xmlPayload);

        return tx.execute(status -> {
            // Idempotence côté ERP : une référence déjà connue ne produit aucun nouvel effet.
            Optional<ErpAck> existing = existingAck(cmd.getRef());
            if (existing.isPresent()) {
                return existing.get();
            }

            BigDecimal total = BigDecimal.ZERO;
            for (ErpCommandeXml.Ligne ligne : cmd.getLignes()) {
                int updated = jdbc.update("""
                        UPDATE erp.inventory
                           SET available = available - ?, reserved = reserved + ?, updated_at = now()
                         WHERE sku = ? AND available >= ?
                        """, ligne.getQte(), ligne.getQte(), ligne.getArticle(), ligne.getQte());
                if (updated == 0) {
                    status.setRollbackOnly();
                    return ErpAck.rejected(cmd.getRef(), ligne.getArticle() + " : stock insuffisant au moment de la réservation");
                }
                BigDecimal price = jdbc.queryForObject("SELECT unit_price FROM erp.product WHERE sku = ?",
                        BigDecimal.class, ligne.getArticle());
                total = total.add(price.multiply(BigDecimal.valueOf(ligne.getQte())));
            }

            jdbc.update("INSERT INTO erp.erp_order (ref, customer_id, xml_payload, status) VALUES (?, ?, ?, 'RESERVED')",
                    cmd.getRef(), cmd.getClient(), xmlPayload);
            for (ErpCommandeXml.Ligne ligne : cmd.getLignes()) {
                jdbc.update("""
                        INSERT INTO erp.erp_order_line (order_ref, sku, quantity, unit_price)
                        SELECT ?, sku, ?, unit_price FROM erp.product WHERE sku = ?
                        """, cmd.getRef(), ligne.getQte(), ligne.getArticle());
            }

            String invoiceNo = cmd.getRef().replace("CMD-", "FAC-");
            jdbc.update("INSERT INTO erp.invoice (invoice_no, order_ref, total) VALUES (?, ?, ?)",
                    invoiceNo, cmd.getRef(), total);
            jdbc.update("UPDATE erp.erp_order SET status = 'INVOICED' WHERE ref = ?", cmd.getRef());

            return new ErpAck(cmd.getRef(), true, false, invoiceNo, total, null);
        });
    }

    private Optional<ErpAck> existingAck(String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT invoice_no, total FROM erp.invoice WHERE order_ref = ?", ref);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new ErpAck(ref, true, true, (String) row.get("invoice_no"),
                (BigDecimal) row.get("total"), "commande déjà connue de l'ERP"));
    }

    // --- Lecture pour l'écran ERP ---

    public List<Map<String, Object>> inventory() {
        return jdbc.queryForList("""
                SELECT p.sku, p.name, p.unit_price, i.available, i.reserved, i.updated_at
                FROM erp.product p JOIN erp.inventory i ON i.sku = p.sku
                ORDER BY p.sku
                """);
    }

    /** Commandes reçues par l'ERP (plus récentes d'abord), avec leur facture. */
    public List<Map<String, Object>> orders() {
        return jdbc.queryForList("""
                SELECT o.ref, o.customer_id, o.status, o.received_at, o.xml_payload,
                       i.invoice_no, i.total, i.issued_at
                FROM erp.erp_order o LEFT JOIN erp.invoice i ON i.order_ref = o.ref
                ORDER BY o.received_at DESC
                """);
    }

    public Optional<Map<String, Object>> findOrder(String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM erp.erp_order WHERE ref = ?", ref);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> order = rows.get(0);
        order.put("lines", jdbc.queryForList(
                "SELECT sku, quantity, unit_price FROM erp.erp_order_line WHERE order_ref = ? ORDER BY id", ref));
        order.put("invoice", jdbc.queryForList("SELECT * FROM erp.invoice WHERE order_ref = ?", ref)
                .stream().findFirst().orElse(null));
        return Optional.of(order);
    }
}
