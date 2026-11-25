package com.team5.voiceorchestrator.transport;

import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.UnityControlMessage;
import com.team5.voiceorchestrator.orchestration.ConversationOrchestrator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically sends keep-alive {@code ping} frames on idle Unity/Therapist WebSockets
 * and gracefully closes & cleans up sessions that exceed the idle timeout.
 */
@Component
public class SessionHeartbeatMonitor {

    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 20_000L;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 65_000L;
    public static final long DEFAULT_CHECK_PERIOD_MS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(SessionHeartbeatMonitor.class);

    private final SessionRegistry sessionRegistry;
    private final UnityWebSocketHandler unityWebSocketHandler;
    private final TherapistWebSocketHandler therapistWebSocketHandler;
    private final ConversationOrchestrator conversationOrchestrator;
    private final long heartbeatIntervalMs;
    private final long idleTimeoutMs;
    private final long checkPeriodMs;

    private ScheduledExecutorService scheduler;

    @Autowired
    public SessionHeartbeatMonitor(
            SessionRegistry sessionRegistry,
            UnityWebSocketHandler unityWebSocketHandler,
            TherapistWebSocketHandler therapistWebSocketHandler,
            ConversationOrchestrator conversationOrchestrator) {
        this(
                sessionRegistry,
                unityWebSocketHandler,
                therapistWebSocketHandler,
                conversationOrchestrator,
                DEFAULT_HEARTBEAT_INTERVAL_MS,
                DEFAULT_IDLE_TIMEOUT_MS,
                DEFAULT_CHECK_PERIOD_MS
        );
    }

    SessionHeartbeatMonitor(
            SessionRegistry sessionRegistry,
            UnityWebSocketHandler unityWebSocketHandler,
            TherapistWebSocketHandler therapistWebSocketHandler,
            ConversationOrchestrator conversationOrchestrator,
            long heartbeatIntervalMs,
            long idleTimeoutMs,
            long checkPeriodMs) {
        this.sessionRegistry = sessionRegistry;
        this.unityWebSocketHandler = unityWebSocketHandler;
        this.therapistWebSocketHandler = therapistWebSocketHandler;
        this.conversationOrchestrator = conversationOrchestrator;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.checkPeriodMs = checkPeriodMs;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ws-heartbeat-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        runHeartbeatCycle(Instant.now());
                    } catch (Throwable ex) {
                        log.error("Unexpected error in WebSocket heartbeat monitor cycle: {}",
                                ex.getMessage(), ex);
                    }
                },
                checkPeriodMs,
                checkPeriodMs,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void runHeartbeatCycle(Instant now) {
        for (ActiveSession active : sessionRegistry.getAllSessions()) {
            if (active.isClosed()) {
                continue;
            }
            checkUnityChannel(active, now);
            checkTherapistChannel(active, now);
        }
    }

    private void checkUnityChannel(ActiveSession active, Instant now) {
        WebSocketSession socket = active.getUnitySession();
        if (socket == null) {
            return;
        }
        String sessionId = active.getSessionId();
        Instant lastActivity = active.getUnityLastActivityAt() != null
                ? active.getUnityLastActivityAt()
                : active.getLastActivityAt();
        long idleMs = Duration.between(lastActivity, now).toMillis();

        if (!socket.isOpen() || idleMs >= idleTimeoutMs) {
            log.warn("Unity WebSocket '{}' timed out for session '{}' after {} ms; closing cleanly.",
                    socket.getId(), sessionId, idleMs);
            ActiveSession.closeQuietly(socket,
                    new CloseStatus(CloseStatus.GOING_AWAY.getCode(), "Session heartbeat timeout"));
            if (sessionRegistry.unregisterUnitySession(sessionId, socket)) {
                conversationOrchestrator.onSessionClosed(sessionId);
            }
            return;
        }

        long sinceLastPingMs = Duration.between(active.getUnityLastPingSentAt(), now).toMillis();
        if (idleMs >= heartbeatIntervalMs && sinceLastPingMs >= heartbeatIntervalMs) {
            active.markUnityPingSent(now);
            unityWebSocketHandler.sendControlMessage(
                    sessionId,
                    UnityControlMessage.of(UnityControlMessage.TYPE_PING, sessionId, now.toEpochMilli())
            );
        }
    }

    private void checkTherapistChannel(ActiveSession active, Instant now) {
        WebSocketSession socket = active.getTherapistSession();
        if (socket == null) {
            return;
        }
        String sessionId = active.getSessionId();
        Instant lastActivity = active.getTherapistLastActivityAt() != null
                ? active.getTherapistLastActivityAt()
                : active.getLastActivityAt();
        long idleMs = Duration.between(lastActivity, now).toMillis();

        if (!socket.isOpen() || idleMs >= idleTimeoutMs) {
            log.warn("Therapist WebSocket '{}' timed out for session '{}' after {} ms; closing cleanly.",
                    socket.getId(), sessionId, idleMs);
            ActiveSession.closeQuietly(socket,
                    new CloseStatus(CloseStatus.GOING_AWAY.getCode(), "Session heartbeat timeout"));
            sessionRegistry.unregisterTherapistSession(sessionId, socket);
            return;
        }

        long sinceLastPingMs = Duration.between(active.getTherapistLastPingSentAt(), now).toMillis();
        if (idleMs >= heartbeatIntervalMs && sinceLastPingMs >= heartbeatIntervalMs) {
            active.markTherapistPingSent(now);
            therapistWebSocketHandler.sendControlMessage(
                    sessionId,
                    new TherapistControlMessage(
                            TherapistControlMessage.TYPE_PING,
                            sessionId,
                            null,
                            null,
                            now.toEpochMilli()
                    )
            );
        }
    }
}
