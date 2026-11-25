package com.team5.voiceorchestrator.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.voice.AudioWavUtils;
import com.team5.voiceorchestrator.voice.SttClient;
import com.team5.voiceorchestrator.voice.TtsClient;
import com.team5.voiceorchestrator.voice.VadDetector;
import com.team5.voiceorchestrator.voice.VadDetector.VadResult;
import com.team5.voiceorchestrator.voice.VadDetector.VadState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Temporary browser-testing endpoints for live verification of Hebrew VAD + STT and TTS.
 */
@RestController
@RequestMapping("/api/test")
public class VoiceTestController {

    private final OrchestratorProperties properties;
    private final ObjectProvider<VadDetector> vadDetectorProvider;
    private final ObjectProvider<SttClient> sttClientProvider;
    private final ObjectProvider<TtsClient> ttsClientProvider;

    public VoiceTestController(
            OrchestratorProperties properties,
            ObjectProvider<VadDetector> vadDetectorProvider,
            ObjectProvider<SttClient> sttClientProvider,
            ObjectProvider<TtsClient> ttsClientProvider) {
        this.properties = properties;
        this.vadDetectorProvider = vadDetectorProvider;
        this.sttClientProvider = sttClientProvider;
        this.ttsClientProvider = ttsClientProvider;
    }

    @PostMapping(value = "/vad-chunk", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<VadChunkResponse> processVadChunk(
            @RequestParam(defaultValue = "browser-test-session") String sessionId,
            @RequestBody(required = false) byte[] audioChunk) {
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        if (vad == null) {
            return ResponseEntity.internalServerError().body(
                    new VadChunkResponse("error", "VadDetector bean is not available", null, null));
        }

        long vadStartNs = System.nanoTime();
        VadResult vadResult = vad.processChunk(sessionId, audioChunk == null ? new byte[0] : audioChunk);
        long vadLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - vadStartNs);

        if (vadResult.state() == VadState.SILENCE) {
            return ResponseEntity.ok(new VadChunkResponse("listening", null, vadLatencyMs, null));
        }
        if (vadResult.state() == VadState.SPEECH_START || vadResult.state() == VadState.SPEECH_ACTIVE) {
            return ResponseEntity.ok(new VadChunkResponse("speaking", null, vadLatencyMs, null));
        }

        SttClient stt = sttClientProvider.getIfAvailable();
        if (stt == null) {
            return ResponseEntity.ok(new VadChunkResponse("speech_ended", "", vadLatencyMs, 0L));
        }

        long sttStartNs = System.nanoTime();
        String transcript = stt.transcribe(sessionId, vadResult.utteranceAudio()).join();
        long sttLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sttStartNs);

        return ResponseEntity.ok(new VadChunkResponse("speech_ended", transcript, vadLatencyMs, sttLatencyMs));
    }

    @PostMapping("/vad-reset")
    public ResponseEntity<Map<String, String>> resetVad(
            @RequestParam(defaultValue = "browser-test-session") String sessionId) {
        VadDetector vad = vadDetectorProvider.getIfAvailable();
        if (vad != null) {
            vad.resetSession(sessionId);
        }
        return ResponseEntity.ok(Map.of("status", "reset", "sessionId", sessionId));
    }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "audio/wav")
    public ResponseEntity<byte[]> synthesizeTts(@RequestBody TtsTestRequest request) {
        TtsClient tts = ttsClientProvider.getIfAvailable();
        if (tts == null) {
            return ResponseEntity.internalServerError().build();
        }
        String text = request != null && request.text() != null ? request.text().trim() : "";
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, String> directives = (request.emotion() != null && !request.emotion().isBlank())
                ? Map.of("emotion", request.emotion().trim())
                : Map.of();

        byte[] pcmBytes = tts.synthesize("browser-tts-test", text, directives).join();
        OrchestratorProperties.Audio audio = properties.getAudio();
        byte[] wavBytes = AudioWavUtils.pcmToWav(
                pcmBytes,
                audio.getSampleRateHz(),
                audio.getChannels(),
                audio.getBitsPerSample()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                .body(wavBytes);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VadChunkResponse(
            String status,
            String transcript,
            Long vadLatencyMs,
            Long sttLatencyMs
    ) {
    }

    public record TtsTestRequest(
            String text,
            String emotion
    ) {
    }
}
