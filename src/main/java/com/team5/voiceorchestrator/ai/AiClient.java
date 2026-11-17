package com.team5.voiceorchestrator.ai;

import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Client abstraction for communicating with Team 3's AI engine (Contract 4).
 * <p>
 * Implemented by Dev C in {@code ai/} ({@code MockAiClient} and {@code RemoteAiClient}).
 */
public interface AiClient {

    /**
     * Sends a child's transcribed turn to the AI engine and returns the structured response.
     *
     * @param request turn payload containing {@code sessionId} and {@code transcript}
     * @return future resolving to the structured AI response
     */
    CompletableFuture<AiTurnResponse> requestTurn(AiTurnRequest request);
}
