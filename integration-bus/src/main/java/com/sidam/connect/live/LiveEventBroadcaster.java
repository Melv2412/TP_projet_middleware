package com.sidam.connect.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diffuse en temps réel chaque événement d'intégration à tous les écrans connectés
 * (WebSocket /ws/events). Les écrans n'affichent donc que ce que le bus a réellement traité.
 */
@Component
public class LiveEventBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveEventBroadcaster.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public LiveEventBroadcaster(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Le décorateur rend l'envoi sûr depuis plusieurs threads (consommateurs Camel en parallèle).
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    public int connectedScreens() {
        return sessions.size();
    }

    /** Envoie un événement à tous les écrans. Ne lève jamais d'exception : le flux métier passe avant. */
    public void broadcast(Map<String, Object> event) {
        if (sessions.isEmpty()) {
            return;
        }
        String text;
        try {
            text = json.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("Événement non diffusable : {}", e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(text);
        sessions.values().forEach(s -> {
            try {
                if (s.isOpen()) {
                    s.sendMessage(message);
                }
            } catch (Exception e) {
                sessions.remove(s.getId());
            }
        });
    }
}
