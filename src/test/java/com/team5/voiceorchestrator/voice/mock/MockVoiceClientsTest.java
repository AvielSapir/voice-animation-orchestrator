package com.team5.voiceorchestrator.voice.mock;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockVoiceClientsTest {

    @Test
    void mockSttClientReturnsNonBlankHebrewText() {
        MockSttClient sttClient = new MockSttClient();

        for (int i = 0; i < 6; i++) {
            String transcript = sttClient.transcribe("session-1", new byte[]{1, 2, 3, 4}).join();
            assertNotNull(transcript);
            assertFalse(transcript.isBlank(), "Transcript must never be blank");
            assertTrue(
                    transcript.codePoints().anyMatch(cp -> cp >= 0x0590 && cp <= 0x05FF),
                    "Transcript should contain Hebrew characters: " + transcript
            );
        }
    }

    @Test
    void mockTtsClientReturnsRawPcmS16LeMatchingAudioProperties() {
        OrchestratorProperties properties = new OrchestratorProperties();
        OrchestratorProperties.Audio audio = properties.getAudio();
        MockTtsClient ttsClient = new MockTtsClient(properties);

        byte[] pcm = ttsClient.synthesize("session-1", "שלום, מה שלומך היום?", Map.of("tone", "warm")).join();

        int expectedBytes = audio.getSampleRateHz()
                * audio.getChannels()
                * (audio.getBitsPerSample() / 8)
                * MockTtsClient.DEFAULT_DURATION_MS
                / 1000;

        assertNotNull(pcm);
        assertTrue(pcm.length > 0, "PCM output must be non-empty");
        assertEquals(0, pcm.length % 2, "PCM_S16LE output must have an even byte length");
        assertEquals(expectedBytes, pcm.length, "PCM byte count must match orchestrator.audio.* configuration");

        String header = new String(pcm, 0, Math.min(4, pcm.length), StandardCharsets.US_ASCII);
        assertNotEquals("RIFF", header, "PCM output must not contain a RIFF/WAV header");
    }
}
