package com.team5.voiceorchestrator.contracts;

import java.util.Map;

/**
 * Contract 4 structured response payload returned by Team 3's AI engine.
 *
 * @param replyText          textual response to be synthesized into speech
 * @param ttsDirectives      tone/style directives for TTS (e.g., emotion, pace, pitch)
 * @param animationTriggerId character animation trigger identifier for Unity
 */
public record AiTurnResponse(
        String replyText,
        Map<String, String> ttsDirectives,
        String animationTriggerId
) {
}
