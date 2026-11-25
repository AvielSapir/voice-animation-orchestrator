package com.team5.voiceorchestrator.voice.mock;

import com.team5.voiceorchestrator.voice.SttClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock {@link SttClient} implementation active under the {@code mock} Spring profile.
 * <p>
 * Cycles through realistic child-therapy Hebrew utterances so end-to-end
 * orchestration can be exercised without an external STT provider.
 */
@Service
@Profile("mock")
public class MockSttClient implements SttClient {

    private static final List<String> MOCK_UTTERANCES = List.of(
            "קצת עצוב לי היום כי אף אחד לא רצה לשחק איתי בהפסקה",
            "אני שמח שהצלחתי לצייר ציור יפה בבית הספר",
            "לפעמים אני מתרגש לפני שאני מדבר מול הכיתה",
            "היום שיחקתי עם הכלב שלי בגינה והיה ממש כיף"
    );

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public CompletableFuture<String> transcribe(String sessionId, byte[] audioBytes) {
        int index = Math.floorMod(counter.getAndIncrement(), MOCK_UTTERANCES.size());
        return CompletableFuture.completedFuture(MOCK_UTTERANCES.get(index));
    }
}
