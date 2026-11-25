package com.team5.voiceorchestrator.voice.vad;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.VadDetector.VadResult;
import com.team5.voiceorchestrator.voice.VadDetector.VadState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerVadDetectorTest {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int CHUNK_MS = 20;
    private static final int BYTES_PER_CHUNK = (SAMPLE_RATE_HZ * 2 * CHUNK_MS) / 1000; // 640 bytes

    private ServerVadDetector vadDetector;

    @BeforeEach
    void setUp() {
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getVad().setEnergyThreshold(0.02);
        properties.getVad().setSilenceThresholdMs(1000);
        properties.getVad().setMinSpeechDurationMs(250);
        vadDetector = new ServerVadDetector(properties);
    }

    @Test
    void shortMidSentencePauseDoesNotPrematurelyCutOffSpeech() {
        String sessionId = "vad-pause-test";

        // 300ms of speech (15 chunks x 20ms)
        VadResult first = vadDetector.processChunk(sessionId, voicedChunk());
        assertThat(first.state()).isEqualTo(VadState.SPEECH_START);
        for (int i = 1; i < 15; i++) {
            assertThat(vadDetector.processChunk(sessionId, voicedChunk()).state()).isEqualTo(VadState.SPEECH_ACTIVE);
        }

        // 400ms mid-sentence pause (20 silent chunks < 1000ms threshold) -> must stay SPEECH_ACTIVE
        for (int i = 0; i < 20; i++) {
            VadResult pauseResult = vadDetector.processChunk(sessionId, silenceChunk());
            assertThat(pauseResult.state()).isEqualTo(VadState.SPEECH_ACTIVE);
            assertThat(pauseResult.utteranceAudio()).isNull();
        }

        // Resume speaking for 200ms (10 chunks)
        for (int i = 0; i < 10; i++) {
            assertThat(vadDetector.processChunk(sessionId, voicedChunk()).state()).isEqualTo(VadState.SPEECH_ACTIVE);
        }

        // Trailing silence >= 1000ms (50 chunks x 20ms) -> triggers SPEECH_END with full buffered utterance
        VadResult endResult = null;
        for (int i = 0; i < 50; i++) {
            endResult = vadDetector.processChunk(sessionId, silenceChunk());
            if (i < 49) {
                assertThat(endResult.state()).isEqualTo(VadState.SPEECH_ACTIVE);
            }
        }

        assertThat(endResult).isNotNull();
        assertThat(endResult.state()).isEqualTo(VadState.SPEECH_END);
        // 15 speech + 20 pause + 10 speech + 50 silence = 95 chunks buffered
        assertThat(endResult.utteranceAudio()).hasSize(95 * BYTES_PER_CHUNK);
    }

    @Test
    void shortNoiseBurstBelowMinSpeechDurationIsDiscarded() {
        String sessionId = "vad-noise-test";

        // 100ms noise (5 chunks < 250ms minSpeechDurationMs)
        assertThat(vadDetector.processChunk(sessionId, voicedChunk()).state()).isEqualTo(VadState.SPEECH_START);
        for (int i = 1; i < 5; i++) {
            vadDetector.processChunk(sessionId, voicedChunk());
        }

        VadResult result = null;
        for (int i = 0; i < 50; i++) {
            result = vadDetector.processChunk(sessionId, silenceChunk());
        }

        assertThat(result).isNotNull();
        assertThat(result.state()).isEqualTo(VadState.SILENCE);
        assertThat(result.utteranceAudio()).isNull();
    }

    @Test
    void resetSessionClearsBufferedState() {
        String sessionId = "vad-reset-test";
        assertThat(vadDetector.processChunk(sessionId, voicedChunk()).state()).isEqualTo(VadState.SPEECH_START);

        vadDetector.resetSession(sessionId);

        assertThat(vadDetector.processChunk(sessionId, silenceChunk()).state()).isEqualTo(VadState.SILENCE);
    }

    private static byte[] voicedChunk() {
        byte[] buffer = new byte[BYTES_PER_CHUNK];
        int samples = BYTES_PER_CHUNK / 2;
        for (int i = 0; i < samples; i++) {
            double angle = 2.0 * Math.PI * 440.0 * i / SAMPLE_RATE_HZ;
            short sample = (short) Math.round(8000 * Math.sin(angle));
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    private static byte[] silenceChunk() {
        return new byte[BYTES_PER_CHUNK];
    }
}
