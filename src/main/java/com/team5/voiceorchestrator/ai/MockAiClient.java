package com.team5.voiceorchestrator.ai;

import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic mock implementation of {@link AiClient} active under the {@code mock} Spring profile.
 *
 * <p>Produces Contract 4-compliant plain spoken Hebrew {@link AiTurnResponse} payloads with standard
 * {@code ttsDirectives} and locked-catalog {@code animationTriggerId} values, and adapts responses
 * when optional {@code liveSteering} instructions are provided.
 */
@Service
@Profile("mock")
public class MockAiClient implements AiClient {

    public static final Set<String> ANIMATION_CATALOG = Set.of(
            "idle",
            "listen",
            "think",
            "wave",
            "talk_warm",
            "talk_happy",
            "talk_curious",
            "talk_calm",
            "clap",
            "hug_gesture"
    );

    @Override
    public CompletableFuture<AiTurnResponse> requestTurn(AiTurnRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String steering = request.liveSteering();
        if (steering != null && !steering.isBlank()) {
            return CompletableFuture.completedFuture(buildSteeredResponse(steering));
        }
        return CompletableFuture.completedFuture(buildRegularResponse(request.transcript()));
    }

    private AiTurnResponse buildRegularResponse(String transcript) {
        String text = transcript == null ? "" : transcript;

        if (containsAny(text, "שלום", "היי", "בוקר טוב", "להתראות")) {
            return new AiTurnResponse(
                    "שלום חבר! איזה כיף לדבר איתך היום. איך אתה מרגיש?",
                    directives("happy", "normal", "normal", "conversational"),
                    "wave"
            );
        }

        if (containsAny(text, "עצוב", "קשה", "לבד", "פחד", "כועס", "בוכה")) {
            return new AiTurnResponse(
                    "אני שומע שזה היה ממש לא פשוט בשבילך. אני כאן איתך, רוצה לספר לי מה הרגשת?",
                    directives("empathetic", "slow", "normal", "soothing"),
                    "talk_warm"
            );
        }

        if (containsAny(text, "שמח", "כיף", "משחק", "צחקתי", "אוהב")) {
            return new AiTurnResponse(
                    "איזה כיף לשמוע את זה! נשמע שהיה לך רגע ממש משמח.",
                    directives("happy", "normal", "high", "playful"),
                    "talk_happy"
            );
        }

        if (containsAny(text, "?", "למה", "איך", "מה זה")) {
            return new AiTurnResponse(
                    "זו שאלה ממש מעניינת. בוא נחשוב על זה ביחד, מה לדעתך יכול לעזור?",
                    directives("curious", "normal", "normal", "conversational"),
                    "talk_curious"
            );
        }

        return new AiTurnResponse(
                "אני מקשיב לך ושמח שאתה משתף אותי. תרצה לספר לי עוד קצת על מה שקרה?",
                directives("warm", "normal", "normal", "conversational"),
                "talk_warm"
        );
    }

    private AiTurnResponse buildSteeredResponse(String liveSteering) {
        String normalized = liveSteering.toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "רגוע", "הרגע", "נשימה", "שקט", "calm", "sooth", "gentle")) {
            return new AiTurnResponse(
                    "אני כאן איתך, בוא ניקח נשימה עמוקה ורגועה ביחד. אפשר לדבר לאט בקצב שנוח לך.",
                    directives("calm", "slow", "low", "soothing"),
                    "talk_calm"
            );
        }

        if (containsAny(normalized, "עודד", "שבח", "כל הכבוד", "מחיאות", "encourage", "praise", "clap")) {
            return new AiTurnResponse(
                    "כל הכבוד לך ששיתפת אותי בזה! עשית צעד אמיץ וממש מגיעה לך מילה טובה.",
                    directives("excited", "normal", "high", "playful"),
                    "clap"
            );
        }

        if (containsAny(normalized, "חיבוק", "נחם", "אמפתיה", "hug", "comfort", "empathetic")) {
            return new AiTurnResponse(
                    "אני שומע כמה זה כבד עכשיו, ואני שולח לך חיבוק חם ונשאר כאן לצדך.",
                    directives("empathetic", "slow", "normal", "soothing"),
                    "hug_gesture"
            );
        }

        if (containsAny(normalized, "שמח", "משחק", "כיף", "happy", "playful", "cheer")) {
            return new AiTurnResponse(
                    "בוא נחשוב על משחק כיפי שאפשר לשחק יחד, מה הכי בא לך לעשות עכשיו?",
                    directives("happy", "normal", "normal", "playful"),
                    "talk_happy"
            );
        }

        if (containsAny(normalized, "שאל", "סקרן", "הפסקה", "חבר", "ask", "curious", "explore")) {
            return new AiTurnResponse(
                    "סקרן אותי לשמוע עוד, מי היה איתך שם ומה הכי עזר לך באותו רגע?",
                    directives("curious", "normal", "normal", "conversational"),
                    "talk_curious"
            );
        }

        return new AiTurnResponse(
                "אני מקשיב לך בתשומת לב, ואשמח שנחשוב על זה יחד צעד אחר צעד.",
                directives("warm", "normal", "normal", "conversational"),
                "talk_warm"
        );
    }

    private static Map<String, String> directives(String emotion, String pace, String pitch, String style) {
        return Map.of(
                "emotion", emotion,
                "pace", pace,
                "pitch", pitch,
                "style", style
        );
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
