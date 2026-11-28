package com.team5.voiceorchestrator.ai;

/**
 * Thrown when Team 3's AI engine responds with HTTP 410 Gone, indicating the therapy
 * session has already been closed and Team 5 should stop sending turns for it.
 */
public class AiSessionClosedException extends RuntimeException {

    private final String sessionId;

    public AiSessionClosedException(String sessionId) {
        super("AI session already closed: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
