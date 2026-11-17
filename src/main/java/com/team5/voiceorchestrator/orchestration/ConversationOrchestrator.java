package com.team5.voiceorchestrator.orchestration;

import com.team5.voiceorchestrator.ai.AiClient;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.voice.SttClient;
import com.team5.voiceorchestrator.voice.TtsClient;
import com.team5.voiceorchestrator.voice.VadDetector;
import com.team5.voiceorchestrator.voice.VadDetector.VadResult;
import com.team5.voiceorchestrator.voice.VadDetector.VadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central end-to-end pipeline coordinator:
 * {@code Audio Chunks -> VAD -> STT -> AiClient -> TTS + Animation -> Unity}.
 * <p>
 * Owned by the Tech Lead. Uses {@link ObjectProvider} for downstream components so the
 * scaffold compiles and boots cleanly while individual developer modules are being built.
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private final OrchestratorProperties properties;
    private final ObjectProvider<VadDetector> vadDetectorProvider;
    private final ObjectProvider<SttClient> sttClientProvider;
    private final ObjectProvider<AiClient> aiClientProvider;
    private final ObjectProvider<TtsClient> ttsClientProvider;

    public ConversationOrchestrator(
            OrchestratorProperties properties,
            ObjectProvider<VadDetector> vadDetectorProvider,
            ObjectProvider<SttClient> sttClientProvider,
            ObjectProvider<AiClient> aiClientProvider,
            ObjectProvider<TtsClient> ttsClientProvider) {
        this.properties = properties;
        this.vadDetectorProvider = vadDetectorProvider;
        this.sttClientProvider = sttClientProvider;
        this.aiClientProvider = aiClientProvider;
        this.ttsClientProvider = ttsClientProvider;
    }

    /**
     * Processes an incoming audio chunk from Unity and, once a complete utterance is
     * detected by VAD, executes the asynchronous STT -> AI -> TTS pipeline.
     *
     * @param sessionId  active child session identifier
     * @param audioChunk incoming PCM audio chunk
     * @return future resolving to a {@link UnityResponsePacket} when an utterance completes,
     * or {@link Optional#empty()} while listening or if pipeline dependencies are not yet wired
     */
    public CompletableFuture<Optional<UnityResponsePacket>> handleAudioChunk(String sessionId, byte[] audioChunk) {
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        SttClient stt = sttClientProvider.getIfAvailable();
        AiClient ai = aiClientProvider.getIfAvailable();
        TtsClient tts = ttsClientProvider.getIfAvailable();

        if (vad == null || stt == null || ai == null || tts == null) {
            log.debug("Pipeline components not fully wired yet for session '{}'.", sessionId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        VadResult vadResult = vad.processChunk(sessionId, audioChunk);
        if (vadResult.state() != VadState.SPEECH_END || vadResult.utteranceAudio() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String turnId = UUID.randomUUID().toString();
        int sampleRateHz = properties.getAudio().getSampleRateHz();

        return stt.transcribe(sessionId, vadResult.utteranceAudio())
                .thenCompose(transcript -> ai.requestTurn(new AiTurnRequest(sessionId, transcript)))
                .thenCompose(aiResponse -> tts.synthesize(sessionId, aiResponse.replyText(), aiResponse.ttsDirectives())
                        .thenApply(audioBytes -> Optional.of(new UnityResponsePacket(
                                sessionId,
                                turnId,
                                Base64.getEncoder().encodeToString(audioBytes),
                                sampleRateHz,
                                aiResponse.animationTriggerId(),
                                List.of()
                        ))));
    }

    /**
     * Cleans up per-session state when a Unity WebSocket connection closes.
     *
     * @param sessionId closed session identifier
     */
    public void onSessionClosed(String sessionId) {
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        if (vad != null) {
            vad.resetSession(sessionId);
        }
        log.debug("Cleaned up orchestrator state for closed session '{}'.", sessionId);
    }
}
