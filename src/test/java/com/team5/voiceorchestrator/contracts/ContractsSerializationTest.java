package com.team5.voiceorchestrator.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket.LipSyncFrame;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractsSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unityResponsePacketRoundTripPreservesLockedCamelCaseFields() throws Exception {
        UnityResponsePacket packet = new UnityResponsePacket(
                "session-child-402",
                "turn-001",
                "AAAAAP//AQAB",
                16000,
                "talk_warm",
                List.of(
                        new LipSyncFrame(0L, "sil", 0.0f),
                        new LipSyncFrame(60L, "aa", 0.82f)
                )
        );

        String json = objectMapper.writeValueAsString(packet);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("sessionId")).isTrue();
        assertThat(root.has("turnId")).isTrue();
        assertThat(root.has("audioBase64")).isTrue();
        assertThat(root.has("sampleRateHz")).isTrue();
        assertThat(root.has("animationTriggerId")).isTrue();
        assertThat(root.has("lipSyncFrames")).isTrue();
        assertThat(root.size()).isEqualTo(6);

        UnityResponsePacket decoded = objectMapper.readValue(json, UnityResponsePacket.class);
        assertThat(decoded).isEqualTo(packet);
    }

    @Test
    void unityResponsePacketNormalizesMissingOrNullLipSyncFramesToEmptyImmutableList() throws Exception {
        String jsonMissingFrames = """
                {
                  "sessionId": "session-1",
                  "turnId": "turn-1",
                  "audioBase64": "AAAA",
                  "sampleRateHz": 16000,
                  "animationTriggerId": "idle"
                }
                """;

        UnityResponsePacket fromMissing = objectMapper.readValue(jsonMissingFrames, UnityResponsePacket.class);
        assertThat(fromMissing.lipSyncFrames()).isNotNull().isEmpty();
        assertThatThrownBy(() -> fromMissing.lipSyncFrames().add(new LipSyncFrame(10L, "aa", 0.5f)))
                .isInstanceOf(UnsupportedOperationException.class);

        UnityResponsePacket fromNull = new UnityResponsePacket("s1", "t1", "AAAA", 16000, "idle", null);
        assertThat(fromNull.lipSyncFrames()).isNotNull().isEmpty();
    }

    @Test
    void unityControlMessageRoundTripOmitsNullMessageAndIncludesErrorMessage() throws Exception {
        UnityControlMessage start = UnityControlMessage.of(
                UnityControlMessage.TYPE_SESSION_START,
                "session-child-402",
                1795352000000L
        );

        String startJson = objectMapper.writeValueAsString(start);
        JsonNode startNode = objectMapper.readTree(startJson);
        assertThat(startNode.has("message")).isFalse();
        assertThat(objectMapper.readValue(startJson, UnityControlMessage.class)).isEqualTo(start);

        UnityControlMessage error = new UnityControlMessage(
                UnityControlMessage.TYPE_ERROR,
                "session-child-402",
                1795352020100L,
                "Audio frame must be 16kHz 16-bit mono PCM_S16LE"
        );

        String errorJson = objectMapper.writeValueAsString(error);
        JsonNode errorNode = objectMapper.readTree(errorJson);
        assertThat(errorNode.get("type").asText()).isEqualTo("error");
        assertThat(errorNode.get("message").asText()).contains("PCM_S16LE");
        assertThat(objectMapper.readValue(errorJson, UnityControlMessage.class)).isEqualTo(error);
    }

    @Test
    void aiTurnRequestRoundTripPreservesUtf8HebrewTranscript() throws Exception {
        String hebrew = "היום היה לי קצת קשה בהפסקה כי אף אחד לא רצה לשחק איתי";
        AiTurnRequest request = new AiTurnRequest("session-child-402", hebrew);

        String json = objectMapper.writeValueAsString(request);
        AiTurnRequest decoded = objectMapper.readValue(json, AiTurnRequest.class);

        assertThat(decoded).isEqualTo(request);
        assertThat(decoded.transcript()).isEqualTo(hebrew);
    }

    @Test
    void aiTurnResponseRoundTripAndNullSafeDirectivesNormalization() throws Exception {
        AiTurnResponse response = new AiTurnResponse(
                "אני שומע שזה היה ממש לא נעים להישאר לבד בהפסקה.",
                Map.of("emotion", "empathetic", "pace", "slow"),
                "talk_warm"
        );

        String json = objectMapper.writeValueAsString(response);
        AiTurnResponse decoded = objectMapper.readValue(json, AiTurnResponse.class);
        assertThat(decoded).isEqualTo(response);

        AiTurnResponse withNullMap = objectMapper.readValue("""
                {
                  "replyText": "שלום חבר",
                  "ttsDirectives": null,
                  "animationTriggerId": "wave"
                }
                """, AiTurnResponse.class);
        assertThat(withNullMap.ttsDirectives()).isNotNull().isEmpty();

        Map<String, String> dirtyMap = new HashMap<>();
        dirtyMap.put("emotion", "warm");
        dirtyMap.put("pace", null);
        dirtyMap.put(null, "ignored");

        AiTurnResponse cleaned = new AiTurnResponse("שלום", dirtyMap, "talk_warm");
        assertThat(cleaned.ttsDirectives()).containsExactlyEntriesOf(Map.of("emotion", "warm"));
        assertThatThrownBy(() -> cleaned.ttsDirectives().put("pitch", "normal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aiTurnRequestSupportsOptionalLiveSteering() throws Exception {
        AiTurnRequest withoutSteering = new AiTurnRequest("session-child-402", "שלום");
        JsonNode withoutNode = objectMapper.readTree(objectMapper.writeValueAsString(withoutSteering));
        assertThat(withoutNode.has("liveSteering")).isFalse();

        String steering = "עודד אותו לשתף מה קרה בהפסקה, בטון רגוע";
        AiTurnRequest withSteering = new AiTurnRequest("session-child-402", "שלום", steering);
        String json = objectMapper.writeValueAsString(withSteering);
        AiTurnRequest decoded = objectMapper.readValue(json, AiTurnRequest.class);
        assertThat(decoded).isEqualTo(withSteering);
        assertThat(decoded.liveSteering()).isEqualTo(steering);
    }

    @Test
    void therapistTranscriptEventRoundTripOmitsNullTriggerForChild() throws Exception {
        TherapistTranscriptEvent childEvent = TherapistTranscriptEvent.childUtterance(
                "session-child-402", "turn-001", "קצת עצוב לי היום", 1795352010000L
        );
        String childJson = objectMapper.writeValueAsString(childEvent);
        JsonNode childNode = objectMapper.readTree(childJson);
        assertThat(childNode.has("animationTriggerId")).isFalse();
        assertThat(objectMapper.readValue(childJson, TherapistTranscriptEvent.class)).isEqualTo(childEvent);

        TherapistTranscriptEvent characterEvent = TherapistTranscriptEvent.characterReply(
                "session-child-402", "turn-001", "אני כאן איתך, רוצה לספר לי מה קרה?", "talk_warm", 1795352012500L
        );
        String charJson = objectMapper.writeValueAsString(characterEvent);
        assertThat(objectMapper.readValue(charJson, TherapistTranscriptEvent.class)).isEqualTo(characterEvent);
    }

    @Test
    void therapistControlMessageRoundTripForSteeringAndAuth() throws Exception {
        TherapistControlMessage steering = TherapistControlMessage.liveSteering(
                "session-child-402", "תעבור לטון מעודד ותשאל על המשחק", 1795352020000L
        );
        String steeringJson = objectMapper.writeValueAsString(steering);
        JsonNode steeringNode = objectMapper.readTree(steeringJson);
        assertThat(steeringNode.has("token")).isFalse();
        assertThat(objectMapper.readValue(steeringJson, TherapistControlMessage.class)).isEqualTo(steering);

        TherapistControlMessage auth = TherapistControlMessage.auth(
                "session-child-402", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test", 1795352000000L
        );
        String authJson = objectMapper.writeValueAsString(auth);
        JsonNode authNode = objectMapper.readTree(authJson);
        assertThat(authNode.has("instruction")).isFalse();
        assertThat(objectMapper.readValue(authJson, TherapistControlMessage.class)).isEqualTo(auth);
    }
}
