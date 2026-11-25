package com.team5.voiceorchestrator.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract 2 (v1.0.0 - LOCKED) inbound WebSocket control message sent from Team 1's Therapist
 * React UI to Team 5 Orchestrator ({@code /ws/therapist}) during a live session.
 *
 * @param type        control event type ({@code auth}, {@code live_steering}, {@code session_start}, {@code session_end}, {@code ping}, {@code pong})
 * @param sessionId   active child therapy session identifier (non-blank)
 * @param instruction covert therapist steering instruction (required when {@code type == "live_steering"}, omitted otherwise)
 * @param token       JWT token sent in the initial {@code auth} message (omitted otherwise)
 * @param timestampMs client epoch timestamp in milliseconds (>= 0)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TherapistControlMessage(
        String type,
        String sessionId,
        String instruction,
        String token,
        long timestampMs
) {
    public static final String TYPE_AUTH = "auth";
    public static final String TYPE_LIVE_STEERING = "live_steering";
    public static final String TYPE_SESSION_START = "session_start";
    public static final String TYPE_SESSION_END = "session_end";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";

    public static TherapistControlMessage liveSteering(String sessionId, String instruction, long timestampMs) {
        return new TherapistControlMessage(TYPE_LIVE_STEERING, sessionId, instruction, null, timestampMs);
    }

    public static TherapistControlMessage auth(String sessionId, String token, long timestampMs) {
        return new TherapistControlMessage(TYPE_AUTH, sessionId, null, token, timestampMs);
    }
}
