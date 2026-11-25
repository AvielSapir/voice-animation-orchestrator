package com.team5.voiceorchestrator.transport;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory registry of active session rooms mapped by {@code sessionId}.
 * Coordinates Unity (Contract 3) and Therapist (Contract 2) sockets while preventing
 * duplicate connections and guaranteeing full resource cleanup on disconnect.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final ConcurrentHashMap<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers a Unity WebSocket for {@code sessionId}, cleanly superseding any previous Unity socket.
     */
    public ActiveSession registerUnitySession(String sessionId, WebSocketSession socket) {
        validateRegistrationArgs(sessionId, socket);
        AtomicReference<WebSocketSession> superseded = new AtomicReference<>();

        ActiveSession active = sessions.compute(sessionId, (id, existing) -> {
            ActiveSession session = (existing == null || existing.isClosed()) ? new ActiveSession(id) : existing;
            WebSocketSession previous = session.getUnitySession();
            if (previous != null && previous != socket) {
                superseded.set(previous);
            }
            session.setUnitySession(socket);
            return session;
        });

        WebSocketSession oldSocket = superseded.get();
        if (oldSocket != null) {
            log.info("Superseding previous Unity WebSocket '{}' for session '{}'.", oldSocket.getId(), sessionId);
            ActiveSession.closeQuietly(oldSocket, CloseStatus.NORMAL);
        }
        return active;
    }

    /**
     * Registers a Therapist WebSocket for {@code sessionId}, cleanly superseding any previous Therapist socket.
     */
    public ActiveSession registerTherapistSession(String sessionId, WebSocketSession socket) {
        validateRegistrationArgs(sessionId, socket);
        AtomicReference<WebSocketSession> superseded = new AtomicReference<>();

        ActiveSession active = sessions.compute(sessionId, (id, existing) -> {
            ActiveSession session = (existing == null || existing.isClosed()) ? new ActiveSession(id) : existing;
            WebSocketSession previous = session.getTherapistSession();
            if (previous != null && previous != socket) {
                superseded.set(previous);
                session.setTherapistAuthenticated(false);
            } else if (previous == null) {
                session.setTherapistAuthenticated(false);
            }
            session.setTherapistSession(socket);
            return session;
        });

        WebSocketSession oldSocket = superseded.get();
        if (oldSocket != null) {
            log.info("Superseding previous Therapist WebSocket '{}' for session '{}'.", oldSocket.getId(), sessionId);
            ActiveSession.closeQuietly(oldSocket, CloseStatus.NORMAL);
        }
        return active;
    }

    /**
     * Unregisters a Unity socket only if it is still the active Unity socket for {@code sessionId}.
     * Removes and cleans up the room if no sockets remain attached.
     *
     * @return {@code true} if this socket was the active Unity connection for the session
     */
    public boolean unregisterUnitySession(String sessionId, WebSocketSession socket) {
        if (sessionId == null || sessionId.isBlank() || socket == null) {
            return false;
        }
        AtomicBoolean removedActive = new AtomicBoolean(false);
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.clearUnitySessionIfMatches(socket)) {
                removedActive.set(true);
                if (session.isEmpty()) {
                    session.closeAndClear();
                    return null;
                }
            }
            return session;
        });
        return removedActive.get();
    }

    /**
     * Unregisters a Therapist socket only if it is still the active Therapist socket for {@code sessionId}.
     * Removes and cleans up the room if no sockets remain attached.
     *
     * @return {@code true} if this socket was the active Therapist connection for the session
     */
    public boolean unregisterTherapistSession(String sessionId, WebSocketSession socket) {
        if (sessionId == null || sessionId.isBlank() || socket == null) {
            return false;
        }
        AtomicBoolean removedActive = new AtomicBoolean(false);
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.clearTherapistSessionIfMatches(socket)) {
                removedActive.set(true);
                if (session.isEmpty()) {
                    session.closeAndClear();
                    return null;
                }
            }
            return session;
        });
        return removedActive.get();
    }

    public Optional<ActiveSession> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        ActiveSession session = sessions.get(sessionId);
        return (session != null && !session.isClosed()) ? Optional.of(session) : Optional.empty();
    }

    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ActiveSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.closeAndClear();
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public List<ActiveSession> getAllSessions() {
        return List.copyOf(sessions.values());
    }

    @PreDestroy
    public void shutdown() {
        List<ActiveSession> snapshot = List.copyOf(sessions.values());
        sessions.clear();
        for (ActiveSession session : snapshot) {
            session.closeAndClear();
        }
    }

    private static void validateRegistrationArgs(String sessionId, WebSocketSession socket) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
    }
}
