package com.team5.voiceorchestrator.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract 4 (v1.1.0 - LOCKED) request payload sent from Team 5 Orchestrator to Team 3's AI
 * conversation service via {@code POST /api/v1/turn} (UTF-8 JSON).
 *
 * <p>{@code sessionId} binds the turn to Team 3's active session context (character persona,
 * patient background, session opening goals, and conversation history). When the therapist sends
 * a covert instruction via Contract 2 ({@code live_steering}), Team 5 forwards it in
 * {@code liveSteering} on the turn.
 *
 * @param sessionId    active child session identifier (non-blank)
 * @param transcript   transcribed Hebrew speech text from the child (non-blank UTF-8)
 * @param liveSteering optional covert therapist steering instruction forwarded from Contract 2 (omitted when null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiTurnRequest(
        String sessionId,
        String transcript,
        String liveSteering
) {
    public AiTurnRequest(String sessionId, String transcript) {
        this(sessionId, transcript, null);
    }
}
