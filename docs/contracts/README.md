# Team 5 Contracts & Phase 1 Verification (`v1.0.0 — LOCKED`)

This directory holds the locked communication contracts owned by **Team 5 (`voice-animation-orchestrator`)** for Phase 1.

Each contract is provided in three complementary formats:
1. **Agent-Ready Technical Spec (`.md`)** — Exact wire format, constraints, DTO code, error handling, and implementation checklists for AI coding agents and developers on Partner Teams 3 & 4.
2. **JSON Schema (`.schema.json`)** — Machine-readable JSON Schema (Draft 2020-12) for validation and contract tests.
3. **Hebrew Narrative Overview (`.html`)** — Clean, gentle Hebrew explanation of the contract and data flow for human alignment and quick onboarding.

---

## 1. Locked External Contracts

| Contract | Parties | Technical Spec (Agent-Ready) | Hebrew Overview (Human) | JSON Schemas | Java DTOs (`contracts/`) |
| --- | --- | --- | --- | --- | --- |
| **Contract 2** | Team 1 (Therapist React UI) ↔ Team 5 (Orchestrator) | [`contract-2-therapist.md`](./contract-2-therapist.md) | [`contract-2-therapist.html`](./contract-2-therapist.html) | [`therapist-transcript-event.schema.json`](./schemas/therapist-transcript-event.schema.json)<br>[`therapist-control-message.schema.json`](./schemas/therapist-control-message.schema.json) | `TherapistTranscriptEvent`<br>`TherapistControlMessage` |
| **Contract 3** | Team 4 (Unity C#) ↔ Team 5 (Orchestrator) | [`contract-3-unity.md`](./contract-3-unity.md) | [`contract-3-unity.html`](./contract-3-unity.html) | [`unity-response-packet.schema.json`](./schemas/unity-response-packet.schema.json)<br>[`unity-control-message.schema.json`](./schemas/unity-control-message.schema.json) | `UnityResponsePacket`<br>`UnityControlMessage` |
| **Contract 4** | Team 5 (Orchestrator) ↔ Team 3 (AI Engine) | [`contract-4-ai.md`](./contract-4-ai.md) | [`contract-4-ai.html`](./contract-4-ai.html) | [`ai-turn-request.schema.json`](./schemas/ai-turn-request.schema.json)<br>[`ai-turn-response.schema.json`](./schemas/ai-turn-response.schema.json) | `AiTurnRequest`<br>`AiTurnResponse` |

---

## 2. Internal Developer Interfaces Verification (Issue #1)

All four core internal service interfaces required for Phase 1 parallel development by **Dev B** and **Dev C** have been audited against Contract 3, Contract 4, and `ConversationOrchestrator`:

| Interface | Package / File | Owner (Impl) | Verified Method Signature(s) | Status |
| --- | --- | --- | --- | --- |
| `VadDetector` | `voice/VadDetector.java` | Dev B (`voice/vad/`) | `VadResult processChunk(String sessionId, byte[] audioChunk)`<br>`void resetSession(String sessionId)` | **LOCKED & VERIFIED** |
| `SttClient` | `voice/SttClient.java` | Dev B (`voice/mock/`, `voice/providers/`) | `CompletableFuture<String> transcribe(String sessionId, byte[] audioBytes)` | **LOCKED & VERIFIED** |
| `TtsClient` | `voice/TtsClient.java` | Dev B (`voice/mock/`, `voice/providers/`) | `CompletableFuture<byte[]> synthesize(String sessionId, String text, Map<String, String> ttsDirectives)` | **LOCKED & VERIFIED** |
| `AiClient` | `ai/AiClient.java` | Dev C (`ai/`) | `CompletableFuture<AiTurnResponse> requestTurn(AiTurnRequest request)` | **LOCKED & VERIFIED** |

---

## 3. Change Control Policy

- **Frozen Signatures:** Developers in Team 5 MUST NOT modify files under `contracts/` or `docs/contracts/` directly.
- **Additive Evolution Only:** Once locked in Phase 1, updates must remain backwards-compatible (e.g., optional nullable fields or additive `ttsDirectives` keys) and go through a dedicated **Tech Lead PR** notifying Team 3, Team 4, and all Team 5 developers.
- **Audio & Animation Constants:** Any adjustment to `orchestrator.audio.*` defaults in `application.yml` or the 10-trigger `animationTriggerId` catalog requires updating both `contract-3-unity.md` and `contract-4-ai.md` in lockstep.
