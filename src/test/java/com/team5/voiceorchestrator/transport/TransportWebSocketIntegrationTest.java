package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransportWebSocketIntegrationTest {

    private static final String VALID_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0aGVyYXBpc3QtMSJ9.signaturePart123";

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private TherapistWebSocketHandler therapistHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConversationOrchestrator conversationOrchestrator;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    @AfterEach
    void cleanUpSessions() {
        sessionRegistry.shutdown();
    }

    @Test
    void unityEndToEndAudioControlDuplicateReplacementAndCleanup() throws Exception {
        String sessionId = "e2e-unity-1";
        UnityResponsePacket expectedPacket = new UnityResponsePacket(
                sessionId,
                "turn-e2e-1",
                "AAAAAP//AQAB",
                16000,
                "talk_warm",
                List.of()
        );
        when(conversationOrchestrator.handleAudioChunk(eq(sessionId), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(expectedPacket)));

        BlockingQueue<String> firstClientMessages = new LinkedBlockingQueue<>();
        CountDownLatch firstClosedLatch = new CountDownLatch(1);
        WebSocketSession firstSession = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                firstClientMessages.offer(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                firstClosedLatch.countDown();
            }
        }, "ws://localhost:" + port + "/ws/unity?sessionId=" + sessionId).get(5, TimeUnit.SECONDS);

        // Heartbeat ping -> pong
        firstSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                UnityControlMessage.of(UnityControlMessage.TYPE_PING, sessionId, System.currentTimeMillis()))));
        String pongJson = firstClientMessages.poll(5, TimeUnit.SECONDS);
        assertThat(pongJson).isNotNull();
        assertThat(objectMapper.readValue(pongJson, UnityControlMessage.class).type())
                .isEqualTo(UnityControlMessage.TYPE_PONG);

        // Binary 640-byte PCM chunk -> mocked UnityResponsePacket
        firstSession.sendMessage(new BinaryMessage(new byte[640]));
        String packetJson = firstClientMessages.poll(5, TimeUnit.SECONDS);
        assertThat(packetJson).isNotNull();
        assertThat(objectMapper.readValue(packetJson, UnityResponsePacket.class)).isEqualTo(expectedPacket);

        // Duplicate connection with same sessionId supersedes firstSession cleanly
        CountDownLatch secondClosedLatch = new CountDownLatch(1);
        WebSocketSession secondSession = client.execute(new TextWebSocketHandler() {
            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                secondClosedLatch.countDown();
            }
        }, "ws://localhost:" + port + "/ws/unity?sessionId=" + sessionId).get(5, TimeUnit.SECONDS);

        assertThat(firstClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sessionRegistry.getSession(sessionId)).isPresent();

        secondSession.close(CloseStatus.NORMAL);
        assertThat(secondClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        verify(conversationOrchestrator, timeout(5000)).onSessionClosed(sessionId);
        assertThat(sessionRegistry.getSession(sessionId)).isEmpty();
    }

    @Test
    void therapistEndToEndAuthLiveSteeringAndTranscriptDelivery() throws Exception {
        String sessionId = "e2e-therapist-1";
        BlockingQueue<String> therapistMessages = new LinkedBlockingQueue<>();
        CountDownLatch closedLatch = new CountDownLatch(1);

        WebSocketSession therapistSocket = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                therapistMessages.offer(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closedLatch.countDown();
            }
        }, "ws://localhost:" + port + "/ws/therapist?sessionId=" + sessionId).get(5, TimeUnit.SECONDS);

        therapistSocket.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                TherapistControlMessage.auth(sessionId, VALID_JWT, System.currentTimeMillis()))));

        String steering = "תעודד אותו ותישאר בקול רגוע";
        therapistSocket.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                TherapistControlMessage.liveSteering(sessionId, steering, System.currentTimeMillis()))));

        long deadline = System.currentTimeMillis() + 5000;
        ActiveSession active = null;
        while (System.currentTimeMillis() < deadline) {
            active = sessionRegistry.getSession(sessionId).orElse(null);
            if (active != null && active.isTherapistAuthenticated() && steering.equals(active.peekLiveSteering())) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(active).isNotNull();
        assertThat(active.isTherapistAuthenticated()).isTrue();
        assertThat(active.pollLiveSteering()).isEqualTo(steering);

        TherapistTranscriptEvent event = TherapistTranscriptEvent.characterReply(
                sessionId, "turn-e2e-2", "אני כאן איתך, רוצה לספר לי עוד?", "talk_warm", System.currentTimeMillis());
        assertThat(therapistHandler.sendTranscriptEvent(sessionId, event).get(5, TimeUnit.SECONDS)).isTrue();

        String receivedJson = therapistMessages.poll(5, TimeUnit.SECONDS);
        assertThat(receivedJson).isNotNull();
        assertThat(objectMapper.readValue(receivedJson, TherapistTranscriptEvent.class)).isEqualTo(event);

        therapistSocket.close(CloseStatus.NORMAL);
        assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        long closeDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < closeDeadline && sessionRegistry.getSession(sessionId).isPresent()) {
            Thread.sleep(20);
        }
        assertThat(sessionRegistry.getSession(sessionId)).isEmpty();
    }
}
