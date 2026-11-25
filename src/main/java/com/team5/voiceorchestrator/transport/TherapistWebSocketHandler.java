package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.config.AsyncConfig;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * WebSocket handler for Team 1's Therapist React UI on {@code /ws/therapist} (Contract 2).
 * <p>
 * Self-registers its endpoint via {@link WebSocketConfigurer} so {@code config/WebSocketConfig}
 * remains untouched, and validates initial {@code auth} JWT frames before accepting live steering
 * or pushing {@link TherapistTranscriptEvent} updates.
 */
@Component
public class TherapistWebSocketHandler extends TextWebSocketHandler implements WebSocketConfigurer {

    public static final String THERAPIST_ENDPOINT = "/ws/therapist";
    public static final String ATTR_SESSION_ID = "voiceOrchestrator.therapistSessionId";

    private static final Pattern JWT_FORMAT_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static final Logger log = LoggerFactory.getLogger(TherapistWebSocketHandler.class);

    /**
     * Callback hook allowing downstream orchestration components to receive covert
     * {@code live_steering} instructions without coupling transport to orchestration internals.
     */
    @FunctionalInterface
    public interface LiveSteeringListener {
        void onLiveSteering(String sessionId, String instruction);
    }

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final OrchestratorProperties properties;
    private final Executor orchestratorExecutor;
    private final ObjectProvider<LiveSteeringListener> liveSteeringListenerProvider;

    public TherapistWebSocketHandler(
            SessionRegistry sessionRegistry,
            ObjectMapper objectMapper,
            OrchestratorProperties properties,
            @Qualifier(AsyncConfig.ORCHESTRATOR_EXECUTOR) Executor orchestratorExecutor,
            ObjectProvider<LiveSteeringListener> liveSteeringListenerProvider) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orchestratorExecutor = orchestratorExecutor;
        this.liveSteeringListenerProvider = liveSteeringListenerProvider;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String allowedOrigins = properties.getWebsocket().getAllowedOrigins();
        registry.addHandler(this, THERAPIST_ENDPOINT)
                .setAllowedOrigins(allowedOrigins.split(","));
        log.info("Registered TherapistWebSocketHandler at endpoint '{}'.", THERAPIST_ENDPOINT);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = UnityWebSocketHandler.resolveSessionId(session);
        if (sessionId != null) {
            session.getAttributes().put(ATTR_SESSION_ID, sessionId);
            sessionRegistry.registerTherapistSession(sessionId, session);
            log.info("Therapist WebSocket '{}' connected for session '{}' (awaiting auth).",
                    session.getId(), sessionId);
        } else {
            log.debug("Therapist WebSocket '{}' connected without query sessionId; awaiting auth frame.",
                    session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        TherapistControlMessage control;
        try {
            control = objectMapper.readValue(message.getPayload(), TherapistControlMessage.class);
        } catch (JsonProcessingException ex) {
            log.warn("Invalid Therapist control JSON on socket '{}': {}", session.getId(), ex.getMessage());
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Malformed control JSON"));
            return;
        }

        if (control == null || control.type() == null || control.type().isBlank()) {
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Missing control message type"));
            return;
        }

        String sessionId = resolveMessageSessionId(session, control);
        if (sessionId == null) {
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Missing or blank sessionId"));
            return;
        }

        switch (control.type()) {
            case TherapistControlMessage.TYPE_AUTH -> handleAuth(session, sessionId, control.token());
            case TherapistControlMessage.TYPE_PING -> handlePing(session, sessionId);
            case TherapistControlMessage.TYPE_PONG ->
                    sessionRegistry.getSession(sessionId).ifPresent(ActiveSession::touch);
            case TherapistControlMessage.TYPE_LIVE_STEERING -> handleLiveSteering(session, sessionId, control);
            case TherapistControlMessage.TYPE_SESSION_START -> {
                ActiveSession active = requireAuthenticatedSession(session, sessionId);
                if (active != null) {
                    active.touch();
                    log.info("Therapist sent session_start for session '{}'.", sessionId);
                }
            }
            case TherapistControlMessage.TYPE_SESSION_END -> {
                ActiveSession active = requireAuthenticatedSession(session, sessionId);
                if (active != null) {
                    log.info("Therapist sent session_end for session '{}'; closing therapist socket.", sessionId);
                    ActiveSession.closeQuietly(session, CloseStatus.NORMAL);
                }
            }
            default -> log.warn("Unsupported Therapist control message type '{}' for session '{}'.",
                    control.type(), sessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = getAttributeSessionId(session);
        log.error("Therapist WebSocket transport error for session '{}' (socket '{}'): {}",
                sessionId, session.getId(), exception.getMessage(), exception);
        ActiveSession.closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = getAttributeSessionId(session);
        if (sessionId == null) {
            return;
        }
        boolean wasActive = sessionRegistry.unregisterTherapistSession(sessionId, session);
        if (wasActive) {
            log.info("Therapist WebSocket '{}' closed for session '{}' (status: {}).",
                    session.getId(), sessionId, status);
        }
    }

    /**
     * Asynchronously pushes a {@link TherapistTranscriptEvent} to the authenticated therapist
     * socket bound to {@code sessionId}.
     */
    public CompletableFuture<Boolean> sendTranscriptEvent(String sessionId, TherapistTranscriptEvent event) {
        if (sessionId == null || sessionId.isBlank() || event == null) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            ActiveSession activeSession = sessionRegistry.getSession(sessionId).orElse(null);
            if (activeSession == null || !activeSession.isTherapistAuthenticated()) {
                return false;
            }
            try {
                String json = objectMapper.writeValueAsString(event);
                return activeSession.sendTherapistText(json);
            } catch (JsonProcessingException ex) {
                log.error("Failed serializing TherapistTranscriptEvent for session '{}': {}",
                        sessionId, ex.getMessage(), ex);
                return false;
            }
        }, orchestratorExecutor);
    }

    private void handleAuth(WebSocketSession session, String sessionId, String token) {
        if (!isValidJwtFormat(token)) {
            log.warn("Rejecting Therapist WebSocket '{}' for session '{}' due to invalid or missing JWT.",
                    session.getId(), sessionId);
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Invalid or missing auth token"));
            return;
        }

        ActiveSession active = ensureRegisteredTherapistSession(session, sessionId);
        active.setTherapistAuthenticated(true);
        active.touch();
        log.info("Therapist WebSocket '{}' authenticated for session '{}'.", session.getId(), sessionId);
    }

    private void handleLiveSteering(WebSocketSession session, String sessionId, TherapistControlMessage control) {
        ActiveSession active = requireAuthenticatedSession(session, sessionId);
        if (active == null) {
            return;
        }
        String instruction = control.instruction();
        if (instruction == null || instruction.isBlank()) {
            log.warn("Ignoring blank live_steering instruction for session '{}'.", sessionId);
            return;
        }

        active.offerLiveSteering(instruction);
        LiveSteeringListener listener = liveSteeringListenerProvider.getIfAvailable();
        if (listener != null) {
            orchestratorExecutor.execute(() -> {
                try {
                    listener.onLiveSteering(sessionId, instruction);
                } catch (RuntimeException ex) {
                    log.error("LiveSteeringListener failed for session '{}': {}",
                            sessionId, ex.getMessage(), ex);
                }
            });
        }
    }

    private void handlePing(WebSocketSession session, String sessionId) {
        TherapistControlMessage pong = new TherapistControlMessage(
                TherapistControlMessage.TYPE_PONG,
                sessionId,
                null,
                null,
                System.currentTimeMillis()
        );
        String json;
        try {
            json = objectMapper.writeValueAsString(pong);
        } catch (JsonProcessingException ex) {
            log.error("Failed serializing Therapist pong for session '{}': {}", sessionId, ex.getMessage());
            return;
        }

        ActiveSession active = sessionRegistry.getSession(sessionId).orElse(null);
        if (active != null && active.getTherapistSession() == session) {
            active.sendTherapistText(json);
        } else if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException | IllegalStateException ex) {
                log.debug("Failed sending Therapist pong on socket '{}': {}", session.getId(), ex.getMessage());
            }
        }
    }

    private ActiveSession requireAuthenticatedSession(WebSocketSession session, String sessionId) {
        ActiveSession active = sessionRegistry.getSession(sessionId).orElse(null);
        if (active == null || active.getTherapistSession() != session || !active.isTherapistAuthenticated()) {
            log.warn("Rejecting unauthenticated Therapist control frame for session '{}' on socket '{}'.",
                    sessionId, session.getId());
            ActiveSession.closeQuietly(session,
                    new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Authentication required"));
            return null;
        }
        return active;
    }

    private ActiveSession ensureRegisteredTherapistSession(WebSocketSession session, String sessionId) {
        String existingAttr = getAttributeSessionId(session);
        if (existingAttr != null && !existingAttr.equals(sessionId)) {
            sessionRegistry.unregisterTherapistSession(existingAttr, session);
        }
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);

        ActiveSession existing = sessionRegistry.getSession(sessionId).orElse(null);
        if (existing != null && existing.getTherapistSession() == session) {
            return existing;
        }
        return sessionRegistry.registerTherapistSession(sessionId, session);
    }

    private static String resolveMessageSessionId(WebSocketSession session, TherapistControlMessage control) {
        if (control.sessionId() != null && !control.sessionId().isBlank()) {
            return control.sessionId().trim();
        }
        return getAttributeSessionId(session);
    }

    private static String getAttributeSessionId(WebSocketSession session) {
        Object attr = session.getAttributes().get(ATTR_SESSION_ID);
        return (attr instanceof String s && !s.isBlank()) ? s : null;
    }

    static boolean isValidJwtFormat(String token) {
        return token != null && !token.isBlank() && JWT_FORMAT_PATTERN.matcher(token.trim()).matches();
    }
}
