package com.sidam.connect.audit;

/**
 * Une étape traversée par un message dans le bus.
 * Construit avec {@link #of(String, String, String)} puis complété au besoin.
 */
public class IntegrationEvent {

    String orderId;
    String correlationId;
    String source;
    String destination;
    String routeId;
    String pattern;
    String status;
    String message;
    String payload;
    String payloadFormat;
    int attempt = 1;
    Long durationMs;
    String errorCode;

    public static IntegrationEvent of(String orderId, String correlationId, String status) {
        IntegrationEvent e = new IntegrationEvent();
        e.orderId = orderId;
        e.correlationId = correlationId;
        e.status = status;
        return e;
    }

    public IntegrationEvent route(String routeId, String pattern) {
        this.routeId = routeId;
        this.pattern = pattern;
        return this;
    }

    public IntegrationEvent flow(String source, String destination) {
        this.source = source;
        this.destination = destination;
        return this;
    }

    public IntegrationEvent message(String message) {
        this.message = message;
        return this;
    }

    public IntegrationEvent payload(String payload, String format) {
        this.payload = payload;
        this.payloadFormat = format;
        return this;
    }

    public IntegrationEvent duration(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public IntegrationEvent attempt(int attempt) {
        this.attempt = attempt;
        return this;
    }

    public IntegrationEvent error(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }
}
