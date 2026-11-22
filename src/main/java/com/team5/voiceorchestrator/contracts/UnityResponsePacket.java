package com.team5.voiceorchestrator.contracts;

import java.util.List;

/**
 * Contract 3 (v1.0.0 - LOCKED) response packet sent from Team 5 Orchestrator to Team 4's Unity client
 * as a UTF-8 WebSocket text JSON frame.
 *
 * <p>Audio wire rule: {@code audioBase64} decodes to raw {@code PCM_S16LE} (16-bit signed little-endian,
 * mono, at {@code sampleRateHz} — default {@code 16000} Hz) with no WAV/RIFF header.
 * {@code animationTriggerId} uses lowercase {@code snake_case} identifiers from the locked animation
 * catalog (safe fallback {@code "talk_warm"} / {@code "idle"}). {@code lipSyncFrames} is always
 * non-null and may be empty ({@code []}) when Unity uses audio-RMS mouth fallback.
 *
 * @param sessionId          active child session identifier
 * @param turnId             unique identifier for this conversational turn
 * @param audioBase64        synthesized speech audio encoded as Base64 raw PCM_S16LE (no WAV header)
 * @param sampleRateHz       audio sample rate in Hz (locked default: 16000)
 * @param animationTriggerId character animation trigger identifier (e.g., "talk_warm", "idle")
 * @param lipSyncFrames      optional viseme/amplitude frames synchronized to the audio stream; never null
 */
public record UnityResponsePacket(
        String sessionId,
        String turnId,
        String audioBase64,
        int sampleRateHz,
        String animationTriggerId,
        List<LipSyncFrame> lipSyncFrames
) {
    public UnityResponsePacket {
        lipSyncFrames = lipSyncFrames == null ? List.of() : List.copyOf(lipSyncFrames);
    }

    /**
     * Single lip-sync frame aligned to an audio timestamp.
     *
     * @param timestampMs offset from the start of the audio clip in milliseconds (>= 0)
     * @param viseme      viseme identifier from the locked catalog (e.g., "sil", "aa", "ee", "mbp")
     * @param weight      normalized intensity in [0.0, 1.0]
     */
    public record LipSyncFrame(
            long timestampMs,
            String viseme,
            float weight
    ) {
    }
}
