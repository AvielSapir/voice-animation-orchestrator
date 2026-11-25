package com.team5.voiceorchestrator.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract 2 (v1.0.0 - LOCKED) outbound WebSocket event pushed from Team 5 Orchestrator to
 * Team 1's Therapist React UI ({@code /ws/therapist}) whenever a new child utterance or
 * character reply is produced during an active session.
 *
 * @param sessionId          active child therapy session identifier (non-blank)
 * @param turnId             unique turn identifier correlating with the audio/animation turn (nullable for system events)
 * @param speaker            speaker role: {@code CHILD}, {@code CHARACTER}, or {@code SYSTEM}
 * @param text               transcribed child speech or character reply text (UTF-8 Hebrew)
 * @param animationTriggerId animation trigger chosen for {@code CHARACTER} turns (omitted when null)
 * @param timestampMs        server epoch timestamp in milliseconds (>= 0)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TherapistTranscriptEvent(
        String sessionId,
        String turnId,
        String speaker,
        String text,
        String animationTriggerId,
        long timestampMs
) {
    public static final String SPEAKER_CHILD = "CHILD";
    public static final String SPEAKER_CHARACTER = "CHARACTER";
    public static final String SPEAKER_SYSTEM = "SYSTEM";

    public static TherapistTranscriptEvent childUtterance(
            String sessionId, String turnId, String text, long timestampMs) {
        return new TherapistTranscriptEvent(sessionId, turnId, SPEAKER_CHILD, text, null, timestampMs);
    }

    public static TherapistTranscriptEvent characterReply(
            String sessionId, String turnId, String text, String animationTriggerId, long timestampMs) {
        return new TherapistTranscriptEvent(sessionId, turnId, SPEAKER_CHARACTER, text, animationTriggerId, timestampMs);
    }
}
