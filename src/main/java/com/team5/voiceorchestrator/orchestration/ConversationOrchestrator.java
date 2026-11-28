package com.team5.voiceorchestrator.orchestration;

import com.team5.voiceorchestrator.ai.AiClient;
import com.team5.voiceorchestrator.ai.AiSessionClosedException;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
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

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final ObjectProvider<TherapistEventPublisher> therapistEventPublisherProvider;
    private final ObjectProvider<UnityResponsePublisher> unityResponsePublisherProvider;
    private final ConcurrentHashMap<String, String> pendingLiveSteering = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ByteArrayOutputStream> fallbackAudioBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTurnQueues = new ConcurrentHashMap<>();
    private final Set<String> permanentlyClosedSessions = ConcurrentHashMap.newKeySet();

    public ConversationOrchestrator(
            OrchestratorProperties properties,
            ObjectProvider<VadDetector> vadDetectorProvider,
            ObjectProvider<SttClient> sttClientProvider,
            ObjectProvider<AiClient> aiClientProvider,
            ObjectProvider<TtsClient> ttsClientProvider,
            ObjectProvider<TherapistEventPublisher> therapistEventPublisherProvider,
            ObjectProvider<UnityResponsePublisher> unityResponsePublisherProvider) {
        this.properties = properties;
        this.vadDetectorProvider = vadDetectorProvider;
        this.sttClientProvider = sttClientProvider;
        this.aiClientProvider = aiClientProvider;
        this.ttsClientProvider = ttsClientProvider;
        this.therapistEventPublisherProvider = therapistEventPublisherProvider;
        this.unityResponsePublisherProvider = unityResponsePublisherProvider;
    }

    /**
     * Stores the latest pending covert therapist steering instruction for the given session.
     * The instruction is consumed on the next AI turn for that session.
     *
     * @param sessionId   active child session identifier
     * @param instruction covert steering instruction
     */
    public void onSessionStarted(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            permanentlyClosedSessions.remove(sessionId);
        }
    }

    public void submitLiveSteering(String sessionId, String instruction) {
        if (sessionId == null || sessionId.isBlank() || instruction == null || instruction.isBlank()) {
            return;
        }
        if (permanentlyClosedSessions.contains(sessionId)) {
            return;
        }
        pendingLiveSteering.put(sessionId, instruction);
    }

    /**
     * Processes an incoming audio chunk from Unity and, once a complete utterance is
     * available (via VAD or Phase 1 direct fallback), executes the asynchronous
     * STT -> AI -> TTS pipeline strictly sequentially per session and notifies any registered publishers.
     *
     * @param sessionId  active child session identifier
     * @param audioChunk incoming PCM audio chunk
     * @return future resolving to a {@link UnityResponsePacket} when an utterance completes,
     * or {@link Optional#empty()} while listening, if closed, or if pipeline dependencies are not yet wired
     */
    public CompletableFuture<Optional<UnityResponsePacket>> handleAudioChunk(String sessionId, byte[] audioChunk) {
        if (sessionId == null || sessionId.isBlank() || permanentlyClosedSessions.contains(sessionId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        SttClient stt = sttClientProvider.getIfAvailable();
        AiClient ai = aiClientProvider.getIfAvailable();
        TtsClient tts = ttsClientProvider.getIfAvailable();

        if (stt == null || ai == null || tts == null) {
            log.debug("Pipeline components not fully wired yet for session '{}'.", sessionId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        byte[] utteranceAudio;
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        if (vad != null) {
            VadResult vadResult = vad.processChunk(sessionId, audioChunk);
            if (vadResult.state() != VadState.SPEECH_END || vadResult.utteranceAudio() == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            utteranceAudio = vadResult.utteranceAudio();
        } else {
            if (audioChunk == null || audioChunk.length == 0) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            utteranceAudio = processFallbackChunk(sessionId, audioChunk);
            if (utteranceAudio == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        return enqueueTurn(sessionId, () -> executeTurn(sessionId, utteranceAudio, stt, ai, tts));
    }

    private CompletableFuture<Optional<UnityResponsePacket>> enqueueTurn(
            String sessionId,
            Supplier<CompletableFuture<Optional<UnityResponsePacket>>> turnSupplier) {
        CompletableFuture<Optional<UnityResponsePacket>> resultFuture = new CompletableFuture<>();
        CompletableFuture<Void> prev;
        CompletableFuture<Void> next = new CompletableFuture<>();

        synchronized (sessionTurnQueues) {
            prev = sessionTurnQueues.getOrDefault(sessionId, CompletableFuture.completedFuture(null));
            sessionTurnQueues.put(sessionId, next);
        }

        prev.handle((ignored, ex) -> null)
                .thenCompose(ignored -> {
                    if (permanentlyClosedSessions.contains(sessionId)) {
                        resultFuture.complete(Optional.empty());
                        return CompletableFuture.completedFuture((Void) null);
                    }
                    return turnSupplier.get()
                            .whenComplete((res, err) -> {
                                if (err != null) {
                                    resultFuture.completeExceptionally(err);
                                } else {
                                    resultFuture.complete(res);
                                }
                            })
                            .handle((res, err) -> (Void) null);
                })
                .whenComplete((ignored, ex) -> next.complete(null));

        return resultFuture;
    }

    private CompletableFuture<Optional<UnityResponsePacket>> executeTurn(
            String sessionId,
            byte[] utteranceAudio,
            SttClient stt,
            AiClient ai,
            TtsClient tts) {
        String turnId = UUID.randomUUID().toString();
        int sampleRateHz = properties.getAudio().getSampleRateHz();

        return stt.transcribe(sessionId, utteranceAudio)
                .thenCompose(transcript -> {
                    if (permanentlyClosedSessions.contains(sessionId)) {
                        return CompletableFuture.completedFuture(Optional.<UnityResponsePacket>empty());
                    }
                    publishTherapistEvent(TherapistTranscriptEvent.childUtterance(
                            sessionId, turnId, transcript, System.currentTimeMillis()));
                    String liveSteering = pendingLiveSteering.remove(sessionId);
                    return ai.requestTurn(new AiTurnRequest(sessionId, transcript, liveSteering))
                            .thenCompose(aiResponse -> {
                                if (permanentlyClosedSessions.contains(sessionId)) {
                                    return CompletableFuture.completedFuture(Optional.<UnityResponsePacket>empty());
                                }
                                publishTherapistEvent(TherapistTranscriptEvent.characterReply(
                                        sessionId,
                                        turnId,
                                        aiResponse.replyText(),
                                        aiResponse.animationTriggerId(),
                                        System.currentTimeMillis()));
                                return tts.synthesize(sessionId, aiResponse.replyText(), aiResponse.ttsDirectives())
                                        .thenApply(audioBytes -> {
                                            UnityResponsePacket packet = new UnityResponsePacket(
                                                    sessionId,
                                                    turnId,
                                                    Base64.getEncoder().encodeToString(audioBytes),
                                                    sampleRateHz,
                                                    aiResponse.animationTriggerId(),
                                                    List.of()
                                            );
                                            publishUnityResponse(packet);
                                            return Optional.of(packet);
                                        });
                            });
                })
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof AiSessionClosedException) {
                        log.info("Team 3 reported session '{}' closed (HTTP 410); stopping turns and cleaning up.", sessionId);
                        permanentlyClosedSessions.add(sessionId);
                        onSessionClosed(sessionId);
                        return Optional.empty();
                    }
                    throw ex instanceof RuntimeException re ? re : new CompletionException(cause);
                });
    }

    /**
     * Cleans up per-session state when a Unity WebSocket connection closes or Team 3 reports HTTP 410 Gone.
     *
     * @param sessionId closed session identifier
     */
    public void onSessionClosed(String sessionId) {
        if (sessionId != null) {
            pendingLiveSteering.remove(sessionId);
            fallbackAudioBuffers.remove(sessionId);
            synchronized (sessionTurnQueues) {
                sessionTurnQueues.remove(sessionId);
            }
        }
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        if (vad != null) {
            vad.resetSession(sessionId);
        }
        log.debug("Cleaned up orchestrator state for closed session '{}'.", sessionId);
    }

    private byte[] processFallbackChunk(String sessionId, byte[] audioChunk) {
        ByteArrayOutputStream buffer = fallbackAudioBuffers.computeIfAbsent(sessionId, ignored -> new ByteArrayOutputStream());
        synchronized (buffer) {
            if (isSilentChunk(audioChunk)) {
                if (buffer.size() == 0) {
                    return null;
                }
                byte[] utterance = buffer.toByteArray();
                buffer.reset();
                return utterance;
            }
            buffer.writeBytes(audioChunk);
            return null;
        }
    }

    private static boolean isSilentChunk(byte[] chunk) {
        for (byte b : chunk) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private void publishTherapistEvent(TherapistTranscriptEvent event) {
        TherapistEventPublisher publisher = therapistEventPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publish(event);
        }
    }

    private void publishUnityResponse(UnityResponsePacket packet) {
        UnityResponsePublisher publisher = unityResponsePublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publish(packet);
        }
    }
}
