package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnityWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SessionRegistry sessionRegistry;
    private ConversationOrchestrator orchestrator;
    private UnityWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistry();
        orchestrator = mock(ConversationOrchestrator.class);
        Executor directExecutor = Runnable::run;
        handler = new UnityWebSocketHandler(sessionRegistry, orchestrator, objectMapper, directExecutor);
    }

    @AfterEach
    void tearDown() {
        sessionRegistry.shutdown();
    }

    private static WebSocketSession mockSession(String id, String uriString, HttpHeaders headers) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(uriString == null ? null : URI.create(uriString));
        when(session.getHandshakeHeaders()).thenReturn(headers == null ? new HttpHeaders() : headers);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void rejectsConnectionWithMissingOrBlankSessionId() throws Exception {
        WebSocketSession session = mockSession("ws-1", "ws://localhost:8080/ws/unity", null);

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(sessionRegistry.activeSessionCount()).isZero();
    }

    @Test
    void extractsSessionIdFromQueryParamAndHeaderFallback() {
        WebSocketSession querySession = mockSession("ws-q", "ws://localhost:8080/ws/unity?sessionId=child-101", null);
        handler.afterConnectionEstablished(querySession);
        assertThat(sessionRegistry.getSession("child-101")).isPresent();

        HttpHeaders headers = new HttpHeaders();
        headers.add(UnityWebSocketHandler.HEADER_SESSION_ID, "child-202");
        WebSocketSession headerSession = mockSession("ws-h", "ws://localhost:8080/ws/unity", headers);
        handler.afterConnectionEstablished(headerSession);
        assertThat(sessionRegistry.getSession("child-202")).isPresent();
    }

    @Test
    void forwardsBinaryPcmChunkAndSendsUnityResponsePacket() throws Exception {
        WebSocketSession session = mockSession("ws-pcm", "ws://localhost:8080/ws/unity?sessionId=child-303", null);
        handler.afterConnectionEstablished(session);

        byte[] pcmChunk = new byte[640];
        pcmChunk[0] = 0x12;
        pcmChunk[1] = 0x34;

        UnityResponsePacket responsePacket = new UnityResponsePacket(
                "child-303",
                "turn-1",
                "AAAA",
                16000,
                "talk_warm",
                List.of()
        );
        when(orchestrator.handleAudioChunk(eq("child-303"), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(responsePacket)));

        handler.handleBinaryMessage(session, new BinaryMessage(pcmChunk));

        verify(orchestrator).handleAudioChunk(eq("child-303"), eq(pcmChunk));
        ArgumentCaptor<TextMessage> sentCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sentCaptor.capture());

        UnityResponsePacket decoded = objectMapper.readValue(sentCaptor.getValue().getPayload(), UnityResponsePacket.class);
        assertThat(decoded).isEqualTo(responsePacket);
    }

    @Test
    void rejectsOddByteLengthAudioFrameWithErrorControlMessage() throws Exception {
        WebSocketSession session = mockSession("ws-odd", "ws://localhost:8080/ws/unity?sessionId=child-404", null);
        handler.afterConnectionEstablished(session);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2, 3}));

        verify(orchestrator, never()).handleAudioChunk(any(), any());
        ArgumentCaptor<TextMessage> sentCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sentCaptor.capture());

        UnityControlMessage err = objectMapper.readValue(sentCaptor.getValue().getPayload(), UnityControlMessage.class);
        assertThat(err.type()).isEqualTo(UnityControlMessage.TYPE_ERROR);
        assertThat(err.sessionId()).isEqualTo("child-404");
        assertThat(err.message()).contains("even byte length");
    }

    @Test
    void handlesPingBargeInSessionEndAndDisconnectCleanup() throws Exception {
        WebSocketSession session = mockSession("ws-ctrl", "ws://localhost:8080/ws/unity?sessionId=child-505", null);
        handler.afterConnectionEstablished(session);

        String pingJson = objectMapper.writeValueAsString(
                UnityControlMessage.of(UnityControlMessage.TYPE_PING, "child-505", 1000L));
        handler.handleTextMessage(session, new TextMessage(pingJson));

        ArgumentCaptor<TextMessage> pongCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(pongCaptor.capture());
        UnityControlMessage pong = objectMapper.readValue(pongCaptor.getValue().getPayload(), UnityControlMessage.class);
        assertThat(pong.type()).isEqualTo(UnityControlMessage.TYPE_PONG);

        ActiveSession active = sessionRegistry.getSession("child-505").orElseThrow();
        active.enqueueAudioChunk(new byte[640]);
        assertThat(active.getBufferedAudioCount()).isEqualTo(1);

        String bargeInJson = objectMapper.writeValueAsString(
                UnityControlMessage.of(UnityControlMessage.TYPE_BARGE_IN, "child-505", 1001L));
        handler.handleTextMessage(session, new TextMessage(bargeInJson));
        assertThat(active.getBufferedAudioCount()).isZero();

        String endJson = objectMapper.writeValueAsString(
                UnityControlMessage.of(UnityControlMessage.TYPE_SESSION_END, "child-505", 1002L));
        handler.handleTextMessage(session, new TextMessage(endJson));
        verify(session).close(CloseStatus.NORMAL);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(orchestrator).onSessionClosed("child-505");
        assertThat(sessionRegistry.getSession("child-505")).isEmpty();
    }
}
