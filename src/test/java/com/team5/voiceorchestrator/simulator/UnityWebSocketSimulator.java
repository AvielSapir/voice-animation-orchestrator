package com.team5.voiceorchestrator.simulator;

import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Test simulator that mimics Team 4's Unity WebSocket client by driving
 * {@link ConversationOrchestrator} directly with {@link UnityControlMessage}s,
 * 20ms 16kHz 16-bit mono {@code PCM_S16LE} audio chunks, and therapist live steering,
 * without depending on the {@code transport} layer.
 */
public class UnityWebSocketSimulator {

    public static final int SAMPLE_RATE_HZ = 16_000;
    public static final int CHUNK_DURATION_MS = 20;
    public static final int SAMPLES_PER_CHUNK = SAMPLE_RATE_HZ * CHUNK_DURATION_MS / 1000;
    public static final int BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2;

    private static final int DEFAULT_SPEECH_CHUNKS = 20;
    private static final int DEFAULT_SILENCE_CHUNKS = 60;
    private static final double DEFAULT_TONE_HZ = 440.0;
    private static final int DEFAULT_AMPLITUDE = 8_000;
    private static final long TURN_TIMEOUT_SECONDS = 10L;

    private final ConversationOrchestrator orchestrator;
    private final List<UnityControlMessage> sentControlMessages = new CopyOnWriteArrayList<>();

    public UnityWebSocketSimulator(ConversationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Simulates Unity sending a {@code session_start} control message.
     *
     * @param sessionId active child session identifier
     * @return the constructed {@link UnityControlMessage}
     */
    public UnityControlMessage sendSessionStart(String sessionId) {
        UnityControlMessage message = UnityControlMessage.of(
                UnityControlMessage.TYPE_SESSION_START,
                sessionId,
                System.currentTimeMillis()
        );
        sentControlMessages.add(message);
        return message;
    }

    /**
     * Simulates Unity sending a {@code session_end} control message and closing the session.
     *
     * @param sessionId active child session identifier
     * @return the constructed {@link UnityControlMessage}
     */
    public UnityControlMessage sendSessionEnd(String sessionId) {
        UnityControlMessage message = UnityControlMessage.of(
                UnityControlMessage.TYPE_SESSION_END,
                sessionId,
                System.currentTimeMillis()
        );
        sentControlMessages.add(message);
        orchestrator.onSessionClosed(sessionId);
        return message;
    }

    /**
     * Sends a single 20ms PCM audio chunk to {@link ConversationOrchestrator#handleAudioChunk}.
     *
     * @param sessionId  active child session identifier
     * @param audioChunk raw {@code PCM_S16LE} audio bytes (typically 640 bytes)
     * @return future resolving to a {@link UnityResponsePacket} when an utterance completes
     */
    public CompletableFuture<Optional<UnityResponsePacket>> sendAudioChunk(String sessionId, byte[] audioChunk) {
        return orchestrator.handleAudioChunk(sessionId, audioChunk);
    }

    /**
     * Simulates a complete spoken utterance (voiced chunks followed by trailing silence)
     * using default durations sufficient to trigger VAD {@code SPEECH_END}.
     *
     * @param sessionId active child session identifier
     * @return the resulting {@link UnityResponsePacket}, if an utterance completed
     */
    public Optional<UnityResponsePacket> simulateUtterance(String sessionId) {
        return simulateUtterance(sessionId, DEFAULT_SPEECH_CHUNKS, DEFAULT_SILENCE_CHUNKS);
    }

    /**
     * Simulates a complete spoken utterance by streaming {@code speechChunkCount} voiced
     * 20ms PCM chunks followed by {@code silenceChunkCount} silent 20ms PCM chunks.
     *
     * @param sessionId         active child session identifier
     * @param speechChunkCount  number of 20ms voiced chunks to send
     * @param silenceChunkCount number of 20ms silence chunks to send
     * @return the resulting {@link UnityResponsePacket}, if an utterance completed
     */
    public Optional<UnityResponsePacket> simulateUtterance(
            String sessionId,
            int speechChunkCount,
            int silenceChunkCount) {
        Optional<UnityResponsePacket> response = Optional.empty();

        for (int i = 0; i < speechChunkCount; i++) {
            Optional<UnityResponsePacket> chunkResult = awaitChunk(
                    orchestrator.handleAudioChunk(sessionId, createSpeechChunk())
            );
            if (chunkResult.isPresent()) {
                response = chunkResult;
            }
        }

        for (int i = 0; i < silenceChunkCount; i++) {
            Optional<UnityResponsePacket> chunkResult = awaitChunk(
                    orchestrator.handleAudioChunk(sessionId, createSilenceChunk())
            );
            if (chunkResult.isPresent()) {
                response = chunkResult;
                break;
            }
        }

        return response;
    }

    /**
     * Submits a covert therapist live-steering instruction for the next turn.
     *
     * @param sessionId   active child session identifier
     * @param instruction steering instruction text
     */
    public void submitLiveSteering(String sessionId, String instruction) {
        orchestrator.submitLiveSteering(sessionId, instruction);
    }

    /**
     * Notifies the orchestrator that the Unity WebSocket session has closed.
     *
     * @param sessionId closed session identifier
     */
    public void closeSession(String sessionId) {
        orchestrator.onSessionClosed(sessionId);
    }

    /**
     * Returns an immutable snapshot of all {@link UnityControlMessage}s sent through this simulator.
     */
    public List<UnityControlMessage> getSentControlMessages() {
        return List.copyOf(sentControlMessages);
    }

    /**
     * Generates a 20ms (640-byte) 16kHz 16-bit mono {@code PCM_S16LE} sine-wave speech chunk.
     */
    public static byte[] createSpeechChunk() {
        return createSpeechChunk(DEFAULT_TONE_HZ, DEFAULT_AMPLITUDE);
    }

    /**
     * Generates a 20ms (640-byte) 16kHz 16-bit mono {@code PCM_S16LE} sine-wave chunk.
     *
     * @param frequencyHz sine wave frequency in Hz
     * @param amplitude   peak 16-bit signed sample amplitude (0..32767)
     * @return 640-byte {@code PCM_S16LE} buffer
     */
    public static byte[] createSpeechChunk(double frequencyHz, int amplitude) {
        byte[] buffer = new byte[BYTES_PER_CHUNK];
        for (int i = 0; i < SAMPLES_PER_CHUNK; i++) {
            double angle = 2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE_HZ;
            short sample = (short) Math.round(amplitude * Math.sin(angle));
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    /**
     * Generates a 20ms (640-byte) 16kHz 16-bit mono {@code PCM_S16LE} silence chunk.
     */
    public static byte[] createSilenceChunk() {
        return new byte[BYTES_PER_CHUNK];
    }

    private static Optional<UnityResponsePacket> awaitChunk(CompletableFuture<Optional<UnityResponsePacket>> future) {
        try {
            return future.get(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Timed out or failed while awaiting orchestrator audio chunk", e);
        }
    }
}
