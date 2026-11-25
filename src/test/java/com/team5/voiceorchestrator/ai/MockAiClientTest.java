package com.team5.voiceorchestrator.ai;

import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiClientTest {

    private static final Set<String> LOCKED_ANIMATION_CATALOG = Set.of(
            "idle", "listen", "think", "wave", "talk_warm",
            "talk_happy", "talk_curious", "talk_calm", "clap", "hug_gesture"
    );

    private static final Set<String> ALLOWED_EMOTIONS = Set.of(
            "warm", "empathetic", "happy", "excited", "curious", "calm", "gentle", "hesitant"
    );
    private static final Set<String> ALLOWED_PACES = Set.of("slow", "normal", "fast");
    private static final Set<String> ALLOWED_PITCHES = Set.of("low", "normal", "high");
    private static final Set<String> ALLOWED_STYLES = Set.of("conversational", "whisper", "playful", "soothing");

    private static final Pattern HEBREW_CHAR = Pattern.compile("\\p{InHebrew}");

    private final MockAiClient client = new MockAiClient();

    @Test
    void annotatedAsMockProfileService() {
        assertThat(MockAiClient.class.getAnnotation(Service.class)).isNotNull();
        Profile profile = MockAiClient.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("mock");
    }

    @Test
    void regularTurnWithoutSteeringReturnsValidPlainHebrewResponse() throws Exception {
        AiTurnRequest request = new AiTurnRequest(
                "session-child-402",
                "היום היה לי קצת קשה בהפסקה כי אף אחד לא רצה לשחק איתי"
        );

        AiTurnResponse response = client.requestTurn(request).get();

        assertValidContract4Response(response);
        assertThat(response.animationTriggerId()).isEqualTo("talk_warm");
        assertThat(response.ttsDirectives().get("emotion")).isEqualTo("empathetic");
    }

    @Test
    void regularTurnWithBlankSteeringBehavesLikeUnsteeredTurn() throws Exception {
        AiTurnRequest unsteered = new AiTurnRequest("session-1", "שלום");
        AiTurnRequest blankSteered = new AiTurnRequest("session-1", "שלום", "   ");

        AiTurnResponse unsteeredResponse = client.requestTurn(unsteered).get();
        AiTurnResponse blankResponse = client.requestTurn(blankSteered).get();

        assertValidContract4Response(unsteeredResponse);
        assertThat(blankResponse).isEqualTo(unsteeredResponse);
        assertThat(unsteeredResponse.animationTriggerId()).isEqualTo("wave");
    }

    @Test
    void liveSteeringAdaptsResponseAndRemainsValidPerContract4() throws Exception {
        String transcript = "אני לא יודע מה לעשות עכשיו";
        AiTurnResponse baseline = client.requestTurn(new AiTurnRequest("session-402", transcript)).get();

        AiTurnResponse calmSteered = client.requestTurn(new AiTurnRequest(
                "session-402",
                transcript,
                "דבר בטון רגוע ועזור לו לקחת נשימה"
        )).get();
        assertValidContract4Response(calmSteered);
        assertThat(calmSteered).isNotEqualTo(baseline);
        assertThat(calmSteered.animationTriggerId()).isEqualTo("talk_calm");
        assertThat(calmSteered.ttsDirectives())
                .containsEntry("emotion", "calm")
                .containsEntry("pace", "slow")
                .containsEntry("style", "soothing");

        AiTurnResponse clapSteered = client.requestTurn(new AiTurnRequest(
                "session-402",
                transcript,
                "עודד אותו ושבח אותו על השיתוף"
        )).get();
        assertValidContract4Response(clapSteered);
        assertThat(clapSteered).isNotEqualTo(baseline);
        assertThat(clapSteered.animationTriggerId()).isEqualTo("clap");
        assertThat(clapSteered.ttsDirectives()).containsEntry("emotion", "excited");

        AiTurnResponse curiousSteered = client.requestTurn(new AiTurnRequest(
                "session-402",
                transcript,
                "תשאל אותו מי היה איתו בהפסקה"
        )).get();
        assertValidContract4Response(curiousSteered);
        assertThat(curiousSteered).isNotEqualTo(baseline);
        assertThat(curiousSteered.animationTriggerId()).isEqualTo("talk_curious");

        AiTurnResponse genericSteered = client.requestTurn(new AiTurnRequest(
                "session-402",
                transcript,
                "הכוונה מיוחדת מהמטפל"
        )).get();
        assertValidContract4Response(genericSteered);
        assertThat(genericSteered).isNotEqualTo(baseline);
    }

    private static void assertValidContract4Response(AiTurnResponse response) {
        assertThat(response).isNotNull();

        String reply = response.replyText();
        assertThat(reply).isNotBlank();
        assertThat(HEBREW_CHAR.matcher(reply).find()).isTrue();
        assertThat(reply).doesNotContain("*", "#", "`", "(", ")");
        assertThat(reply.codePoints().anyMatch(cp -> Character.getType(cp) == Character.OTHER_SYMBOL)).isFalse();

        assertThat(response.animationTriggerId()).isIn(LOCKED_ANIMATION_CATALOG);

        assertThat(response.ttsDirectives()).isNotNull().isNotEmpty();
        assertThat(response.ttsDirectives()).containsKeys("emotion", "pace", "pitch", "style");
        assertThat(response.ttsDirectives().get("emotion")).isIn(ALLOWED_EMOTIONS);
        assertThat(response.ttsDirectives().get("pace")).isIn(ALLOWED_PACES);
        assertThat(response.ttsDirectives().get("pitch")).isIn(ALLOWED_PITCHES);
        assertThat(response.ttsDirectives().get("style")).isIn(ALLOWED_STYLES);
    }
}
