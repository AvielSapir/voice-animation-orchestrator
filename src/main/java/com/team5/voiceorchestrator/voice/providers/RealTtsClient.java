package com.team5.voiceorchestrator.voice.providers;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Production {@link TtsClient} backed by Cartesia's {@code /tts/bytes} HTTP API,
 * returning raw 16kHz 16-bit signed little-endian mono PCM ({@code pcm_s16le}).
 */
@Service
@Profile("!mock")
public class RealTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(RealTtsClient.class);
    private static final String CARTESIA_TTS_BYTES_URL = "https://api.cartesia.ai/tts/bytes";
    private static final String CARTESIA_VERSION = "2025-04-16";

    private final OrchestratorProperties properties;
    private final WebClient webClient;

    public RealTtsClient(OrchestratorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String sessionId, String text, Map<String, String> ttsDirectives) {
        String apiKey = properties.getTts().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("CARTESIA_API_KEY / orchestrator.tts.api-key is not configured"));
        }
        String voiceId = properties.getTts().getVoiceId();
        if (voiceId == null || voiceId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("TTS_VOICE_ID / orchestrator.tts.voice-id is not configured"));
        }
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_id", properties.getTts().getModel());
        payload.put("transcript", text);
        payload.put("voice", Map.of(
                "mode", "id",
                "id", voiceId
        ));
        payload.put("language", properties.getTts().getLanguage());
        payload.put("output_format", Map.of(
                "container", "raw",
                "encoding", "pcm_s16le",
                "sample_rate", properties.getAudio().getSampleRateHz()
        ));

        Duration timeout = Duration.ofMillis(Math.max(500L, properties.getTts().getTimeoutMs()));

        return webClient.post()
                .uri(CARTESIA_TTS_BYTES_URL)
                .header("X-API-Key", apiKey)
                .header("Cartesia-Version", CARTESIA_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(timeout)
                .map(bytes -> bytes == null ? new byte[0] : bytes)
                .doOnSuccess(bytes -> log.debug("Cartesia TTS synthesized {} PCM bytes for session '{}'.",
                        bytes.length, sessionId))
                .doOnError(err -> log.error("Cartesia TTS failed for session '{}': {}", sessionId, err.getMessage()))
                .toFuture();
    }
}
