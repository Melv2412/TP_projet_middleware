package com.sidam.connect.api;

import com.sidam.connect.audit.EventLog;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.model.StoreOrderMessage;
import com.sidam.connect.order.OrderIntakeService;
import com.sidam.connect.order.OrderRepository;
import com.sidam.connect.translate.OrderTranslator;
import org.apache.camel.ProducerTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Point d'entrée du Store : réception des commandes JSON et consultation de leur état. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orders;
    private final OrderIntakeService intake;
    private final EventLog events;
    private final ProducerTemplate camel;
    private final OrderTranslator translator;

    public OrderController(OrderRepository orders, OrderIntakeService intake, EventLog events,
                           ProducerTemplate camel, OrderTranslator translator) {
        this.orders = orders;
        this.intake = intake;
        this.events = events;
        this.camel = camel;
        this.translator = translator;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody StoreOrderMessage request) {
        intake.register(request, "STORE",
                "Commande reçue du Store (" + (request.getItems() == null ? 0 : request.getItems().size()) + " article(s))",
                translator.toJson(request), "JSON");
        try {
            camel.sendBody("direct:submitOrder", request);
        } catch (Exception ex) {
            orders.updateStatus(request.getOrderId(), OrderStatus.FAILED, "Publication RabbitMQ impossible");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("orderId", request.getOrderId(), "error", "Bus de messages indisponible"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "orderId", request.getOrderId(),
                "correlationId", request.getCorrelationId(),
                "status", OrderStatus.RECEIVED.name()));
    }

    /**
     * Import d'un fichier CSV au format historique de l'ERP :
     * <pre>order_id,sku,quantity,unit_price</pre>
     * Chaque order_id du fichier devient une commande du bus.
     */
    @PostMapping(value = "/import-csv", consumes = {"text/csv", "text/plain"})
    public ResponseEntity<Map<String, Object>> importCsv(@RequestBody String csv) {
        List<?> imported = camel.requestBody("direct:importCsv", csv, List.class);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("imported", imported));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return orders.findAll();
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String orderId) {
        return ResponseEntity.of(orders.find(orderId));
    }

    @GetMapping("/{orderId}/events")
    public List<Map<String, Object>> events(@PathVariable String orderId) {
        return events.forOrder(orderId);
    }
}
