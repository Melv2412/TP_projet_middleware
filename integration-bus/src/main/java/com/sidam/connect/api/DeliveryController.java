package com.sidam.connect.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidam.connect.delivery.DeliveryApp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** API de l'application SIDAM Delivery (utilisée par l'écran mobile du livreur). */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeliveryApp app;

    public DeliveryController(DeliveryApp app) {
        this.app = app;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return app.list(status);
    }

    @GetMapping("/{missionId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String missionId) {
        return ResponseEntity.of(app.find(missionId));
    }

    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<Map<String, Object>> byOrder(@PathVariable String orderId) {
        return ResponseEntity.of(app.findByOrder(orderId));
    }

    /**
     * Prise en charge. Corps JSON optionnel : {"driver": "Koffi"}.
     * Le corps est lu comme texte pour accepter aussi un appel sans corps ni Content-Type JSON.
     */
    @PostMapping("/{missionId}/pickup")
    public ResponseEntity<Map<String, Object>> pickUp(@PathVariable String missionId,
                                                      @RequestBody(required = false) String body) {
        String driver = "Livreur SIDAM";
        if (body != null && !body.isBlank()) {
            try {
                JsonNode json = JSON.readTree(body);
                if (json.hasNonNull("driver") && !json.get("driver").asText().isBlank()) {
                    driver = json.get("driver").asText();
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Corps JSON invalide (attendu : {\"driver\": \"…\"})");
            }
        }
        return answer(app.pickUp(missionId, driver), missionId, "La mission doit être au statut ASSIGNED");
    }

    @PostMapping("/{missionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String missionId) {
        return answer(app.confirm(missionId), missionId, "La mission doit d'abord être prise en charge (IN_DELIVERY)");
    }

    private ResponseEntity<Map<String, Object>> answer(DeliveryApp.ActionResult result, String missionId, String rule) {
        return switch (result) {
            case DONE -> ResponseEntity.ok(app.find(missionId).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Mission inconnue"));
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", rule));
        };
    }
}
