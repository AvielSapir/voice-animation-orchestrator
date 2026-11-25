package com.team5.voiceorchestrator.orchestration;

import com.team5.voiceorchestrator.ai.AiClient;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.voice.SttClient;
import com.team5.voiceorchestrator.voice.TtsClient;
import com.team5.voiceorchestrator.voice.VadDetector;
import com.team5.voiceorchestrator.voice.VadDetector.VadResult;
import com.team5.voiceorchestrator.voice.VadDetector.VadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationOrchestratorTest {

    @Test
    void returnsEmptyWhenCoreClientsAreMissing() {
        ConversationOrchestrator orchestrator = new ConversationOrchestrator(
                new OrchestratorProperties(),
                providerOf(null),
                providerOf(null),
                providerOf((req) -> CompletableFuture.completedFuture(new AiTurnResponse("hi", Map.of(), "talk_warm"))),
                providerOf((s, t, d) -> CompletableFuture.completedFuture(new byte[]{1, 2})),
                providerOf(null),
                providerOf(null)
        );

        Optional<UnityResponsePacket> result = orchestrator.handleAudioChunk("s1", new byte[]{1, 2, 3}).join();
        assertThat(result).isEmpty();
    }

    @Test
    void phase1FallbackWithoutVadRunsPipelineAndPublishesEvents() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.getAudio().setSampleRateHz(24000);

        List<TherapistTranscriptEvent> therapistEvents = new ArrayList<>();
        List<UnityResponsePacket> unityPackets = new ArrayList<>();
        List<AiTurnRequest> aiRequests = new ArrayList<>();

        SttClient stt = (sessionId, audio) -> CompletableFuture.completedFuture("שלום");
        AiClient ai = req -> {
            aiRequests.add(req);
            return CompletableFuture.completedFuture(
                    new AiTurnResponse("היי חבר!", Map.of("emotion", "warm"), "wave_hello"));
        };
        TtsClient tts = (sessionId, text, directives) -> CompletableFuture.completedFuture(new byte[]{9, 8, 7});

        ConversationOrchestrator orchestrator = new ConversationOrchestrator(
                props,
                providerOf(null),
                providerOf(stt),
                providerOf(ai),
                providerOf(tts),
                providerOf(therapistEvents::add),
                providerOf(unityPackets::add)
        );

        assertThat(orchestrator.handleAudioChunk("s1", new byte[0]).join()).isEmpty();
        assertThat(orchestrator.handleAudioChunk("s1", new byte[]{0, 0}).join()).isEmpty();

        orchestrator.submitLiveSteering("s1", "   ");
        orchestrator.submitLiveSteering(" ", "ignored");
        orchestrator.submitLiveSteering("s1", "first instruction");
        orchestrator.submitLiveSteering("s1", "be extra gentle");

        assertThat(orchestrator.handleAudioChunk("s1", new byte[]{1, 2, 3}).join()).isEmpty();
        Optional<UnityResponsePacket> result = orchestrator.handleAudioChunk("s1", new byte[]{0, 0}).join();

        assertThat(result).isPresent();
        UnityResponsePacket packet = result.get();
        assertThat(packet.sessionId()).isEqualTo("s1");
        assertThat(packet.turnId()).isNotBlank();
        assertThat(packet.sampleRateHz()).isEqualTo(24000);
        assertThat(packet.animationTriggerId()).isEqualTo("wave_hello");
        assertThat(packet.audioBase64()).isEqualTo(Base64.getEncoder().encodeToString(new byte[]{9, 8, 7}));

        assertThat(unityPackets).containsExactly(packet);
        assertThat(aiRequests).containsExactly(new AiTurnRequest("s1", "שלום", "be extra gentle"));

        assertThat(therapistEvents).hasSize(2);
        assertThat(therapistEvents.get(0).speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHILD);
        assertThat(therapistEvents.get(0).text()).isEqualTo("שלום");
        assertThat(therapistEvents.get(0).turnId()).isEqualTo(packet.turnId());
        assertThat(therapistEvents.get(0).animationTriggerId()).isNull();

        assertThat(therapistEvents.get(1).speaker()).isEqualTo(TherapistTranscriptEvent.SPEAKER_CHARACTER);
        assertThat(therapistEvents.get(1).text()).isEqualTo("היי חבר!");
        assertThat(therapistEvents.get(1).turnId()).isEqualTo(packet.turnId());
        assertThat(therapistEvents.get(1).animationTriggerId()).isEqualTo("wave_hello");

        // Second turn should not reuse the already consumed liveSteering instruction
        assertThat(orchestrator.handleAudioChunk("s1", new byte[]{4, 5}).join()).isEmpty();
        orchestrator.handleAudioChunk("s1", new byte[]{0, 0}).join();
        assertThat(aiRequests.get(1).liveSteering()).isNull();
    }

    @Test
    void usesVadWhenPresentAndCleansUpOnSessionClosed() {
        AtomicReference<String> resetSessionId = new AtomicReference<>();
        AtomicReference<byte[]> sttReceivedAudio = new AtomicReference<>();
        List<AiTurnRequest> aiRequests = new ArrayList<>();

        VadDetector vad = new VadDetector() {
            @Override
            public VadResult processChunk(String sessionId, byte[] pcmChunk) {
                if (pcmChunk.length == 1) {
                    return new VadResult(VadState.SPEECH_ACTIVE, null);
                }
                return new VadResult(VadState.SPEECH_END, new byte[]{10, 20, 30});
            }

            @Override
            public void resetSession(String sessionId) {
                resetSessionId.set(sessionId);
            }
        };

        SttClient stt = (sessionId, audio) -> {
            sttReceivedAudio.set(audio);
            return CompletableFuture.completedFuture("מה נשמע");
        };
        AiClient ai = req -> {
            aiRequests.add(req);
            return CompletableFuture.completedFuture(new AiTurnResponse("הכל טוב", Map.of(), "talk_warm"));
        };
        TtsClient tts = (sessionId, text, directives) -> CompletableFuture.completedFuture(new byte[]{1});

        ConversationOrchestrator orchestrator = new ConversationOrchestrator(
                new OrchestratorProperties(),
                providerOf(vad),
                providerOf(stt),
                providerOf(ai),
                providerOf(tts),
                providerOf(null),
                providerOf(null)
        );

        assertThat(orchestrator.handleAudioChunk("s2", new byte[]{1}).join()).isEmpty();

        orchestrator.submitLiveSteering("s2", "stale steering");
        orchestrator.onSessionClosed("s2");
        assertThat(resetSessionId.get()).isEqualTo("s2");

        Optional<UnityResponsePacket> result = orchestrator.handleAudioChunk("s2", new byte[]{1, 2}).join();
        assertThat(result).isPresent();
        assertThat(sttReceivedAudio.get()).containsExactly(10, 20, 30);
        assertThat(aiRequests).containsExactly(new AiTurnRequest("s2", "מה נשמע", null));
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) throws BeansException {
                return instance;
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return instance;
            }

            @Override
            public T getIfUnique() throws BeansException {
                return instance;
            }

            @Override
            public T getObject() throws BeansException {
                return instance;
            }
        };
    }
}
