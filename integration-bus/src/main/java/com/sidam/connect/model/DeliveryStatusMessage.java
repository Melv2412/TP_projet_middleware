package com.sidam.connect.model;

import java.time.Instant;

/** Changement de statut envoyé par l'app livreur au bus (app livreur → bus). */
public class DeliveryStatusMessage {

    /** Statuts d'une mission, dans l'ordre où ils doivent arriver. */
    public enum Status {
        ASSIGNED(1, OrderStatus.DELIVERY_ASSIGNED),
        IN_DELIVERY(2, OrderStatus.IN_DELIVERY),
        DELIVERED(3, OrderStatus.DELIVERED);

        public final int rank;
        public final OrderStatus orderStatus;

        Status(int rank, OrderStatus orderStatus) {
            this.rank = rank;
            this.orderStatus = orderStatus;
        }
    }

    private String missionId;
    private String orderId;
    private String correlationId;
    private Status status;
    private String driver;
    private Instant at;

    public DeliveryStatusMessage() {
    }

    public DeliveryStatusMessage(String missionId, String orderId, String correlationId, Status status,
                                 String driver, Instant at) {
        this.missionId = missionId;
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.status = status;
        this.driver = driver;
        this.at = at;
    }

    public String getMissionId() { return missionId; }
    public void setMissionId(String missionId) { this.missionId = missionId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
