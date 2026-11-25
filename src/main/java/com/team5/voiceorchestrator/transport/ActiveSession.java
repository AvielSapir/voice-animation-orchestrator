package com.team5.voiceorchestrator.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Holds thread-safe runtime state for a single child therapy session room,
 * coordinating the Unity child WebSocket, optional Therapist UI WebSocket,
 * bounded FIFO inbound audio buffering, and live steering instructions.
 */
public class ActiveSession {

    public static final int MAX_BUFFERED_CHUNKS = 512;

    private static final Logger log = LoggerFactory.getLogger(ActiveSession.class);

    private final String sessionId;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile WebSocketSession unitySession;
    private final Object unitySendLock = new Object();

    private volatile WebSocketSession therapistSession;
    private final Object therapistSendLock = new Object();
    private volatile boolean therapistAuthenticated;

    private final ConcurrentLinkedQueue<byte[]> inboundAudioBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferedAudioCount = new AtomicInteger(0);
    private final AtomicBoolean drainingAudio = new AtomicBoolean(false);

    private final ConcurrentLinkedQueue<String> liveSteeringQueue = new ConcurrentLinkedQueue<>();

    public ActiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastActivityAt = now;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public WebSocketSession getUnitySession() {
        return unitySession;
    }

    public void setUnitySession(WebSocketSession unitySession) {
        this.unitySession = unitySession;
        touch();
    }

    /**
     * Clears the active Unity socket only if it matches the given socket instance.
     */
    public boolean clearUnitySessionIfMatches(WebSocketSession candidate) {
        if (candidate != null && this.unitySession == candidate) {
            this.unitySession = null;
            return true;
        }
        return false;
    }

    public WebSocketSession getTherapistSession() {
        return therapistSession;
    }

    public void setTherapistSession(WebSocketSession therapistSession) {
        this.therapistSession = therapistSession;
        touch();
    }

    /**
     * Clears the active Therapist socket and resets auth state only if it matches the given socket instance.
     */
    public boolean clearTherapistSessionIfMatches(WebSocketSession candidate) {
        if (candidate != null && this.therapistSession == candidate) {
            this.therapistSession = null;
            this.therapistAuthenticated = false;
            return true;
        }
        return false;
    }

    public boolean isTherapistAuthenticated() {
        return therapistAuthenticated;
    }

    public void setTherapistAuthenticated(boolean therapistAuthenticated) {
        this.therapistAuthenticated = therapistAuthenticated;
    }

    /**
     * Enqueues a defensive copy of an inbound PCM audio chunk into the bounded FIFO buffer.
     * Drops the oldest chunk if {@link #MAX_BUFFERED_CHUNKS} is exceeded.
     */
    public boolean enqueueAudioChunk(byte[] chunk) {
        if (closed.get() || chunk == null || chunk.length == 0) {
            return false;
        }
        inboundAudioBuffer.offer(Arrays.copyOf(chunk, chunk.length));
        int currentSize = bufferedAudioCount.incrementAndGet();
        while (currentSize > MAX_BUFFERED_CHUNKS) {
            byte[] dropped = inboundAudioBuffer.poll();
            if (dropped == null) {
                break;
            }
            currentSize = bufferedAudioCount.decrementAndGet();
        }
        touch();
        return true;
    }

    public int getBufferedAudioCount() {
        return Math.max(0, bufferedAudioCount.get());
    }

    /**
     * Schedules asynchronous single-consumer FIFO draining of buffered audio chunks on the provided executor.
     */
    public void drainAudioAsync(Executor executor, Consumer<byte[]> chunkConsumer) {
        if (closed.get() || inboundAudioBuffer.isEmpty()) {
            return;
        }
        if (!drainingAudio.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    while (!closed.get()) {
                        byte[] chunk = inboundAudioBuffer.poll();
                        if (chunk == null) {
                            break;
                        }
                        bufferedAudioCount.decrementAndGet();
                        chunkConsumer.accept(chunk);
                    }
                } finally {
                    drainingAudio.set(false);
                    if (!closed.get() && !inboundAudioBuffer.isEmpty()) {
                        drainAudioAsync(executor, chunkConsumer);
                    }
                }
            });
        } catch (RuntimeException ex) {
            drainingAudio.set(false);
            throw ex;
        }
    }

    /**
     * Discards any buffered inbound audio chunks (used on child barge-in).
     */
    public void clearAudioBuffer() {
        while (inboundAudioBuffer.poll() != null) {
            bufferedAudioCount.decrementAndGet();
        }
        if (bufferedAudioCount.get() < 0) {
            bufferedAudioCount.set(0);
        }
    }

    public void offerLiveSteering(String instruction) {
        if (!closed.get() && instruction != null && !instruction.isBlank()) {
            liveSteeringQueue.offer(instruction);
            touch();
        }
    }

    public String pollLiveSteering() {
        return liveSteeringQueue.poll();
    }

    public String peekLiveSteering() {
        return liveSteeringQueue.peek();
    }

    /**
     * Sends a UTF-8 JSON text frame to the active Unity socket without throwing on closed connections.
     */
    public boolean sendUnityText(String jsonPayload) {
        return sendText(unitySession, unitySendLock, jsonPayload, "unity");
    }

    /**
     * Sends a UTF-8 JSON text frame to the active Therapist socket without throwing on closed connections.
     */
    public boolean sendTherapistText(String jsonPayload) {
        return sendText(therapistSession, therapistSendLock, jsonPayload, "therapist");
    }

    private boolean sendText(WebSocketSession session, Object lock, String jsonPayload, String channel) {
        if (closed.get() || jsonPayload == null || session == null) {
            return false;
        }
        synchronized (lock) {
            if (closed.get() || !session.isOpen()) {
                return false;
            }
            try {
                session.sendMessage(new TextMessage(jsonPayload));
                touch();
                return true;
            } catch (IOException | IllegalStateException ex) {
                log.debug("Failed sending {} WebSocket frame for session '{}': {}",
                        channel, sessionId, ex.getMessage());
                return false;
            }
        }
    }

    public boolean isEmpty() {
        return unitySession == null && therapistSession == null;
    }

    /**
     * Marks the session closed, clears all queued buffers, and closes any remaining open sockets.
     */
    public void closeAndClear() {
        closed.set(true);
        inboundAudioBuffer.clear();
        bufferedAudioCount.set(0);
        liveSteeringQueue.clear();
        therapistAuthenticated = false;

        WebSocketSession unity = this.unitySession;
        this.unitySession = null;
        WebSocketSession therapist = this.therapistSession;
        this.therapistSession = null;

        closeQuietly(unity, CloseStatus.NORMAL);
        closeQuietly(therapist, CloseStatus.NORMAL);
    }

    public static void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException | IllegalStateException ex) {
            log.debug("Ignoring error while closing WebSocket session '{}': {}",
                    session.getId(), ex.getMessage());
        }
    }
}
