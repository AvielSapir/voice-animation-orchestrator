package com.team5.voiceorchestrator.voice.mock;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.TtsClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mock {@link TtsClient} implementation active under the {@code mock} Spring profile.
 * <p>
 * Generates a short (~200 ms) soft sine wave as raw signed little-endian PCM bytes
 * matching {@code orchestrator.audio.*} without any WAV/RIFF header.
 */
@Service
@Profile("mock")
public class MockTtsClient implements TtsClient {

    static final int DEFAULT_DURATION_MS = 200;
    private static final double TONE_FREQUENCY_HZ = 440.0;
    private static final double AMPLITUDE_16BIT = 1500.0;

    private final OrchestratorProperties properties;

    public MockTtsClient(OrchestratorProperties properties) {
        this.properties = properties;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String sessionId, String text, Map<String, String> ttsDirectives) {
        OrchestratorProperties.Audio audio = properties.getAudio();
        int sampleRateHz = audio.getSampleRateHz();
        int channels = audio.getChannels();
        int bytesPerSample = Math.max(1, audio.getBitsPerSample() / 8);

        int totalFrames = (sampleRateHz * DEFAULT_DURATION_MS) / 1000;
        int totalBytes = totalFrames * channels * bytesPerSample;
        byte[] pcm = new byte[totalBytes];

        for (int frame = 0; frame < totalFrames; frame++) {
            double angle = (2.0 * Math.PI * TONE_FREQUENCY_HZ * frame) / sampleRateHz;
            short sample = (short) Math.round(Math.sin(angle) * AMPLITUDE_16BIT);

            for (int ch = 0; ch < channels; ch++) {
                int offset = (frame * channels + ch) * bytesPerSample;
                if (bytesPerSample == 2) {
                    pcm[offset] = (byte) (sample & 0xFF);
                    pcm[offset + 1] = (byte) ((sample >> 8) & 0xFF);
                } else {
                    pcm[offset] = (byte) ((sample >> 8) & 0xFF);
                }
            }
        }

        return CompletableFuture.completedFuture(pcm);
    }
}
