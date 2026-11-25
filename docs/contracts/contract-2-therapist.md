# Contract 2: Therapist UI (Team 1) ↔ Voice & Animation Orchestrator (Team 5)

| Metadata | Value |
| --- | --- |
| **Version** | `1.0.0` |
| **Status** | `LOCKED` (Phase 1) |
| **Owner** | Team 5 — Tech Lead |
| **Parties** | **Team 1** (React + Vite Therapist UI, WebSocket client) ↔ **Team 5** (`voice-animation-orchestrator`, WebSocket server) |
| **Date** | `2026-11-25` |
| **Human Overview (Hebrew)** | [`contract-2-therapist.html`](./contract-2-therapist.html) |
| **JSON Schemas** | [`therapist-transcript-event.schema.json`](./schemas/therapist-transcript-event.schema.json), [`therapist-control-message.schema.json`](./schemas/therapist-control-message.schema.json) |
| **Java DTOs (Team 5)** | `com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent`, `TherapistControlMessage` |

---

## 1. WebSocket Transport & Auth Specification

- **Endpoint Path:** `/ws/therapist` (e.g., `ws://<host>:8080/ws/therapist?sessionId=<sessionId>`)
- **Frame Encoding:** UTF-8 JSON text frames in both directions.
- **Session Binding & Auth:**
  1. Therapist logs into Team 2 (Contract 1) and receives a JWT.
  2. Immediately upon opening `/ws/therapist`, Team 1 sends an `auth` frame containing `sessionId` and `token` (JWT) so Team 5 validates the token without exposing it in URL query logs.
  3. One active therapist connection per `sessionId`. Reconnecting replaces the previous therapist socket cleanly.

---

## 2. Outbound Event: Team 5 → Team 1 (`TherapistTranscriptEvent`)

Schema: [`schemas/therapist-transcript-event.schema.json`](./schemas/therapist-transcript-event.schema.json)

Pushed immediately after STT finishes (for `CHILD`) and after Team 3 replies (for `CHARACTER`).

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `sessionId` | `string` | Yes | Non-blank active child therapy session identifier. |
| `turnId` | `string` | No | Turn identifier correlating with the audio/animation turn. |
| `speaker` | `string` | Yes | Enum: `"CHILD"`, `"CHARACTER"`, or `"SYSTEM"`. |
| `text` | `string` | Yes | UTF-8 Hebrew transcript or character reply text. |
| `animationTriggerId` | `string` | No | Included on `"CHARACTER"` turns to show which reaction was triggered. |
| `timestampMs` | `integer` | Yes | Server Unix epoch timestamp in milliseconds (`>= 0`). |

### 2.1 Example JSON (`CHILD` & `CHARACTER`)

```json
{
  "sessionId": "session-child-402",
  "turnId": "turn-001",
  "speaker": "CHILD",
  "text": "קצת עצוב לי היום",
  "timestampMs": 1795352010000
}
```

```json
{
  "sessionId": "session-child-402",
  "turnId": "turn-001",
  "speaker": "CHARACTER",
  "text": "אני כאן איתך, רוצה לספר לי מה קרה?",
  "animationTriggerId": "talk_warm",
  "timestampMs": 1795352012500
}
```

---

## 3. Inbound Control: Team 1 → Team 5 (`TherapistControlMessage`)

Schema: [`schemas/therapist-control-message.schema.json`](./schemas/therapist-control-message.schema.json)

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `type` | `string` | Yes | Enum: `"auth"`, `"live_steering"`, `"session_start"`, `"session_end"`, `"ping"`, `"pong"`. |
| `sessionId` | `string` | Yes | Non-blank active session identifier. |
| `instruction` | `string` | Conditional | Required when `type == "live_steering"`. Forwarded by Team 5 to Team 3 via `AiTurnRequest.liveSteering` (Contract 4). |
| `token` | `string` | Conditional | Required when `type == "auth"`. JWT issued by Team 2. |
| `timestampMs` | `integer` | Yes | Client Unix epoch timestamp in milliseconds (`>= 0`). |

### 3.1 Example JSON (`live_steering`)

```json
{
  "type": "live_steering",
  "sessionId": "session-child-402",
  "instruction": "תעבור לטון מעודד ותשאל על המשחק בהפסקה",
  "timestampMs": 1795352020000
}
```
