package com.team5.voiceorchestrator.transport;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private static WebSocketSession mockSocket(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }

    @Test
    void registerUnityAndTherapistShareSameRoomAndCleanUpOnDisconnect() {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession unity = mockSocket("u-1", true);
        WebSocketSession therapist = mockSocket("t-1", true);

        ActiveSession roomFromUnity = registry.registerUnitySession("s-1", unity);
        ActiveSession roomFromTherapist = registry.registerTherapistSession("s-1", therapist);

        assertThat(roomFromTherapist).isSameAs(roomFromUnity);
        assertThat(registry.activeSessionCount()).isEqualTo(1);
        assertThat(roomFromUnity.getUnitySession()).isSameAs(unity);
        assertThat(roomFromUnity.getTherapistSession()).isSameAs(therapist);

        assertThat(registry.unregisterUnitySession("s-1", unity)).isTrue();
        assertThat(registry.activeSessionCount()).isEqualTo(1);
        assertThat(registry.getSession("s-1")).isPresent();

        assertThat(registry.unregisterTherapistSession("s-1", therapist)).isTrue();
        assertThat(registry.activeSessionCount()).isZero();
        assertThat(registry.getSession("s-1")).isEmpty();
        assertThat(roomFromUnity.isClosed()).isTrue();
    }

    @Test
    void duplicateUnityConnectionSupersedesOldSocketWithNormalClosureAndIgnoresStaleUnregister() throws IOException {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession oldSocket = mockSocket("u-old", true);
        WebSocketSession newSocket = mockSocket("u-new", true);

        ActiveSession first = registry.registerUnitySession("s-dup", oldSocket);
        ActiveSession second = registry.registerUnitySession("s-dup", newSocket);

        assertThat(second).isSameAs(first);
        verify(oldSocket).close(CloseStatus.NORMAL);
        verify(newSocket, never()).close(any());

        // Stale disconnect from oldSocket must not remove newSocket
        assertThat(registry.unregisterUnitySession("s-dup", oldSocket)).isFalse();
        assertThat(registry.getSession("s-dup")).isPresent();
        assertThat(first.getUnitySession()).isSameAs(newSocket);

        // Disconnecting newSocket cleans up the room
        assertThat(registry.unregisterUnitySession("s-dup", newSocket)).isTrue();
        assertThat(registry.getSession("s-dup")).isEmpty();
    }

    @Test
    void duplicateTherapistConnectionSupersedesOldSocketAndResetsAuth() throws IOException {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession oldTherapist = mockSocket("t-old", true);
        WebSocketSession newTherapist = mockSocket("t-new", true);

        ActiveSession session = registry.registerTherapistSession("s-th", oldTherapist);
        session.setTherapistAuthenticated(true);

        registry.registerTherapistSession("s-th", newTherapist);

        verify(oldTherapist).close(CloseStatus.NORMAL);
        assertThat(session.isTherapistAuthenticated()).isFalse();
        assertThat(session.getTherapistSession()).isSameAs(newTherapist);
        assertThat(registry.unregisterTherapistSession("s-th", oldTherapist)).isFalse();
        assertThat(registry.unregisterTherapistSession("s-th", newTherapist)).isTrue();
        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    void activeSessionEnforcesBoundedAudioBufferAndDrainsFifoAsynchronously() throws InterruptedException {
        ActiveSession session = new ActiveSession("s-audio");
        int totalChunks = ActiveSession.MAX_BUFFERED_CHUNKS + 25;
        for (int i = 0; i < totalChunks; i++) {
            session.enqueueAudioChunk(new byte[]{(byte) (i >> 8), (byte) i});
        }

        assertThat(session.getBufferedAudioCount()).isEqualTo(ActiveSession.MAX_BUFFERED_CHUNKS);

        List<Integer> receivedOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(ActiveSession.MAX_BUFFERED_CHUNKS);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            session.drainAudioAsync(executor, chunk -> {
                int value = ((chunk[0] & 0xFF) << 8) | (chunk[1] & 0xFF);
                receivedOrder.add(value);
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(receivedOrder).hasSize(ActiveSession.MAX_BUFFERED_CHUNKS);
        assertThat(receivedOrder.get(0)).isEqualTo(25);
        assertThat(receivedOrder.get(receivedOrder.size() - 1)).isEqualTo(totalChunks - 1);
        assertThat(session.getBufferedAudioCount()).isZero();
    }

    @Test
    void concurrentRegistrationsAndCleanupsRemainThreadSafe() throws InterruptedException {
        SessionRegistry registry = new SessionRegistry();
        int threads = 16;
        int sessionsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int workerId = t;
            pool.execute(() -> {
                try {
                    for (int i = 0; i < sessionsPerThread; i++) {
                        String sid = "session-" + workerId + "-" + i;
                        WebSocketSession u = mockSocket("u-" + sid, true);
                        WebSocketSession th = mockSocket("th-" + sid, true);
                        registry.registerUnitySession(sid, u);
                        registry.registerTherapistSession(sid, th);
                        registry.unregisterUnitySession(sid, u);
                        registry.unregisterTherapistSession(sid, th);
                    }
                } catch (Throwable ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(failures.get()).isZero();
        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    void rejectsBlankSessionIdOrNullSocket() {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession socket = mockSocket("u-1", true);

        assertThatThrownBy(() -> registry.registerUnitySession("  ", socket))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.registerUnitySession("s-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
