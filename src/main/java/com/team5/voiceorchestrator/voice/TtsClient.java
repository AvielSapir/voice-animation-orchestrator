package com.team5.voiceorchestrator.voice;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Text-to-Speech (TTS) client abstraction.
 * <p>
 * Implemented by Dev B in {@code voice/mock/} (for the {@code mock} profile)
 * and {@code voice/providers/} (for the selected production TTS provider).
 */
public interface TtsClient {

    /**
     * Synthesizes spoken audio from text and tone directives asynchronously.
     *
     * @param sessionId     active child session identifier
     * @param text          reply text to synthesize
     * @param ttsDirectives tone/style directives from Team 3's AI response
     * @return future resolving to synthesized audio bytes in the agreed Unity format
     */
    CompletableFuture<byte[]> synthesize(String sessionId, String text, Map<String, String> ttsDirectives);
}
