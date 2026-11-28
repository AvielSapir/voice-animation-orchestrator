# Contract 4: Voice & Animation Orchestrator (Team 5) ↔ AI Conversation Service (Team 3)

| Metadata | Value |
| --- | --- |
| **Version** | `1.1.0` |
| **Status** | `LOCKED` (Phase 1) |
| **Owner** | Team 5 — Tech Lead |
| **Parties** | **Team 5** (`voice-animation-orchestrator`, caller) ↔ **Team 3** (AI Conversation Service, HTTP provider) |
| **Date** | `2026-11-25` |
| **Human Overview (Hebrew)** | [`contract-4-ai.html`](./contract-4-ai.html) |
| **JSON Schemas** | [`ai-turn-request.schema.json`](./schemas/ai-turn-request.schema.json), [`ai-turn-response.schema.json`](./schemas/ai-turn-response.schema.json) |
| **Java DTOs & Interface (Team 5)** | `com.team5.voiceorchestrator.contracts.AiTurnRequest`, `AiTurnResponse`; `ai.AiClient` |

---

## 1. HTTP Transport Specification

- **Base URL Config:** `orchestrator.ai.base-url` (env `AI_ENGINE_BASE_URL`, default `http://localhost:8081`)
- **Endpoint Path:** `POST /api/v1/turn` (`orchestrator.ai.turn-endpoint`)
- **Headers:**
  - Request: `Content-Type: application/json; charset=utf-8`, `Accept: application/json`
  - Response: `Content-Type: application/json; charset=utf-8`
- **Character Encoding:** Strict **UTF-8** (Hebrew text in `transcript` and `replyText` MUST NOT be ASCII-escaped or Latin-1 mangled).
- **Timeouts & Latency Targets:**
  - Client timeout (`orchestrator.ai.timeout-ms`): `5000 ms` (hard limit in `RemoteAiClient`; `2000 ms` in `mock` profile).
  - Target p95 server response latency: **`<= 1500 ms`** to preserve real-time child conversation flow.

---

## 2. Request Payload (`AiTurnRequest`)

Schema: [`schemas/ai-turn-request.schema.json`](./schemas/ai-turn-request.schema.json)

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `sessionId` | `string` | Yes | Non-blank active child session identifier. Team 3 uses this key to fetch and maintain session context (character persona, patient background, session goals, conversation history). |
| `transcript` | `string` | Yes | Non-blank UTF-8 Hebrew text transcribed from the child's completed speech utterance. |
| `liveSteering` | `string` | No | Optional covert therapist steering instruction forwarded by Team 5 from Contract 2 (`live_steering`), omitted when null. |

### 2.1 Request JSON Example

```json
{
  "sessionId": "session-child-402",
  "transcript": "היום היה לי קצת קשה בהפסקה כי אף אחד לא רצה לשחק איתי",
  "liveSteering": "עודד אותו לשתף מי היה איתו ומה הרגיש, בטון רגוע"
}
```

### 2.2 Java 17 Record (Team 3 / Team 5 DTO)

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiTurnRequest(
        String sessionId,
        String transcript,
        String liveSteering
) {
    public AiTurnRequest(String sessionId, String transcript) {
        this(sessionId, transcript, null);
    }
}
```

---

## 3. Response Payload (`AiTurnResponse` — Strict Structured Output)

Schema: [`schemas/ai-turn-response.schema.json`](./schemas/ai-turn-response.schema.json)

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `replyText` | `string` | Yes | Non-blank plain spoken Hebrew response sent directly to TTS. **MUST NOT** contain markdown (`**`, `#`, `- `), bullet lists, emojis, or stage directions like `(מחייך)`. |
| `ttsDirectives` | `object` (`Map<String, String>`) | Yes | Flat key-value map of vocal tone/style instructions for TTS. **No nested objects, arrays, or `null` values.** May be `{}` if neutral defaults apply. |
| `animationTriggerId` | `string` | Yes | Lowercase `snake_case` animation trigger from Section 5 (fallback: `"talk_warm"`). |

### 3.1 Response JSON Example

```json
{
  "replyText": "אני שומע שזה היה ממש לא נעים להישאר לבד בהפסקה. רוצה לספר לי מה הרגשת באותו רגע?",
  "ttsDirectives": {
    "emotion": "empathetic",
    "pace": "slow",
    "pitch": "normal",
    "style": "soothing"
  },
  "animationTriggerId": "talk_warm"
}
```

### 3.2 Java 17 Record (Team 3 / Team 5 DTO)

```java
public record AiTurnResponse(
        String replyText,
        Map<String, String> ttsDirectives,
        String animationTriggerId
) {
}
```

---

## 4. Standard `ttsDirectives` Keys & Values

Team 3 SHOULD emit values from this locked dictionary so Team 5's `RealTtsClient` can map them reliably across TTS providers:

| Key | Allowed Values | Default if Omitted |
| --- | --- | --- |
| `emotion` | `warm`, `empathetic`, `happy`, `excited`, `curious`, `calm`, `gentle`, `hesitant` | `warm` |
| `pace` | `slow`, `normal`, `fast` | `normal` |
| `pitch` | `low`, `normal`, `high` | `normal` |
| `style` | `conversational`, `whisper`, `playful`, `soothing` | `conversational` |

**Forward-Compatibility Rules:**
- Every value in `ttsDirectives` MUST be a JSON `string`.
- Team 5 safely ignores unrecognized directive keys and falls back to warm child-friendly defaults when keys are absent.
- Team 5's `AiTurnResponse` record filters out any accidental `null` values or `null` maps on deserialization.

---

## 5. Shared `animationTriggerId` Catalog (v1.0.0)

Team 3 MUST choose `animationTriggerId` from this locked catalog (identical to Contract 3 for Unity):

| Trigger ID | Character Posture / Meaning |
| --- | --- |
| `idle` | Calm resting posture |
| `listen` | Attentive listening posture |
| `think` | Thoughtful pause gesture |
| `wave` | Greeting / goodbye wave |
| `talk_warm` | Warm, supportive speaking (**primary fallback**) |
| `talk_happy` | Bright, smiling speaking |
| `talk_curious` | Inquisitive, engaged speaking |
| `talk_calm` | Grounding, gentle speaking |
| `clap` | Gentle encouraging applause |
| `hug_gesture` | Warm open-arms comforting gesture |

**Fallback Guarantee:** Team 5's `AnimationMapper` validates `animationTriggerId` against this catalog. Any missing, blank, or unrecognized value is automatically mapped to `"talk_warm"` before sending `UnityResponsePacket` to Team 4.

---

## 6. HTTP Status Codes, Timeouts & Resilience Contract

| HTTP Status | Meaning | Team 5 Behavior (`RemoteAiClient`) |
| --- | --- | --- |
| `200 OK` | Valid `AiTurnResponse` JSON returned | Validates `replyText` is non-blank and passes to `AnimationMapper` + `TtsClient`. |
| `400 Bad Request` | Invalid request JSON or blank `sessionId`/`transcript` | Logs error, triggers calm Hebrew fallback `AiTurnResponse`. |
| `404 Not Found` | Unknown/expired `sessionId` on Team 3 | Logs error, triggers calm Hebrew fallback `AiTurnResponse`. |
| `5xx` / Timeout (`> 5000ms`) / Connection Refused | Team 3 unavailable or LLM failure | Never propagates exception to crash WebSocket session; emits safe fallback `AiTurnResponse` (e.g., `"אני כאן איתך, אפשר להגיד לי את זה שוב?"`, `emotion=calm`, `animationTriggerId=talk_warm`). |

---

## 7. Safety Guardrails & Live Steering Boundary

1. **Child Safety Guardrails:** Team 3 is responsible for enforcing content safety, distress detection, and age-appropriate Hebrew phrasing *before* returning `AiTurnResponse`. Team 5 treats `replyText` as ready-to-speak output.
2. **Therapist Live Steering (`v1.1.0`):** Therapist `live_steering` instructions arrive at Team 5 from Team 1 via Contract 2 (`/ws/therapist`) and are forwarded by Team 5 in the optional `liveSteering` field of `AiTurnRequest` so Team 3 can inject them into the turn prompt.

---

## 8. Agent Implementation Checklist (Team 3)

- [ ] Expose `POST /api/v1/turn` accepting `AiTurnRequest(sessionId, transcript, liveSteering)` in UTF-8 JSON (where `liveSteering` is optional/nullable).
- [ ] Enforce LLM **Structured Output** so `replyText`, `ttsDirectives`, and `animationTriggerId` are always returned in valid JSON matching [`ai-turn-response.schema.json`](./schemas/ai-turn-response.schema.json).
- [ ] Strip/forbid markdown, emojis, and parenthetical stage directions from `replyText`.
- [ ] Constrain `animationTriggerId` to the 10 triggers in Section 5 (default `"talk_warm"`).
- [ ] Constrain `ttsDirectives` to flat string key-value pairs using Section 4 keys (`emotion`, `pace`, `pitch`, `style`).
- [ ] Return HTTP `200 OK` within `<= 1500 ms` p95 (must complete well under Team 5's `5000 ms` timeout).
