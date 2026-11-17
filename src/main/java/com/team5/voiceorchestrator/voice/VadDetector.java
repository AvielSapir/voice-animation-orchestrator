package com.team5.voiceorchestrator.voice;

/**
 * Server-side Voice Activity Detection (VAD) abstraction.
 * <p>
 * Implemented by Dev B in {@code voice/vad/}.
 */
public interface VadDetector {

    /**
     * Processes an incoming audio chunk for a given session and detects speech boundaries.
     *
     * @param sessionId  active child session identifier
     * @param audioChunk incoming PCM audio chunk
     * @return detection result, including the full utterance audio when {@link VadState#SPEECH_END} occurs
     */
    VadResult processChunk(String sessionId, byte[] audioChunk);

    /**
     * Releases any buffered audio or energy state associated with a closed session.
     *
     * @param sessionId closed session identifier
     */
    void resetSession(String sessionId);

    enum VadState {
        SILENCE,
        SPEECH_START,
        SPEECH_ACTIVE,
        SPEECH_END
    }

    /**
     * Result of processing a single audio chunk through VAD.
     *
     * @param state          detected voice activity state
     * @param utteranceAudio complete buffered utterance bytes when {@code state == VadState.SPEECH_END}, otherwise {@code null}
     */
    record VadResult(VadState state, byte[] utteranceAudio) {
    }
}
