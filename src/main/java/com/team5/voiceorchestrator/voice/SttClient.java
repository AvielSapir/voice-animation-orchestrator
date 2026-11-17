package com.team5.voiceorchestrator.voice;

import java.util.concurrent.CompletableFuture;

/**
 * Speech-to-Text (STT) client abstraction.
 * <p>
 * Implemented by Dev B in {@code voice/mock/} (for the {@code mock} profile)
 * and {@code voice/providers/} (for the selected production STT provider).
 */
public interface SttClient {

    /**
     * Transcribes a completed speech utterance into text asynchronously.
     *
     * @param sessionId  active child session identifier
     * @param audioBytes raw PCM audio bytes of the utterance
     * @return future resolving to the transcribed text
     */
    CompletableFuture<String> transcribe(String sessionId, byte[] audioBytes);
}
