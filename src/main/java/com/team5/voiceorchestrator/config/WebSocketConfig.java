package com.team5.voiceorchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Configures the WebSocket endpoint used by Team 4's Unity client.
 * <p>
 * The concrete {@link WebSocketHandler} bean (e.g., {@code UnityWebSocketHandler} in
 * {@code transport/}) is discovered automatically when present.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final OrchestratorProperties properties;
    private final ObjectProvider<WebSocketHandler> webSocketHandlerProvider;

    public WebSocketConfig(
            OrchestratorProperties properties,
            ObjectProvider<WebSocketHandler> webSocketHandlerProvider) {
        this.properties = properties;
        this.webSocketHandlerProvider = webSocketHandlerProvider;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandler handler = webSocketHandlerProvider.getIfAvailable();
        if (handler == null) {
            log.warn("No WebSocketHandler bean found; skipping registration for endpoint '{}'.",
                    properties.getWebsocket().getEndpoint());
            return;
        }

        String endpoint = properties.getWebsocket().getEndpoint();
        String allowedOrigins = properties.getWebsocket().getAllowedOrigins();

        registry.addHandler(handler, endpoint)
                .setAllowedOrigins(allowedOrigins.split(","));
        log.info("Registered WebSocket handler '{}' at endpoint '{}'.",
                handler.getClass().getSimpleName(), endpoint);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(properties.getWebsocket().getMaxBinaryMessageSizeBytes());
        container.setMaxTextMessageBufferSize(properties.getWebsocket().getMaxTextMessageSizeBytes());
        return container;
    }
}
