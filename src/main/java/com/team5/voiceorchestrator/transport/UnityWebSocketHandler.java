package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.config.AsyncConfig;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * WebSocket handler for Team 4's Unity client on {@code /ws/unity} (Contract 3).
 * <p>
 * Marked {@link Primary} so {@code config/WebSocketConfig} automatically selects this bean
 * even when additional {@code WebSocketHandler} beans (such as {@link TherapistWebSocketHandler})
 * exist in the application context.
 */
@Component
@Primary
public class UnityWebSocketHandler extends AbstractWebSocketHandler {

    public static final String ATTR_SESSION_ID = "voiceOrchestrator.sessionId";
    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String QUERY_PARAM_SESSION_ID = "sessionId";

    private static final Logger log = LoggerFactory.getLogger(UnityWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final ConversationOrchestrator conversationOrchestrator;
    private final ObjectMapper objectMapper;
    private final Executor orchestratorExecutor;

    public UnityWebSocketHandler(
            SessionRegistry sessionRegistry,
            ConversationOrchestrator conversationOrchestrator,
            ObjectMapper objectMapper,
            @Qualifier(AsyncConfig.ORCHESTRATOR_EXECUTOR) Executor orchestratorExecutor) {
        this.sessionRegistry = sessionRegistry;
        this.conversationOrchestrator = conversationOrchestrator;
        this.objectMapper = objectMapper;
        this.orchestratorExecutor = orchestratorExecutor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = resolveSessionId(session);
        if (sessionId == null) {
            log.warn("Rejecting Unity WebSocket '{}' due to missing or blank sessionId.", session.getId());
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Missing or blank sessionId"));
            return;
        }
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);
        sessionRegistry.registerUnitySession(sessionId, session);
        log.info("Unity WebSocket '{}' connected for session '{}'.", session.getId(), sessionId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = getOrResolveSessionId(session);
        if (sessionId == null) {
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Missing or blank sessionId"));
            return;
        }

        ByteBuffer buffer = message.getPayload();
        int length = buffer.remaining();
        if (length == 0) {
            return;
        }

        if (length % 2 != 0) {
            log.warn("Received odd-length PCM frame ({} bytes) for session '{}'.", length, sessionId);
            sendControlMessage(sessionId, new UnityControlMessage(
                    UnityControlMessage.TYPE_ERROR,
                    sessionId,
                    System.currentTimeMillis(),
                    "Audio frame must have even byte length (16-bit PCM_S16LE)"
            ));
            return;
        }

        byte[] payload = new byte[length];
        buffer.get(payload);

        sessionRegistry.getSession(sessionId).ifPresent(activeSession -> {
            if (activeSession.enqueueAudioChunk(payload)) {
                activeSession.drainAudioAsync(orchestratorExecutor, chunk -> forwardAudioChunk(sessionId, chunk));
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = getOrResolveSessionId(session);
        if (sessionId == null) {
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Missing or blank sessionId"));
            return;
        }

        UnityControlMessage control;
        try {
            control = objectMapper.readValue(message.getPayload(), UnityControlMessage.class);
        } catch (JsonProcessingException ex) {
            log.warn("Invalid Unity control JSON for session '{}': {}", sessionId, ex.getMessage());
            sendControlMessage(sessionId, new UnityControlMessage(
                    UnityControlMessage.TYPE_ERROR,
                    sessionId,
                    System.currentTimeMillis(),
                    "Malformed control JSON"
            ));
            return;
        }

        if (control == null || control.type() == null || control.type().isBlank()) {
            sendControlMessage(sessionId, new UnityControlMessage(
                    UnityControlMessage.TYPE_ERROR,
                    sessionId,
                    System.currentTimeMillis(),
                    "Control message type must not be blank"
            ));
            return;
        }

        sessionRegistry.getSession(sessionId).ifPresent(ActiveSession::touch);

        switch (control.type()) {
            case UnityControlMessage.TYPE_SESSION_START ->
                    log.info("Received session_start from Unity for session '{}'.", sessionId);
            case UnityControlMessage.TYPE_SESSION_END -> {
                log.info("Received session_end from Unity for session '{}'; closing socket.", sessionId);
                ActiveSession.closeQuietly(session, CloseStatus.NORMAL);
            }
            case UnityControlMessage.TYPE_PING -> sendControlMessage(
                    sessionId,
                    UnityControlMessage.of(UnityControlMessage.TYPE_PONG, sessionId, System.currentTimeMillis())
            );
            case UnityControlMessage.TYPE_PONG ->
                    log.debug("Received heartbeat pong from Unity for session '{}'.", sessionId);
            case UnityControlMessage.TYPE_BARGE_IN -> {
                sessionRegistry.getSession(sessionId).ifPresent(ActiveSession::clearAudioBuffer);
                log.info("Processed barge_in for session '{}'.", sessionId);
            }
            default -> {
                log.warn("Unsupported Unity control message type '{}' for session '{}'.", control.type(), sessionId);
                sendControlMessage(sessionId, new UnityControlMessage(
                        UnityControlMessage.TYPE_ERROR,
                        sessionId,
                        System.currentTimeMillis(),
                        "Unsupported control message type: " + control.type()
                ));
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = getOrResolveSessionId(session);
        log.error("Unity WebSocket transport error for session '{}' (socket '{}'): {}",
                sessionId, session.getId(), exception.getMessage(), exception);
        ActiveSession.closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = getOrResolveSessionId(session);
        if (sessionId == null) {
            return;
        }
        boolean wasActive = sessionRegistry.unregisterUnitySession(sessionId, session);
        if (wasActive) {
            conversationOrchestrator.onSessionClosed(sessionId);
            log.info("Unity WebSocket '{}' closed for session '{}' (status: {}).",
                    session.getId(), sessionId, status);
        }
    }

    /**
     * Asynchronously serializes and sends a {@link UnityResponsePacket} to the active Unity socket.
     */
    public CompletableFuture<Boolean> sendResponsePacket(String sessionId, UnityResponsePacket packet) {
        if (sessionId == null || sessionId.isBlank() || packet == null) {
            return CompletableFuture.completedFuture(false);
        }
        return sendJsonAsync(sessionId, packet);
    }

    /**
     * Asynchronously serializes and sends a {@link UnityControlMessage} to the active Unity socket.
     */
    public CompletableFuture<Boolean> sendControlMessage(String sessionId, UnityControlMessage controlMessage) {
        if (sessionId == null || sessionId.isBlank() || controlMessage == null) {
            return CompletableFuture.completedFuture(false);
        }
        return sendJsonAsync(sessionId, controlMessage);
    }

    private CompletableFuture<Boolean> sendJsonAsync(String sessionId, Object payload) {
        return CompletableFuture.supplyAsync(() -> {
            ActiveSession activeSession = sessionRegistry.getSession(sessionId).orElse(null);
            if (activeSession == null) {
                return false;
            }
            try {
                String json = objectMapper.writeValueAsString(payload);
                return activeSession.sendUnityText(json);
            } catch (JsonProcessingException ex) {
                log.error("Failed serializing outbound Unity payload for session '{}': {}",
                        sessionId, ex.getMessage(), ex);
                return false;
            }
        }, orchestratorExecutor);
    }

    private void forwardAudioChunk(String sessionId, byte[] chunk) {
        try {
            conversationOrchestrator.handleAudioChunk(sessionId, chunk)
                    .thenAccept(optionalPacket -> optionalPacket.ifPresent(packet -> sendResponsePacket(sessionId, packet)))
                    .exceptionally(ex -> {
                        log.error("Error processing audio chunk for session '{}': {}",
                                sessionId, ex.getMessage(), ex);
                        return null;
                    });
        } catch (RuntimeException ex) {
            log.error("Failed dispatching audio chunk for session '{}': {}", sessionId, ex.getMessage(), ex);
        }
    }

    private String getOrResolveSessionId(WebSocketSession session) {
        Object attr = session.getAttributes().get(ATTR_SESSION_ID);
        if (attr instanceof String existing && !existing.isBlank()) {
            return existing;
        }
        String resolved = resolveSessionId(session);
        if (resolved != null) {
            session.getAttributes().put(ATTR_SESSION_ID, resolved);
        }
        return resolved;
    }

    static String resolveSessionId(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        URI uri = session.getUri();
        if (uri != null) {
            String fromQuery = UriComponentsBuilder.fromUri(uri)
                    .build()
                    .getQueryParams()
                    .getFirst(QUERY_PARAM_SESSION_ID);
            if (fromQuery != null && !fromQuery.isBlank()) {
                return fromQuery.trim();
            }
        }
        if (session.getHandshakeHeaders() != null) {
            String fromHeader = session.getHandshakeHeaders().getFirst(HEADER_SESSION_ID);
            if (fromHeader != null && !fromHeader.isBlank()) {
                return fromHeader.trim();
            }
        }
        return null;
    }
}
