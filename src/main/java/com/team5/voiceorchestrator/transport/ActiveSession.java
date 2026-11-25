package com.team5.voiceorchestrator.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Holds thread-safe runtime state for a single child therapy session room,
 * coordinating the Unity child WebSocket, optional Therapist UI WebSocket,
 * PCM chunk normalization, bounded FIFO inbound/outbound buffering,
 * per-channel heartbeat tracking, and live steering instructions.
 */
public class ActiveSession {

    public static final int MAX_BUFFERED_CHUNKS = 512;
    public static final int MAX_OUTBOUND_MESSAGES = 256;
    private static final int MAX_TRACKED_TURN_IDS = 64;

    private static final Logger log = LoggerFactory.getLogger(ActiveSession.class);

    private final String sessionId;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile WebSocketSession unitySession;
    private volatile Instant unityLastActivityAt;
    private volatile Instant unityLastPingSentAt = Instant.EPOCH;
    private final OutboundChannel unityOutbound = new OutboundChannel("unity");

    private volatile WebSocketSession therapistSession;
    private volatile Instant therapistLastActivityAt;
    private volatile Instant therapistLastPingSentAt = Instant.EPOCH;
    private volatile boolean therapistAuthenticated;
    private final OutboundChannel therapistOutbound = new OutboundChannel("therapist");

    private final ConcurrentLinkedQueue<byte[]> inboundAudioBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferedAudioCount = new AtomicInteger(0);
    private final AtomicBoolean drainingAudio = new AtomicBoolean(false);
    private final ByteArrayOutputStream partialPcmBuffer = new ByteArrayOutputStream();

    private final ConcurrentLinkedQueue<String> liveSteeringQueue = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<String> dispatchedTurnIds = new LinkedHashSet<>();

    public ActiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastActivityAt = now;
        this.unityLastActivityAt = now;
        this.therapistLastActivityAt = now;
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

    public Instant getUnityLastActivityAt() {
        return unityLastActivityAt;
    }

    public Instant getUnityLastPingSentAt() {
        return unityLastPingSentAt;
    }

    public void touchUnity() {
        touchUnity(Instant.now());
    }

    void touchUnity(Instant at) {
        Instant timestamp = at != null ? at : Instant.now();
        this.unityLastActivityAt = timestamp;
        this.lastActivityAt = timestamp;
    }

    public void markUnityPingSent(Instant at) {
        this.unityLastPingSentAt = at != null ? at : Instant.now();
    }

    public Instant getTherapistLastActivityAt() {
        return therapistLastActivityAt;
    }

    public Instant getTherapistLastPingSentAt() {
        return therapistLastPingSentAt;
    }

    public void touchTherapist() {
        touchTherapist(Instant.now());
    }

    void touchTherapist(Instant at) {
        Instant timestamp = at != null ? at : Instant.now();
        this.therapistLastActivityAt = timestamp;
        this.lastActivityAt = timestamp;
    }

    public void markTherapistPingSent(Instant at) {
        this.therapistLastPingSentAt = at != null ? at : Instant.now();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public WebSocketSession getUnitySession() {
        return unitySession;
    }

    public void setUnitySession(WebSocketSession unitySession) {
        this.unitySession = unitySession;
        Instant now = Instant.now();
        this.unityLastActivityAt = now;
        this.unityLastPingSentAt = Instant.EPOCH;
        this.lastActivityAt = now;
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
        Instant now = Instant.now();
        this.therapistLastActivityAt = now;
        this.therapistLastPingSentAt = Instant.EPOCH;
        this.lastActivityAt = now;
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
     * Normalizes an inbound PCM byte frame into exact {@code targetChunkBytes}-sized chunks,
     * buffering any trailing partial bytes per session until the next frame arrives.
     */
    public List<byte[]> normalizePcmFrame(byte[] incomingPcm, int targetChunkBytes) {
        if (closed.get() || incomingPcm == null || incomingPcm.length == 0) {
            return List.of();
        }
        if (targetChunkBytes <= 0) {
            return List.of(Arrays.copyOf(incomingPcm, incomingPcm.length));
        }

        synchronized (partialPcmBuffer) {
            if (partialPcmBuffer.size() == 0 && incomingPcm.length == targetChunkBytes) {
                return List.of(Arrays.copyOf(incomingPcm, incomingPcm.length));
            }
            partialPcmBuffer.writeBytes(incomingPcm);
            byte[] all = partialPcmBuffer.toByteArray();
            if (all.length < targetChunkBytes) {
                return List.of();
            }

            int chunkCount = all.length / targetChunkBytes;
            List<byte[]> chunks = new ArrayList<>(chunkCount);
            int offset = 0;
            for (int i = 0; i < chunkCount; i++) {
                chunks.add(Arrays.copyOfRange(all, offset, offset + targetChunkBytes));
                offset += targetChunkBytes;
            }
            partialPcmBuffer.reset();
            if (offset < all.length) {
                partialPcmBuffer.write(all, offset, all.length - offset);
            }
            return List.copyOf(chunks);
        }
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
        touchUnity();
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
     * Discards any buffered inbound audio chunks and partial PCM bytes (used on child barge-in).
     */
    public void clearAudioBuffer() {
        synchronized (partialPcmBuffer) {
            partialPcmBuffer.reset();
        }
        while (inboundAudioBuffer.poll() != null) {
            bufferedAudioCount.decrementAndGet();
        }
        if (bufferedAudioCount.get() < 0) {
            bufferedAudioCount.set(0);
        }
    }

    /**
     * Claims {@code turnId} so duplicate outbound packets for the same turn are suppressed.
     */
    public boolean tryClaimTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return true;
        }
        synchronized (dispatchedTurnIds) {
            if (dispatchedTurnIds.contains(turnId)) {
                return false;
            }
            dispatchedTurnIds.add(turnId);
            if (dispatchedTurnIds.size() > MAX_TRACKED_TURN_IDS) {
                Iterator<String> iterator = dispatchedTurnIds.iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            return true;
        }
    }

    public void offerLiveSteering(String instruction) {
        if (!closed.get() && instruction != null && !instruction.isBlank()) {
            liveSteeringQueue.offer(instruction);
            touchTherapist();
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
        return unityOutbound.sendSync(unitySession, jsonPayload, closed, this::touchUnity, sessionId);
    }

    /**
     * Enqueues a UTF-8 JSON text frame for ordered asynchronous delivery to the active Unity socket.
     */
    public CompletableFuture<Boolean> enqueueUnityTextAsync(String jsonPayload, Executor executor) {
        return unityOutbound.enqueueAsync(
                () -> unitySession, jsonPayload, executor, closed, this::touchUnity, sessionId);
    }

    /**
     * Sends a UTF-8 JSON text frame to the active Therapist socket without throwing on closed connections.
     */
    public boolean sendTherapistText(String jsonPayload) {
        return therapistOutbound.sendSync(therapistSession, jsonPayload, closed, this::touchTherapist, sessionId);
    }

    /**
     * Enqueues a UTF-8 JSON text frame for ordered asynchronous delivery to the active Therapist socket.
     */
    public CompletableFuture<Boolean> enqueueTherapistTextAsync(String jsonPayload, Executor executor) {
        return therapistOutbound.enqueueAsync(
                () -> therapistSession, jsonPayload, executor, closed, this::touchTherapist, sessionId);
    }

    public boolean isEmpty() {
        return unitySession == null && therapistSession == null;
    }

    /**
     * Marks the session closed, clears all queued buffers, and closes any remaining open sockets.
     */
    public void closeAndClear() {
        closed.set(true);
        synchronized (partialPcmBuffer) {
            partialPcmBuffer.reset();
        }
        inboundAudioBuffer.clear();
        bufferedAudioCount.set(0);
        liveSteeringQueue.clear();
        synchronized (dispatchedTurnIds) {
            dispatchedTurnIds.clear();
        }
        therapistAuthenticated = false;

        unityOutbound.clearAndFailAll();
        therapistOutbound.clearAndFailAll();

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

    private record OutboundTextTask(String payload, CompletableFuture<Boolean> future) {
    }

    private static final class OutboundChannel {
        private final String channelName;
        private final Object sendLock = new Object();
        private final ConcurrentLinkedQueue<OutboundTextTask> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queueSize = new AtomicInteger(0);
        private final AtomicBoolean draining = new AtomicBoolean(false);

        private OutboundChannel(String channelName) {
            this.channelName = channelName;
        }

        private CompletableFuture<Boolean> enqueueAsync(
                java.util.function.Supplier<WebSocketSession> sessionSupplier,
                String jsonPayload,
                Executor executor,
                AtomicBoolean closed,
                Runnable onSent,
                String sessionId) {
            WebSocketSession session = sessionSupplier.get();
            if (closed.get() || jsonPayload == null || session == null || !session.isOpen()) {
                return CompletableFuture.completedFuture(false);
            }

            CompletableFuture<Boolean> future = new CompletableFuture<>();
            queue.offer(new OutboundTextTask(jsonPayload, future));
            int size = queueSize.incrementAndGet();
            while (size > MAX_OUTBOUND_MESSAGES) {
                OutboundTextTask dropped = queue.poll();
                if (dropped == null) {
                    break;
                }
                size = queueSize.decrementAndGet();
                dropped.future().complete(false);
            }

            drainAsync(sessionSupplier, executor, closed, onSent, sessionId);
            return future;
        }

        private void drainAsync(
                java.util.function.Supplier<WebSocketSession> sessionSupplier,
                Executor executor,
                AtomicBoolean closed,
                Runnable onSent,
                String sessionId) {
            if (closed.get() || queue.isEmpty()) {
                return;
            }
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        while (!closed.get()) {
                            OutboundTextTask task = queue.poll();
                            if (task == null) {
                                break;
                            }
                            queueSize.decrementAndGet();
                            boolean sent = sendSync(sessionSupplier.get(), task.payload(), closed, onSent, sessionId);
                            task.future().complete(sent);
                        }
                    } finally {
                        draining.set(false);
                        if (!closed.get() && !queue.isEmpty()) {
                            drainAsync(sessionSupplier, executor, closed, onSent, sessionId);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                draining.set(false);
                clearAndFailAll();
                throw ex;
            }
        }

        private boolean sendSync(
                WebSocketSession session,
                String jsonPayload,
                AtomicBoolean closed,
                Runnable onSent,
                String sessionId) {
            if (closed.get() || jsonPayload == null || session == null) {
                return false;
            }
            synchronized (sendLock) {
                if (closed.get() || !session.isOpen()) {
                    return false;
                }
                try {
                    session.sendMessage(new TextMessage(jsonPayload));
                    onSent.run();
                    return true;
                } catch (IOException | IllegalStateException ex) {
                    log.debug("Failed sending {} WebSocket frame for session '{}': {}",
                            channelName, sessionId, ex.getMessage());
                    return false;
                }
            }
        }

        private void clearAndFailAll() {
            OutboundTextTask task;
            while ((task = queue.poll()) != null) {
                queueSize.decrementAndGet();
                task.future().complete(false);
            }
            if (queueSize.get() < 0) {
                queueSize.set(0);
            }
        }
    }
}
