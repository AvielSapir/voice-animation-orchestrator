package com.team5.voiceorchestrator.contracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contract 4 (v1.0.0 - LOCKED) structured response payload returned by Team 3's AI engine.
 *
 * <p>Formatting rules:
 * <ul>
 *   <li>{@code replyText} must be plain spoken Hebrew text ready for TTS (no markdown, bullets,
 *       asterisks, emojis, or stage directions).</li>
 *   <li>{@code ttsDirectives} is a flat {@code Map<String, String>} using standard keys
 *       ({@code emotion}, {@code pace}, {@code pitch}, {@code style}); null maps or null entries
 *       are safely normalized to empty/filtered immutable maps.</li>
 *   <li>{@code animationTriggerId} must be a lowercase {@code snake_case} trigger from the shared
 *       animation catalog (fallback {@code "talk_warm"}).</li>
 * </ul>
 *
 * @param replyText          plain spoken textual response to be synthesized into speech
 * @param ttsDirectives      flat tone/style directives for TTS (e.g., emotion, pace, pitch, style); never null
 * @param animationTriggerId character animation trigger identifier for Unity
 */
public record AiTurnResponse(
        String replyText,
        Map<String, String> ttsDirectives,
        String animationTriggerId
) {
    public AiTurnResponse {
        if (ttsDirectives == null || ttsDirectives.isEmpty()) {
            ttsDirectives = Map.of();
        } else {
            Map<String, String> cleaned = new LinkedHashMap<>();
            ttsDirectives.forEach((k, v) -> {
                if (k != null && v != null) {
                    cleaned.put(k, v);
                }
            });
            ttsDirectives = Collections.unmodifiableMap(cleaned);
        }
    }
}
