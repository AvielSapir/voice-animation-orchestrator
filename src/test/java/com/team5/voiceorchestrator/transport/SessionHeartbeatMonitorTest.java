package com.team5.voiceorchestrator.transport;

import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionHeartbeatMonitorTest {

    private SessionRegistry sessionRegistry;
    private UnityWebSocketHandler unityHandler;
    private TherapistWebSocketHandler therapistHandler;
    private ConversationOrchestrator orchestrator;
    private SessionHeartbeatMonitor monitor;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistry();
        unityHandler = mock(UnityWebSocketHandler.class);
        therapistHandler = mock(TherapistWebSocketHandler.class);
        orchestrator = mock(ConversationOrchestrator.class);

        when(unityHandler.sendControlMessage(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(therapistHandler.sendControlMessage(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        monitor = new SessionHeartbeatMonitor(
                sessionRegistry,
                unityHandler,
                therapistHandler,
                orchestrator,
                20_000L,
                60_000L,
                5_000L
        );
    }

    @AfterEach
    void tearDown() {
        sessionRegistry.shutdown();
    }

    private static WebSocketSession mockSocket(String id, boolean open) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(open);
        return socket;
    }

    @Test
    void sendsPingOnIdleUnityAndTherapistSocketsWithoutSpamming() {
        WebSocketSession unity = mockSocket("u-1", true);
        WebSocketSession therapist = mockSocket("t-1", true);
        ActiveSession session = sessionRegistry.registerUnitySession("s-hb", unity);
        sessionRegistry.registerTherapistSession("s-hb", therapist);

        Instant base = session.getUnityLastActivityAt();
        monitor.runHeartbeatCycle(base.plusMillis(10_000));
        verify(unityHandler, never()).sendControlMessage(any(), any());
        verify(therapistHandler, never()).sendControlMessage(any(), any());

        Instant firstPingAt = base.plusMillis(25_000);
        monitor.runHeartbeatCycle(firstPingAt);
        verify(unityHandler, times(1)).sendControlMessage(eq("s-hb"), any(UnityControlMessage.class));
        verify(therapistHandler, times(1)).sendControlMessage(eq("s-hb"), any(TherapistControlMessage.class));

        // Second check 5s later must not spam another ping before heartbeatIntervalMs elapses
        monitor.runHeartbeatCycle(firstPingAt.plusMillis(5_000));
        verify(unityHandler, times(1)).sendControlMessage(eq("s-hb"), any(UnityControlMessage.class));
        verify(therapistHandler, times(1)).sendControlMessage(eq("s-hb"), any(TherapistControlMessage.class));

        // Next interval triggers a second ping
        monitor.runHeartbeatCycle(firstPingAt.plusMillis(21_000));
        verify(unityHandler, times(2)).sendControlMessage(eq("s-hb"), any(UnityControlMessage.class));
        verify(therapistHandler, times(2)).sendControlMessage(eq("s-hb"), any(TherapistControlMessage.class));
    }

    @Test
    void closesAndCleansUpTimedOutUnityWhileKeepingActiveTherapistOrViceVersa() throws Exception {
        WebSocketSession unity = mockSocket("u-idle", true);
        WebSocketSession therapist = mockSocket("t-active", true);
        ActiveSession session = sessionRegistry.registerUnitySession("s-timeout", unity);
        sessionRegistry.registerTherapistSession("s-timeout", therapist);

        Instant futureCheck = session.getUnityLastActivityAt().plusMillis(65_000);
        // Simulate therapist just sent a pong 5 seconds before check
        session.touchTherapist(futureCheck.minusMillis(5_000));

        monitor.runHeartbeatCycle(futureCheck);

        ArgumentCaptor<CloseStatus> unityClose = ArgumentCaptor.forClass(CloseStatus.class);
        verify(unity).close(unityClose.capture());
        assertThat(unityClose.getValue().getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        verify(orchestrator).onSessionClosed("s-timeout");

        // Therapist stays alive in the room
        verify(therapist, never()).close(any());
        assertThat(sessionRegistry.getSession("s-timeout")).isPresent();

        // Once therapist also exceeds idleTimeoutMs, room is completely cleaned up
        Instant laterCheck = session.getTherapistLastActivityAt().plusMillis(65_000);
        monitor.runHeartbeatCycle(laterCheck);
        verify(therapist).close(any(CloseStatus.class));
        assertThat(sessionRegistry.getSession("s-timeout")).isEmpty();
    }
}
