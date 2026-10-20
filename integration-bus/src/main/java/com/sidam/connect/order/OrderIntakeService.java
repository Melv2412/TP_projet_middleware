package com.sidam.connect.order;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.model.StoreOrderMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Enregistre une commande entrante (Store JSON ou import CSV) avant sa publication dans RabbitMQ :
 * attribue orderId + correlationId (EIP Correlation Identifier) et trace l'événement RECEIVED.
 */
@Service
public class OrderIntakeService {

    private final OrderRepository orders;
    private final EventLog events;

    public OrderIntakeService(OrderRepository orders, EventLog events) {
        this.orders = orders;
        this.events = events;
    }

    public static void validate(StoreOrderMessage request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("La commande doit contenir au moins un article");
        }
        for (StoreOrderMessage.Item item : request.getItems()) {
            if (item.getSku() == null || item.getSku().isBlank()) {
                throw new IllegalArgumentException("Chaque article doit avoir un SKU");
            }
            if (item.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantité invalide pour " + item.getSku() + " : " + item.getQuantity());
            }
        }
    }

    /**
     * @param source        système d'origine (STORE, ERP_CSV…)
     * @param rawPayload    message tel que reçu, conservé pour l'inspecteur
     * @param payloadFormat JSON, CSV…
     */
    public StoreOrderMessage register(StoreOrderMessage request, String source, String description,
                                      String rawPayload, String payloadFormat) {
        validate(request);
        request.setOrderId(orders.nextOrderId());
        request.setCorrelationId(UUID.randomUUID().toString());
        orders.create(request);
        events.record(IntegrationEvent.of(request.getOrderId(), request.getCorrelationId(), OrderStatus.RECEIVED.name())
                .route("api", "Correlation Identifier")
                .flow(source, "BUS")
                .message(description)
                .payload(rawPayload, payloadFormat));
        return request;
    }
}
