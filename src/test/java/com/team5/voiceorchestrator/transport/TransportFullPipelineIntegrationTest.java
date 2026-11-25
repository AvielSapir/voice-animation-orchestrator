package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class TransportFullPipelineIntegrationTest {

    private static final String VALID_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0aGVyYXBpc3QtMSJ9.signaturePart123";

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private final StandardWebSocketClient client = createLargeBufferClient();

    private static StandardWebSocketClient createLargeBufferClient() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(256 * 1024);
        container.setDefaultMaxBinaryMessageBufferSize(256 * 1024);
        return new StandardWebSocketClient(container);
    }

    @AfterEach
    void cleanUp() {
        sessionRegistry.shutdown();
    }

    private static byte[] createVoicedChunk640() {
        byte[] buffer = new byte[640];
        for (int i = 0; i < 320; i++) {
            double angle = 2.0 * Math.PI * 440.0 * i / 16000.0;
            short sample = (short) Math.round(8000.0 * Math.sin(angle));
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    @Test
    void fullLivePipelineStreamsUnityAudioThroughVadSttAiTtsToUnityAndTherapistSockets() throws Exception {
        String sessionId = "full-pipeline-e2e-1";

        List<String> unityReceived = new CopyOnWriteArrayList<>();
        BlockingQueue<String> unityQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> therapistQueue = new LinkedBlockingQueue<>();
        CountDownLatch unityClosed = new CountDownLatch(1);
        CountDownLatch therapistClosed = new CountDownLatch(1);

        WebSocketSession therapistSocket = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                therapistQueue.offer(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                therapistClosed.countDown();
            }
        }, "ws://localhost:" + port + "/ws/therapist?sessionId=" + sessionId).get(5, TimeUnit.SECONDS);

        therapistSocket.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                TherapistControlMessage.auth(sessionId, VALID_JWT, System.currentTimeMillis()))));

        String steering = "תדבר בעדינות וברוגע";
        therapistSocket.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                TherapistControlMessage.liveSteering(sessionId, steering, System.currentTimeMillis()))));

        long authDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < authDeadline) {
            ActiveSession active = sessionRegistry.getSession(sessionId).orElse(null);
            if (active != null && active.isTherapistAuthenticated() && steering.equals(active.peekLiveSteering())) {
                break;
            }
            Thread.sleep(20);
        }

        WebSocketSession unitySocket = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                unityReceived.add(message.getPayload());
                unityQueue.offer(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                unityClosed.countDown();
            }
        }, "ws://localhost:" + port + "/ws/unity?sessionId=" + sessionId).get(5, TimeUnit.SECONDS);

        byte[] voiced = createVoicedChunk640();
        byte[] silence = new byte[640];

        // 20 chunks = 400ms speech (>= 250ms min-speech-duration-ms)
        for (int i = 0; i < 20; i++) {
            unitySocket.sendMessage(new BinaryMessage(voiced));
        }
        // 50 chunks = 1000ms silence (>= 900ms silence-threshold-ms in mock profile)
        for (int i = 0; i < 50; i++) {
            unitySocket.sendMessage(new BinaryMessage(silence));
        }

        String unityJson = unityQueue.poll(10, TimeUnit.SECONDS);
        assertThat(unityJson).isNotNull();
        UnityResponsePacket packet = objectMapper.readValue(unityJson, UnityResponsePacket.class);
        assertThat(packet.sessionId()).isEqualTo(sessionId);
        assertThat(packet.turnId()).isNotBlank();
        assertThat(packet.sampleRateHz()).isEqualTo(16000);
        assertThat(packet.animationTriggerId()).isNotBlank();
        assertThat(Base64.getDecoder().decode(packet.audioBase64())).isNotEmpty();

        String firstTherapistJson = therapistQueue.poll(10, TimeUnit.SECONDS);
        String secondTherapistJson = therapistQueue.poll(10, TimeUnit.SECONDS);
        assertThat(firstTherapistJson).isNotNull();
        assertThat(secondTherapistJson).isNotNull();

        TherapistTranscriptEvent childEvent = objectMapper.readValue(firstTherapistJson, TherapistTranscriptEvent.class);
        TherapistTranscriptEvent charEvent = objectMapper.readValue(secondTherapistJson, TherapistTranscriptEvent.class);
        assertThat(childEvent.speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHILD);
        assertThat(childEvent.turnId()).isEqualTo(packet.turnId());
        assertThat(charEvent.speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHARACTER);
        assertThat(charEvent.turnId()).isEqualTo(packet.turnId());
        assertThat(charEvent.animationTriggerId()).isEqualTo(packet.animationTriggerId());

        // Ensure deduplication prevented sending the same turn packet twice
        Thread.sleep(200);
        assertThat(unityReceived).hasSize(1);

        unitySocket.close(CloseStatus.NORMAL);
        therapistSocket.close(CloseStatus.NORMAL);
        assertThat(unityClosed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(therapistClosed.await(5, TimeUnit.SECONDS)).isTrue();

        long closeDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < closeDeadline && sessionRegistry.getSession(sessionId).isPresent()) {
            Thread.sleep(20);
        }
        assertThat(sessionRegistry.getSession(sessionId)).isEmpty();
    }
}
