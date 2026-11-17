package com.team5.voiceorchestrator.contracts;

/**
 * Contract 4 request payload sent from Team 5 Orchestrator to Team 3's AI engine.
 *
 * @param sessionId  active child session identifier
 * @param transcript transcribed speech text from the child
 */
public record AiTurnRequest(
        String sessionId,
        String transcript
) {
}
