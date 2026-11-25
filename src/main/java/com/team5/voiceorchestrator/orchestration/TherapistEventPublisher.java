package com.team5.voiceorchestrator.orchestration;

import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;

/**
 * Optional publisher hook for forwarding {@link TherapistTranscriptEvent} updates
 * to the Therapist WebSocket transport layer.
 */
@FunctionalInterface
public interface TherapistEventPublisher {

    void publish(TherapistTranscriptEvent event);
}
