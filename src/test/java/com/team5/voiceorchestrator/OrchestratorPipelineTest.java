package com.team5.voiceorchestrator;

import com.team5.voiceorchestrator.ai.AiClient;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import com.team5.voiceorchestrator.orchestration.TherapistEventPublisher;
import com.team5.voiceorchestrator.orchestration.UnityResponsePublisher;
import com.team5.voiceorchestrator.simulator.UnityWebSocketSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class OrchestratorPipelineTest {

    @TestConfiguration
    static class RecordingPublishersConfig {

        @Bean
        RecordingTherapistEventPublisher therapistEventPublisher() {
            return new RecordingTherapistEventPublisher();
        }

        @Bean
        RecordingUnityResponsePublisher unityResponsePublisher() {
            return new RecordingUnityResponsePublisher();
        }
    }

    static class RecordingTherapistEventPublisher implements TherapistEventPublisher {
        private final List<TherapistTranscriptEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(TherapistTranscriptEvent event) {
            events.add(event);
        }

        List<TherapistTranscriptEvent> getEvents() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }

    static class RecordingUnityResponsePublisher implements UnityResponsePublisher {
        private final List<UnityResponsePacket> packets = new CopyOnWriteArrayList<>();

        @Override
        public void publish(UnityResponsePacket packet) {
            packets.add(packet);
        }

        List<UnityResponsePacket> getPackets() {
            return List.copyOf(packets);
        }

        void clear() {
            packets.clear();
        }
    }

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private RecordingTherapistEventPublisher therapistEventPublisher;

    @Autowired
    private RecordingUnityResponsePublisher unityResponsePublisher;

    @SpyBean
    private AiClient aiClient;

    private UnityWebSocketSimulator simulator;

    @BeforeEach
    void setUp() {
        therapistEventPublisher.clear();
        unityResponsePublisher.clear();
        clearInvocations(aiClient);
        simulator = new UnityWebSocketSimulator(orchestrator);
    }

    @Test
    void walkingSkeletonEndToEndTurnProducesAndPublishesValidUnityResponsePacket() {
        String sessionId = "session-skeleton-001";
        simulator.sendSessionStart(sessionId);

        Optional<UnityResponsePacket> maybePacket = simulator.simulateUtterance(sessionId);
        simulator.sendSessionEnd(sessionId);

        assertThat(maybePacket).isPresent();
        UnityResponsePacket packet = maybePacket.get();

        assertThat(packet.sessionId()).isEqualTo(sessionId);
        assertThat(packet.turnId()).isNotBlank();
        assertThat(packet.sampleRateHz()).isEqualTo(16_000);
        assertThat(packet.animationTriggerId()).isNotBlank();
        assertThat(packet.lipSyncFrames()).isNotNull();

        byte[] decodedPcm = Base64.getDecoder().decode(packet.audioBase64());
        assertThat(decodedPcm).isNotEmpty();

        assertThat(unityResponsePublisher.getPackets())
                .hasSize(1)
                .first()
                .isEqualTo(packet);

        assertThat(simulator.getSentControlMessages())
                .extracting(UnityControlMessage::type)
                .containsExactly(UnityControlMessage.TYPE_SESSION_START, UnityControlMessage.TYPE_SESSION_END);
    }

    @Test
    void endToEndTurnPublishesTherapistTranscriptEventsForChildAndCharacter() {
        String sessionId = "session-transcript-002";
        simulator.sendSessionStart(sessionId);

        UnityResponsePacket packet = simulator.simulateUtterance(sessionId).orElseThrow();
        simulator.closeSession(sessionId);

        List<TherapistTranscriptEvent> events = therapistEventPublisher.getEvents();
        assertThat(events).hasSize(2);

        TherapistTranscriptEvent childEvent = events.get(0);
        assertThat(childEvent.sessionId()).isEqualTo(sessionId);
        assertThat(childEvent.turnId()).isEqualTo(packet.turnId());
        assertThat(childEvent.speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHILD);
        assertThat(childEvent.text()).isNotBlank();
        assertThat(childEvent.animationTriggerId()).isNull();

        TherapistTranscriptEvent characterEvent = events.get(1);
        assertThat(characterEvent.sessionId()).isEqualTo(sessionId);
        assertThat(characterEvent.turnId()).isEqualTo(packet.turnId());
        assertThat(characterEvent.speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHARACTER);
        assertThat(characterEvent.text()).isNotBlank();
        assertThat(characterEvent.animationTriggerId()).isEqualTo(packet.animationTriggerId());
    }

    @Test
    void submitLiveSteeringIsForwardedToNextTurnAndConsumedOnce() {
        String sessionId = "session-steering-003";
        String instruction = "עודד אותו לשתף מה קרה בהפסקה, בטון רגוע";

        simulator.sendSessionStart(sessionId);
        simulator.submitLiveSteering(sessionId, instruction);

        Optional<UnityResponsePacket> firstTurn = simulator.simulateUtterance(sessionId);
        assertThat(firstTurn).isPresent();

        Optional<UnityResponsePacket> secondTurn = simulator.simulateUtterance(sessionId);
        assertThat(secondTurn).isPresent();

        simulator.closeSession(sessionId);

        ArgumentCaptor<AiTurnRequest> requestCaptor = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(aiClient, times(2)).requestTurn(requestCaptor.capture());

        List<AiTurnRequest> capturedRequests = requestCaptor.getAllValues();
        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests.get(0).sessionId()).isEqualTo(sessionId);
        assertThat(capturedRequests.get(0).liveSteering()).isEqualTo(instruction);
        assertThat(capturedRequests.get(1).sessionId()).isEqualTo(sessionId);
        assertThat(capturedRequests.get(1).liveSteering()).isNull();
    }

    @Test
    void onSessionClosedCleansUpPendingLiveSteering() {
        String sessionId = "session-cleanup-004";
        simulator.sendSessionStart(sessionId);
        simulator.submitLiveSteering(sessionId, "הוראה שאמורה להימחק בסגירת הסשן");

        simulator.closeSession(sessionId);

        Optional<UnityResponsePacket> nextTurn = simulator.simulateUtterance(sessionId);
        assertThat(nextTurn).isPresent();

        ArgumentCaptor<AiTurnRequest> requestCaptor = ArgumentCaptor.forClass(AiTurnRequest.class);
        verify(aiClient, times(1)).requestTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().liveSteering()).isNull();
    }
}
