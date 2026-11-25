package com.team5.voiceorchestrator.orchestration;

import com.team5.voiceorchestrator.contracts.UnityResponsePacket;

/**
 * Optional publisher hook for forwarding completed {@link UnityResponsePacket} responses
 * to the Unity WebSocket transport layer.
 */
@FunctionalInterface
public interface UnityResponsePublisher {

    void publish(UnityResponsePacket packet);
}
