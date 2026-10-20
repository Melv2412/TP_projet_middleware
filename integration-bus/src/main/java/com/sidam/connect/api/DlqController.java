package com.sidam.connect.api;

import com.sidam.connect.dlq.DeadLetterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Console de la Dead Letter Queue : liste des messages en échec et rejeu manuel. */
@RestController
@RequestMapping("/api/dlq")
public class DlqController {

    private final DeadLetterService deadLetters;

    public DlqController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("queueDepth", deadLetters.queueDepth(), "messages", deadLetters.list());
    }

    @PostMapping("/{dlqId}/replay")
    public ResponseEntity<Map<String, String>> replay(@PathVariable String dlqId) {
        return switch (deadLetters.replay(dlqId)) {
            case REPLAYED -> ResponseEntity.accepted().body(Map.of("status", "Message rejoué"));
            case ALREADY_REPLAYED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Ce message a déjà été rejoué"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Message introuvable dans la DLQ"));
        };
    }
}
