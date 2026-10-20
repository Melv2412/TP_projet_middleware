package com.sidam.connect.order;

import com.sidam.connect.model.CanonicalOrder;
import com.sidam.connect.model.OrderLine;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.model.StoreOrderMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** État des commandes vu par le bus (schéma bus). */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Génère une référence unique au format CMD-AAAA-NNNN. */
    public String nextOrderId() {
        Long n = jdbc.queryForObject("SELECT nextval('bus.order_number_seq')", Long.class);
        return String.format("CMD-%d-%04d", Year.now().getValue(), n);
    }

    @Transactional
    public void create(StoreOrderMessage order) {
        jdbc.update("""
                INSERT INTO bus.orders (order_id, correlation_id, customer_id, shipping_address, status)
                VALUES (?, ?::uuid, ?, ?, ?)
                """, order.getOrderId(), order.getCorrelationId(), order.getCustomerId(),
                order.getShippingAddress(), OrderStatus.RECEIVED.name());
        for (StoreOrderMessage.Item item : order.getItems()) {
            jdbc.update("INSERT INTO bus.order_item (order_id, sku, quantity) VALUES (?, ?, ?)",
                    order.getOrderId(), item.getSku(), item.getQuantity());
        }
    }

    public void updateStatus(String orderId, OrderStatus status, String reason) {
        jdbc.update("UPDATE bus.orders SET status = ?, status_reason = ?, updated_at = now() WHERE order_id = ?",
                status.name(), reason, orderId);
    }

    /** Avancement de la livraison (Process Manager). */
    public void updateDeliveryStatus(String orderId, String deliveryStatus, OrderStatus status) {
        jdbc.update("""
                UPDATE bus.orders SET delivery_status = ?, status = ?, status_reason = NULL, updated_at = now()
                WHERE order_id = ?
                """, deliveryStatus, status.name(), orderId);
    }

    /** Enregistre les prix et le total calculés par le Content Enricher. */
    @Transactional
    public void savePricing(CanonicalOrder order) {
        jdbc.update("UPDATE bus.orders SET total = ?, updated_at = now() WHERE order_id = ?",
                order.getTotal(), order.getOrderId());
        for (OrderLine line : order.getItems()) {
            jdbc.update("UPDATE bus.order_item SET unit_price = ? WHERE order_id = ? AND sku = ?",
                    line.getUnitPrice(), order.getOrderId(), line.getSku());
        }
    }

    public Optional<Map<String, Object>> find(String orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bus.orders WHERE order_id = ?", orderId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> order = rows.get(0);
        order.put("items", jdbc.queryForList(
                "SELECT sku, quantity, unit_price FROM bus.order_item WHERE order_id = ? ORDER BY id", orderId));
        return Optional.of(order);
    }

    /** Reconstitue le message Store d'origine (pour rejouer / dupliquer un message). */
    public Optional<StoreOrderMessage> findAsStoreMessage(String orderId) {
        List<StoreOrderMessage> rows = jdbc.query(
                "SELECT order_id, correlation_id, customer_id, shipping_address FROM bus.orders WHERE order_id = ?",
                (rs, i) -> {
                    StoreOrderMessage m = new StoreOrderMessage();
                    m.setOrderId(rs.getString("order_id"));
                    m.setCorrelationId(rs.getString("correlation_id"));
                    m.setCustomerId(rs.getString("customer_id"));
                    m.setShippingAddress(rs.getString("shipping_address"));
                    return m;
                }, orderId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoreOrderMessage msg = rows.get(0);
        msg.setItems(jdbc.query("SELECT sku, quantity FROM bus.order_item WHERE order_id = ? ORDER BY id",
                (rs, i) -> new StoreOrderMessage.Item(rs.getString("sku"), rs.getInt("quantity")), orderId));
        return Optional.of(msg);
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT * FROM bus.orders ORDER BY created_at DESC");
    }
}
