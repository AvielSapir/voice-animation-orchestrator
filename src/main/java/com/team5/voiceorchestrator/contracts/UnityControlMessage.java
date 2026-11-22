package com.team5.voiceorchestrator.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract 3 (v1.0.0 - LOCKED) bidirectional WebSocket control message exchanged as a UTF-8
 * JSON text frame between Team 4's Unity client and Team 5 Orchestrator.
 *
 * <p>Text frame disambiguation rule: control messages always contain {@code type}, whereas
 * character turn responses ({@link UnityResponsePacket}) always contain {@code turnId} and
 * {@code audioBase64}.
 *
 * @param type        control message type ({@code session_start}, {@code session_end}, {@code ping}, {@code pong}, {@code barge_in}, {@code error})
 * @param sessionId   active child session identifier
 * @param timestampMs client or server epoch timestamp in milliseconds (>= 0)
 * @param message     optional human-readable status or error detail (omitted when null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnityControlMessage(
        String type,
        String sessionId,
        long timestampMs,
        String message
) {
    public static final String TYPE_SESSION_START = "session_start";
    public static final String TYPE_SESSION_END = "session_end";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_BARGE_IN = "barge_in";
    public static final String TYPE_ERROR = "error";

    /**
     * Creates a control message without an optional {@code message} detail field.
     *
     * @param type        control message type constant
     * @param sessionId   active child session identifier
     * @param timestampMs epoch timestamp in milliseconds
     * @return control message instance with {@code message == null}
     */
    public static UnityControlMessage of(String type, String sessionId, long timestampMs) {
        return new UnityControlMessage(type, sessionId, timestampMs, null);
    }
}
