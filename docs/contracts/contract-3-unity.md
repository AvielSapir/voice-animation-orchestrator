# Contract 3: Unity Child Client (Team 4) ↔ Voice & Animation Orchestrator (Team 5)

| Metadata | Value |
| --- | --- |
| **Version** | `1.0.0` |
| **Status** | `LOCKED` (Phase 1) |
| **Owner** | Team 5 — Tech Lead |
| **Parties** | **Team 4** (Unity C# Child Client) ↔ **Team 5** (`voice-animation-orchestrator`, Java 17 / Spring Boot 3.3.5) |
| **Date** | `2026-11-22` |
| **Human Overview (Hebrew)** | [`contract-3-unity.html`](./contract-3-unity.html) |
| **JSON Schemas** | [`unity-response-packet.schema.json`](./schemas/unity-response-packet.schema.json), [`unity-control-message.schema.json`](./schemas/unity-control-message.schema.json) |
| **Java DTOs (Team 5)** | `com.team5.voiceorchestrator.contracts.UnityResponsePacket`, `UnityControlMessage` |

---

## 1. WebSocket Transport Specification

- **Endpoint Path:** `/ws/unity` (`orchestrator.websocket.endpoint`)
- **Connection URL:**
  - Local dev: `ws://<host>:8080/ws/unity?sessionId=<sessionId>`
  - Production / TLS: `wss://<host>/ws/unity?sessionId=<sessionId>`
- **Session Identification (`sessionId`):**
  - **Primary (Required):** Query parameter `sessionId` (e.g., `?sessionId=session-child-402`).
  - **Fallback Header:** `X-Session-Id: <sessionId>` (supported on native WebSocket clients).
  - **Validation:** Missing or blank `sessionId` MUST be rejected immediately with WebSocket close code `1008` (`POLICY_VIOLATION`).
- **Single Active Connection Rule:**
  - At most **one** active WebSocket connection exists per `sessionId`.
  - If Unity reconnects with the same `sessionId`, Team 5's `SessionRegistry` supersedes the stale connection, closes the old socket cleanly (`1000` `NORMAL_CLOSURE`), and binds the new socket.
- **Configured Buffer Limits (`application.yml`):**
  - Max binary message size: `65,536` bytes (`64 KB`)
  - Max text message size: `16,384` bytes (`16 KB`)
- **Frame Usage:**
  - **Binary WebSocket frames:** Upstream raw child microphone audio (`Unity -> Team 5`).
  - **Text WebSocket frames (UTF-8 JSON):** Control messages (bidirectional) and character turn responses (`Team 5 -> Unity`).

---

## 2. Upstream Audio Format (Unity → Team 5, Binary Frames)

All upstream binary WebSocket frames **MUST** conform strictly to the following locked PCM format:

| Parameter | Locked Value | Config Key (`application.yml`) |
| --- | --- | --- |
| **Encoding** | Raw Linear PCM, signed 16-bit integer, little-endian (`PCM_S16LE`) | — |
| **Container / Header** | **NONE** (raw PCM bytes only; NO WAV/RIFF header) | — |
| **Sample Rate** | `16000` Hz | `orchestrator.audio.sample-rate-hz` |
| **Channels** | `1` (Mono) | `orchestrator.audio.channels` |
| **Bits Per Sample** | `16` (`2` bytes/sample, range `[-32768, 32767]`) | `orchestrator.audio.bits-per-sample` |
| **Target Chunk Duration** | `20` ms | `orchestrator.audio.chunk-duration-ms` |
| **Target Chunk Size** | **`640` bytes** (`16000 * 0.020 * 1 * 2`) = `320` samples | — |
| **Target Cadence & Bitrate** | `50` chunks/sec (`256 kbps` / `32 KB/s`) | — |

### 2.1 Unity Float-to-PCM_S16LE Conversion Rule
Unity `AudioClip.GetData(...)` yields normalized `float` samples in `[-1.0f, 1.0f]`. Convert each sample to little-endian `Int16`:

```csharp
float clamped = Mathf.Clamp(sample, -1.0f, 1.0f);
short pcm16 = (short)(clamped >= 1.0f ? short.MaxValue : clamped * 32768.0f);
buffer[i * 2]     = (byte)(pcm16 & 0xFF);         // LSB (little-endian)
buffer[i * 2 + 1] = (byte)((pcm16 >> 8) & 0xFF);  // MSB
```

### 2.2 Continuous Streaming & Server-Side VAD Rules
1. **No client-side silence gating:** Unity MUST stream continuous `640`-byte chunks (including silence) while the session is listening. Team 5's `ServerVadDetector` performs server-side Voice Activity Detection and requires silence frames to detect end-of-utterance cleanly (`orchestrator.vad.silence-threshold-ms`: `1000` ms default, `900` ms in `mock` profile — tuned for children who pause mid-thought).
2. **Echo prevention:** While the character is playing back synthesized audio, Unity SHOULD enable hardware AEC (`AudioSettings`) or send zeroed silence frames unless the child intentionally triggers barge-in (high mic threshold or `barge_in` control message).
3. **Chunk alignment:** Binary payloads MUST have an even byte length (multiple of `2` bytes) and MUST stay `<= 65,536` bytes. Preferred frame size is `640` bytes (`20` ms); `1,280` bytes (`40` ms) is tolerated during transient buffering.

---

## 3. Control Messages (Bidirectional Text JSON Frames — `UnityControlMessage`)

Schema: [`schemas/unity-control-message.schema.json`](./schemas/unity-control-message.schema.json)

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `type` | `string` | Yes | One of: `session_start`, `session_end`, `ping`, `pong`, `barge_in`, `error` |
| `sessionId` | `string` | Yes | Active child session identifier |
| `timestampMs` | `integer` (`int64`) | Yes | Epoch timestamp in milliseconds (`>= 0`) |
| `message` | `string` | No | Optional human-readable detail (primarily for `error`; omitted when `null`) |

### 3.1 Control Message Types

| `type` | Direction | Semantics |
| --- | --- | --- |
| `session_start` | `Unity -> Team 5` | Optional explicit handshake after WebSocket open confirming mic capture is ready. |
| `session_end` | `Unity -> Team 5` | Graceful end-of-session signal before closing WebSocket (`1000`); triggers state cleanup. |
| `ping` | Bidirectional | Keep-alive heartbeat sent every `15,000–30,000` ms when idle. Receiver replies immediately with `pong`. |
| `pong` | Bidirectional | Heartbeat acknowledgement echoing the `sessionId` and current `timestampMs`. |
| `barge_in` | `Unity -> Team 5` | Signals the child interrupted character playback; Unity stops audio output locally and Team 5 cancels in-flight turn playback if supported. |
| `error` | `Team 5 -> Unity` | Non-fatal protocol warning or diagnostic error (e.g., malformed audio frame). Contains `message`. |

### 3.2 Control Message JSON Examples

```json
{
  "type": "session_start",
  "sessionId": "session-child-402",
  "timestampMs": 1795352000000
}
```

```json
{
  "type": "error",
  "sessionId": "session-child-402",
  "timestampMs": 1795352020100,
  "message": "Audio frame must have even byte length (16-bit PCM_S16LE)"
}
```

### 3.3 Text Frame Disambiguation Rule (Unity Receiver)
Every server-to-Unity text frame is valid UTF-8 JSON. Unity inspects root keys:
- If `"turnId"` and `"audioBase64"` are present → deserialize as **`UnityResponsePacket`**.
- If `"type"` is present → deserialize as **`UnityControlMessage`**.

---

## 4. Downstream Character Turn Response (Team 5 → Unity — `UnityResponsePacket`)

Schema: [`schemas/unity-response-packet.schema.json`](./schemas/unity-response-packet.schema.json)

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `sessionId` | `string` | Yes | Active child session identifier. Must match current connection's `sessionId`. |
| `turnId` | `string` | Yes | Unique turn identifier (UUID). Unity uses this to correlate audio/animation and prevent stale playback. |
| `audioBase64` | `string` | Yes | Base64-encoded raw **`PCM_S16LE`** mono audio bytes at `sampleRateHz` (NO WAV header). Decode with `Convert.FromBase64String(packet.audioBase64)`. |
| `sampleRateHz` | `integer` | Yes | Audio sample rate in Hz (locked default `16000`). Always use `packet.sampleRateHz` when creating `AudioClip.Create(...)`. |
| `animationTriggerId` | `string` | Yes | Lowercase `snake_case` trigger from Section 5. Safe fallback if unknown: `talk_warm` or `idle`. |
| `lipSyncFrames` | `array<LipSyncFrame>` | Yes | Non-null array of viseme keyframes. May be empty (`[]`). |

### 4.1 `LipSyncFrame` Object

| Field | Type | Required | Constraints & Semantics |
| --- | --- | --- | --- |
| `timestampMs` | `integer` (`int64`) | Yes | Millisecond offset from start of `audioBase64` clip (`>= 0`). |
| `viseme` | `string` | Yes | Viseme code from Section 6 (e.g., `"sil"`, `"aa"`, `"mbp"`). |
| `weight` | `number` (`float32`) | Yes | Normalized blendshape weight in `[0.0, 1.0]`. |

### 4.2 `UnityResponsePacket` JSON Example

```json
{
  "sessionId": "session-child-402",
  "turnId": "7f3c8b1e-2d1a-4b64-9a21-0c5a9b1e4410",
  "audioBase64": "AAAAAP//AQABAP//AgD+/w==",
  "sampleRateHz": 16000,
  "animationTriggerId": "talk_warm",
  "lipSyncFrames": [
    { "timestampMs": 0, "viseme": "sil", "weight": 0.0 },
    { "timestampMs": 60, "viseme": "aa", "weight": 0.82 },
    { "timestampMs": 140, "viseme": "mbp", "weight": 0.65 }
  ]
}
```

### 4.3 Unity Audio Playback Note
After `byte[] pcmBytes = Convert.FromBase64String(packet.audioBase64)`:
1. Convert `pcmBytes` (little-endian `Int16`) to `float[]` in `[-1.0f, 1.0f]` (`sampleCount = pcmBytes.Length / 2`).
2. Construct `AudioClip.Create(packet.turnId, sampleCount, 1, packet.sampleRateHz, false)` and call `clip.SetData(samples, 0)`.
3. Play immediately alongside `Animator.SetTrigger(packet.animationTriggerId)`.

---

## 5. Shared `animationTriggerId` Catalog (v1.0.0)

Team 3 (AI), Team 4 (Unity), and Team 5 (Orchestrator) share this closed v1.0.0 trigger catalog. All triggers are lowercase `snake_case`:

| Trigger ID | Meaning / Character Behavior | Intended Context |
| --- | --- | --- |
| `idle` | Calm, gentle breathing standby posture | Session idle / no active turn |
| `listen` | Attentive head tilt, open posture | Child is speaking (`SPEECH_START`) or waiting |
| `think` | Subtle thoughtful look / hand near chin | Short pause / reflection cue |
| `wave` | Friendly welcoming or goodbye wave | Greeting / closing a session |
| `talk_warm` | Warm, supportive speaking gestures (**primary fallback**) | Empathetic / comforting reply |
| `talk_happy` | Bright, smiling speaking posture | Cheerful / encouraging reply |
| `talk_curious` | Engaged, inquisitive expression | Follow-up question |
| `talk_calm` | Slow, grounding, soothing posture | Calming an anxious or upset child |
| `clap` | Gentle celebratory clap | Praising child's achievement or courage |
| `hug_gesture` | Warm open-arms comforting gesture | High emotional support moment |

**Safety Fallback Rule:** If Unity receives an unrecognized `animationTriggerId`, Unity MUST NOT throw or freeze; fall back to `talk_warm` (when audio is non-empty) or `idle` (when silent).

---

## 6. Viseme Set & Lip-Sync Fallback Rule

- **Viseme Catalog (`LipSyncFrame.viseme`):**
  `sil` (silence), `aa` (open vowel), `ee` (wide vowel), `ih` (mid vowel), `oh` (rounded O), `ou` (rounded U), `mbp` (closed lips M/B/P), `fv` (lower lip F/V), `th` (dental TH), `l` (tongue tip L/N/D/T), `sz` (sibilant S/Z/SH).
- **Mandatory Empty-Array Fallback (`lipSyncFrames == []`):**
  In Phase 1 and when a TTS provider does not emit phoneme timings, Team 5 sends `"lipSyncFrames": []`. Unity **MUST** support driving jaw/mouth blendshapes dynamically from the real-time **RMS amplitude** of the decoded `AudioSource` playback buffer whenever `lipSyncFrames` is empty.

---

## 7. Ready-to-Use Unity C# DTOs (Team 4 Reference)

```csharp
using System;
using System.Collections.Generic;

namespace Team4.VoiceClient.Contracts
{
    [Serializable]
    public sealed class UnityResponsePacket
    {
        public string sessionId;
        public string turnId;
        public string audioBase64;
        public int sampleRateHz;
        public string animationTriggerId;
        public List<LipSyncFrame> lipSyncFrames = new List<LipSyncFrame>();
    }

    [Serializable]
    public sealed class LipSyncFrame
    {
        public long timestampMs;
        public string viseme;
        public float weight;
    }

    [Serializable]
    public sealed class UnityControlMessage
    {
        public string type;
        public string sessionId;
        public long timestampMs;
        public string message;
    }
}
```

*(Note: Unity's `JsonUtility` requires exact camelCase field names as declared above and works seamlessly with `List<LipSyncFrame>`. Avoid `[JsonProperty]`-only renaming if using `JsonUtility`.)*

---

## 8. Agent Implementation Checklist (Team 4)

- [ ] Connect to `ws://<host>:8080/ws/unity?sessionId=<id>` (handle close code `1008` and reconnect cleanly).
- [ ] Capture microphone at `16000 Hz`, mono, convert `float[]` → `PCM_S16LE` with **no WAV header**, and stream `640`-byte (`20 ms`) binary WebSocket frames continuously.
- [ ] Send `UnityControlMessage` (`ping` / `session_start` / `session_end`) as UTF-8 text frames; reply `pong` on server `ping`.
- [ ] Parse incoming text frames using the Section 3.3 disambiguation rule (`turnId` + `audioBase64` vs `type`).
- [ ] Decode `audioBase64` into raw `PCM_S16LE` samples at `packet.sampleRateHz` and play via `AudioClip.Create`.
- [ ] Trigger Animator state `packet.animationTriggerId` with safe fallback to `talk_warm` / `idle`.
- [ ] Drive blendshapes from `lipSyncFrames` if non-empty, or real-time audio RMS amplitude if `lipSyncFrames` is `[]`.
