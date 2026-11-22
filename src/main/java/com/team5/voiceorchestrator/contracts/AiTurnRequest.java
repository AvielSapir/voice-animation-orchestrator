package com.team5.voiceorchestrator.contracts;

/**
 * Contract 4 (v1.0.0 - LOCKED) request payload sent from Team 5 Orchestrator to Team 3's AI
 * conversation service via {@code POST /api/v1/turn} (UTF-8 JSON).
 *
 * <p>{@code sessionId} binds the turn to Team 3's active session context (character persona,
 * patient background, session opening goals, conversation history, and therapist Live Steering).
 *
 * @param sessionId  active child session identifier (non-blank)
 * @param transcript transcribed Hebrew speech text from the child (non-blank UTF-8)
 */
public record AiTurnRequest(
        String sessionId,
        String transcript
) {
}
