package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.config.AsyncConfig;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
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
import java.util.List;
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
    public static final String FALLBACK_ANIMATION_TRIGGER = "talk_warm";

    private static final Logger log = LoggerFactory.getLogger(UnityWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final ConversationOrchestrator conversationOrchestrator;
    private final ObjectMapper objectMapper;
    private final OrchestratorProperties properties;
    private final Executor orchestratorExecutor;
    private final int bytesPerSampleFrame;
    private final int targetChunkBytes;
    private final int maxBinaryMessageSizeBytes;

    public UnityWebSocketHandler(
            SessionRegistry sessionRegistry,
            ConversationOrchestrator conversationOrchestrator,
            ObjectMapper objectMapper,
            OrchestratorProperties properties,
            @Qualifier(AsyncConfig.ORCHESTRATOR_EXECUTOR) Executor orchestratorExecutor) {
        this.sessionRegistry = sessionRegistry;
        this.conversationOrchestrator = conversationOrchestrator;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orchestratorExecutor = orchestratorExecutor;

        OrchestratorProperties.Audio audio = properties.getAudio();
        int sampleBytes = Math.max(1, audio.getBitsPerSample() / 8);
        this.bytesPerSampleFrame = Math.max(1, sampleBytes * Math.max(1, audio.getChannels()));
        int computedTarget = (audio.getSampleRateHz() * audio.getChunkDurationMs() / 1000) * this.bytesPerSampleFrame;
        this.targetChunkBytes = Math.max(this.bytesPerSampleFrame, computedTarget);
        this.maxBinaryMessageSizeBytes = properties.getWebsocket().getMaxBinaryMessageSizeBytes();
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

        if (length > maxBinaryMessageSizeBytes) {
            log.warn("Rejecting oversized binary audio frame ({} bytes > max {}) for session '{}'.",
                    length, maxBinaryMessageSizeBytes, sessionId);
            sendErrorControl(sessionId, "Audio frame exceeds maximum size of " + maxBinaryMessageSizeBytes + " bytes");
            return;
        }

        if (length % bytesPerSampleFrame != 0) {
            log.warn("Received odd-length PCM frame ({} bytes) for session '{}'.", length, sessionId);
            sendErrorControl(sessionId, "Audio frame must have even byte length (16-bit PCM_S16LE)");
            return;
        }

        byte[] payload = new byte[length];
        buffer.get(payload);

        if (isWavRiffHeader(payload)) {
            log.warn("Rejecting WAV/RIFF-framed audio payload for session '{}'; raw PCM_S16LE required.", sessionId);
            sendErrorControl(sessionId, "Audio frame must be raw PCM_S16LE without WAV/RIFF header");
            return;
        }

        sessionRegistry.getSession(sessionId).ifPresent(activeSession -> {
            activeSession.touchUnity();
            List<byte[]> normalizedChunks = activeSession.normalizePcmFrame(payload, targetChunkBytes);
            if (normalizedChunks.isEmpty()) {
                return;
            }
            for (byte[] chunk : normalizedChunks) {
                activeSession.enqueueAudioChunk(chunk);
            }
            activeSession.drainAudioAsync(orchestratorExecutor, chunk -> forwardAudioChunk(sessionId, chunk));
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
            sendErrorControl(sessionId, "Malformed control JSON");
            return;
        }

        if (control == null || control.type() == null || control.type().isBlank()) {
            sendErrorControl(sessionId, "Control message type must not be blank");
            return;
        }

        sessionRegistry.getSession(sessionId).ifPresent(ActiveSession::touchUnity);

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
                sendErrorControl(sessionId, "Unsupported control message type: " + control.type());
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
     * Publishes a {@link UnityResponsePacket} to the Unity socket for its {@code sessionId},
     * deduplicating by {@code turnId} so publisher hooks and futures never send twice.
     */
    public CompletableFuture<Boolean> publishResponsePacket(UnityResponsePacket packet) {
        if (packet == null || packet.sessionId() == null || packet.sessionId().isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return sendTurnPacketOnce(packet.sessionId(), packet);
    }

    /**
     * Sends a {@link UnityResponsePacket} only if its {@code turnId} has not yet been dispatched.
     */
    public CompletableFuture<Boolean> sendTurnPacketOnce(String sessionId, UnityResponsePacket packet) {
        if (sessionId == null || sessionId.isBlank() || packet == null) {
            return CompletableFuture.completedFuture(false);
        }
        ActiveSession activeSession = sessionRegistry.getSession(sessionId).orElse(null);
        if (activeSession == null || !activeSession.tryClaimTurnId(packet.turnId())) {
            return CompletableFuture.completedFuture(false);
        }
        return sendResponsePacket(sessionId, packet);
    }

    /**
     * Asynchronously serializes and enqueues a {@link UnityResponsePacket} for ordered delivery.
     */
    public CompletableFuture<Boolean> sendResponsePacket(String sessionId, UnityResponsePacket packet) {
        if (sessionId == null || sessionId.isBlank() || packet == null) {
            return CompletableFuture.completedFuture(false);
        }
        return sendJsonAsync(sessionId, normalizeResponsePacket(packet));
    }

    /**
     * Streams a sequence of {@link UnityResponsePacket} frames smoothly in FIFO order to Unity.
     */
    public CompletableFuture<Boolean> sendResponsePackets(String sessionId, List<UnityResponsePacket> packets) {
        if (sessionId == null || sessionId.isBlank() || packets == null || packets.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> combined = CompletableFuture.completedFuture(true);
        for (UnityResponsePacket packet : packets) {
            if (packet != null) {
                combined = combined.thenCombine(sendResponsePacket(sessionId, packet), (prev, cur) -> prev && cur);
            }
        }
        return combined;
    }

    /**
     * Asynchronously serializes and enqueues a {@link UnityControlMessage} for ordered delivery.
     */
    public CompletableFuture<Boolean> sendControlMessage(String sessionId, UnityControlMessage controlMessage) {
        if (sessionId == null || sessionId.isBlank() || controlMessage == null) {
            return CompletableFuture.completedFuture(false);
        }
        return sendJsonAsync(sessionId, controlMessage);
    }

    private void sendErrorControl(String sessionId, String detail) {
        sendControlMessage(sessionId, new UnityControlMessage(
                UnityControlMessage.TYPE_ERROR,
                sessionId,
                System.currentTimeMillis(),
                detail
        ));
    }

    private UnityResponsePacket normalizeResponsePacket(UnityResponsePacket packet) {
        int defaultSampleRate = properties.getAudio().getSampleRateHz();
        int sampleRateHz = packet.sampleRateHz() > 0 ? packet.sampleRateHz() : defaultSampleRate;
        String triggerId = (packet.animationTriggerId() == null || packet.animationTriggerId().isBlank())
                ? FALLBACK_ANIMATION_TRIGGER
                : packet.animationTriggerId();
        if (sampleRateHz == packet.sampleRateHz() && triggerId.equals(packet.animationTriggerId())) {
            return packet;
        }
        return new UnityResponsePacket(
                packet.sessionId(),
                packet.turnId(),
                packet.audioBase64(),
                sampleRateHz,
                triggerId,
                packet.lipSyncFrames()
        );
    }

    private CompletableFuture<Boolean> sendJsonAsync(String sessionId, Object payload) {
        ActiveSession activeSession = sessionRegistry.getSession(sessionId).orElse(null);
        if (activeSession == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            return activeSession.enqueueUnityTextAsync(json, orchestratorExecutor);
        } catch (JsonProcessingException ex) {
            log.error("Failed serializing outbound Unity payload for session '{}': {}",
                    sessionId, ex.getMessage(), ex);
            return CompletableFuture.completedFuture(false);
        }
    }

    private void forwardAudioChunk(String sessionId, byte[] chunk) {
        try {
            conversationOrchestrator.handleAudioChunk(sessionId, chunk)
                    .thenAccept(optionalPacket ->
                            optionalPacket.ifPresent(packet -> sendTurnPacketOnce(sessionId, packet)))
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

    static boolean isWavRiffHeader(byte[] payload) {
        return payload.length >= 12
                && payload[0] == 'R' && payload[1] == 'I' && payload[2] == 'F' && payload[3] == 'F'
                && payload[8] == 'W' && payload[9] == 'A' && payload[10] == 'V' && payload[11] == 'E';
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
