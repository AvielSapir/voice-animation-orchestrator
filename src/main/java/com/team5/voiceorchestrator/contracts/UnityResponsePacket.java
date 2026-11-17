package com.team5.voiceorchestrator.contracts;

import java.util.List;

/**
 * Contract 3 response packet sent from Team 5 Orchestrator to Team 4's Unity client.
 *
 * @param sessionId          active child session identifier
 * @param turnId             unique identifier for this conversational turn
 * @param audioBase64        synthesized speech audio encoded as Base64 (PCM or agreed format)
 * @param sampleRateHz       audio sample rate in Hz (e.g., 16000)
 * @param animationTriggerId character animation trigger identifier (e.g., "talk_warm", "idle")
 * @param lipSyncFrames      optional viseme/amplitude frames synchronized to the audio stream
 */
public record UnityResponsePacket(
        String sessionId,
        String turnId,
        String audioBase64,
        int sampleRateHz,
        String animationTriggerId,
        List<LipSyncFrame> lipSyncFrames
) {
    /**
     * Single lip-sync frame aligned to an audio timestamp.
     *
     * @param timestampMs offset from the start of the audio clip in milliseconds
     * @param viseme      viseme or blendshape identifier
     * @param weight      normalized intensity in [0.0, 1.0]
     */
    public record LipSyncFrame(
            long timestampMs,
            String viseme,
            float weight
    ) {
    }
}
