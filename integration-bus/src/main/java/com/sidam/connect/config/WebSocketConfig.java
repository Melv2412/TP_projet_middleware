package com.sidam.connect.config;

import com.sidam.connect.live.LiveEventBroadcaster;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Point de connexion temps réel des écrans : ws://localhost:8080/ws/events */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveEventBroadcaster broadcaster;

    public WebSocketConfig(LiveEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(broadcaster, "/ws/events").setAllowedOriginPatterns("*");
    }
}
