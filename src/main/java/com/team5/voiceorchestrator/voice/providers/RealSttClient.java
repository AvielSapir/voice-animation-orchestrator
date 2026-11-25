package com.team5.voiceorchestrator.voice.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.AudioWavUtils;
import com.team5.voiceorchestrator.voice.SttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Production {@link SttClient} backed by Groq's OpenAI-compatible Whisper transcription API.
 */
@Service
@Profile("!mock")
public class RealSttClient implements SttClient {

    private static final Logger log = LoggerFactory.getLogger(RealSttClient.class);
    private static final String GROQ_TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    private final OrchestratorProperties properties;
    private final WebClient webClient;

    public RealSttClient(OrchestratorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public CompletableFuture<String> transcribe(String sessionId, byte[] audioBytes) {
        String apiKey = properties.getStt().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("GROQ_API_KEY / orchestrator.stt.api-key is not configured"));
        }
        if (audioBytes == null || audioBytes.length == 0) {
            return CompletableFuture.completedFuture("");
        }

        OrchestratorProperties.Audio audio = properties.getAudio();
        byte[] wavBytes = AudioWavUtils.pcmToWav(
                audioBytes,
                audio.getSampleRateHz(),
                audio.getChannels(),
                audio.getBitsPerSample()
        );

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(wavBytes) {
            @Override
            public String getFilename() {
                return "utterance.wav";
            }
        }).contentType(MediaType.parseMediaType("audio/wav"));
        bodyBuilder.part("model", properties.getStt().getModel());
        bodyBuilder.part("language", properties.getStt().getLanguage());
        bodyBuilder.part("response_format", "json");

        MultiValueMap<String, HttpEntity<?>> multipartBody = bodyBuilder.build();
        Duration timeout = Duration.ofMillis(Math.max(500L, properties.getStt().getTimeoutMs()));

        return webClient.post()
                .uri(GROQ_TRANSCRIPTIONS_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(multipartBody))
                .retrieve()
                .bodyToMono(GroqTranscriptionResponse.class)
                .timeout(timeout)
                .map(resp -> resp != null && resp.text() != null ? resp.text().trim() : "")
                .doOnSuccess(text -> log.debug("Groq STT transcribed {} bytes for session '{}'.",
                        audioBytes.length, sessionId))
                .doOnError(err -> log.error("Groq STT failed for session '{}': {}", sessionId, err.getMessage()))
                .toFuture();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroqTranscriptionResponse(String text) {
    }
}
