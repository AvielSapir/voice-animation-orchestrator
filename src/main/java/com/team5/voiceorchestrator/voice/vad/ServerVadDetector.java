package com.team5.voiceorchestrator.voice.vad;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.VadDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Energy-based server-side Voice Activity Detector (VAD) for 16-bit signed little-endian PCM chunks.
 * <p>
 * Buffers active speech chunks per session and tolerates short mid-sentence pauses shorter than
 * {@code orchestrator.vad.silence-threshold-ms} before emitting {@link VadState#SPEECH_END}.
 */
@Service
public class ServerVadDetector implements VadDetector {

    private static final Logger log = LoggerFactory.getLogger(ServerVadDetector.class);

    private final OrchestratorProperties properties;
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ServerVadDetector(OrchestratorProperties properties) {
        this.properties = properties;
    }

    @Override
    public VadResult processChunk(String sessionId, byte[] audioChunk) {
        if (sessionId == null || sessionId.isBlank() || audioChunk == null || audioChunk.length < 2) {
            return new VadResult(VadState.SILENCE, null);
        }

        double energy = computeRmsEnergy(audioChunk);
        long chunkDurationMs = computeChunkDurationMs(audioChunk.length);

        OrchestratorProperties.Vad vadProps = properties.getVad();
        double energyThreshold = vadProps.getEnergyThreshold();
        long silenceThresholdMs = vadProps.getSilenceThresholdMs();
        long minSpeechDurationMs = vadProps.getMinSpeechDurationMs();

        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        synchronized (state) {
            if (energy >= energyThreshold) {
                state.buffer.writeBytes(audioChunk);
                state.speechDurationMs += chunkDurationMs;
                state.silenceDurationMs = 0L;

                if (!state.inSpeech) {
                    state.inSpeech = true;
                    log.debug("VAD speech_start for session '{}' (energy={}).", sessionId, energy);
                    return new VadResult(VadState.SPEECH_START, null);
                }
                return new VadResult(VadState.SPEECH_ACTIVE, null);
            }

            if (!state.inSpeech) {
                return new VadResult(VadState.SILENCE, null);
            }

            // Inside active speech: buffer trailing silence so short mid-sentence pauses are preserved.
            state.buffer.writeBytes(audioChunk);
            state.silenceDurationMs += chunkDurationMs;

            if (state.silenceDurationMs < silenceThresholdMs) {
                return new VadResult(VadState.SPEECH_ACTIVE, null);
            }

            byte[] utterance = state.buffer.toByteArray();
            boolean validSpeech = state.speechDurationMs >= minSpeechDurationMs;
            long capturedSpeechMs = state.speechDurationMs;
            state.reset();

            if (!validSpeech) {
                log.debug("Discarded short noise burst for session '{}' ({}ms < {}ms).",
                        sessionId, capturedSpeechMs, minSpeechDurationMs);
                return new VadResult(VadState.SILENCE, null);
            }

            log.debug("VAD speech_end for session '{}' (speech={}ms, bytes={}).",
                    sessionId, capturedSpeechMs, utterance.length);
            return new VadResult(VadState.SPEECH_END, utterance);
        }
    }

    @Override
    public void resetSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private long computeChunkDurationMs(int byteCount) {
        OrchestratorProperties.Audio audio = properties.getAudio();
        int bytesPerSample = Math.max(1, audio.getBitsPerSample() / 8);
        int bytesPerSecond = Math.max(1, audio.getSampleRateHz() * audio.getChannels() * bytesPerSample);
        return Math.max(1L, (byteCount * 1000L) / bytesPerSecond);
    }

    static double computeRmsEnergy(byte[] pcm16Le) {
        int sampleCount = pcm16Le.length / 2;
        if (sampleCount == 0) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            int low = pcm16Le[i * 2] & 0xFF;
            int high = pcm16Le[i * 2 + 1];
            short sample = (short) ((high << 8) | low);
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }
        return Math.sqrt(sumSquares / sampleCount);
    }

    private static final class SessionState {
        boolean inSpeech;
        long speechDurationMs;
        long silenceDurationMs;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void reset() {
            inSpeech = false;
            speechDurationMs = 0L;
            silenceDurationMs = 0L;
            buffer.reset();
        }
    }
}
