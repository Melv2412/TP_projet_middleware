package com.sidam.connect.model;

import java.util.List;

/**
 * Commande au format du site e-commerce (JSON), telle que publiée dans RabbitMQ.
 * Le Store ne connaît pas les prix ERP : ils seront ajoutés par le bus (Content Enricher).
 */
public class StoreOrderMessage {

    private String orderId;
    private String correlationId;
    private String customerId;
    private String shippingAddress;
    private List<Item> items;

    public static class Item {
        private String sku;
        private int quantity;

        public Item() {
        }

        public Item(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
}
