# voice-animation-orchestrator

Real-Time Voice & Animation Orchestrator microservice for **Team 5** (Java 17 + Spring Boot 3.x).

This service acts as the low-latency real-time bridge between **Team 4 (Unity C# client)** and **Team 3 (Spring Boot AI engine)**:

```
Unity (Team 4) --[PCM Audio Chunks]--> Transport --> VAD --> STT --> AI (Team 3) --> TTS + Animation --> Unity (Team 4)
```

## Tech Stack
- **Java 17**
- **Spring Boot 3.3.5** (`spring-boot-starter-websocket`, `spring-boot-starter-webflux`, Jackson)
- **Maven**

## Repository Structure & Folder Ownership

| Owner | Package / Directory | Responsibility |
| --- | --- | --- |
| **Tech Lead** | `pom.xml`, `README.md`, `docs/contracts/`, `src/main/resources/`, `VoiceAnimationOrchestratorApplication.java`, `config/`, `contracts/`, `orchestration/ConversationOrchestrator.java` | Project scaffold, shared contracts, configuration properties, and end-to-end pipeline wiring. |
| **Dev A (Transport)** | `src/main/java/com/team5/voiceorchestrator/transport/` | WebSocket endpoint (`UnityWebSocketHandler`), session registry (`SessionRegistry`), and per-session state (`ActiveSession`). |
| **Dev B (Voice)** | `src/main/java/com/team5/voiceorchestrator/voice/mock/`, `voice/vad/`, `voice/providers/` | Server-side VAD (`ServerVadDetector`), mock and real STT/TTS implementations (`SttClient`, `TtsClient`). |
| **Dev C (Orchestration & QA)** | `src/main/java/com/team5/voiceorchestrator/ai/`, `orchestration/` (excluding `ConversationOrchestrator.java`), `src/test/java/com/team5/voiceorchestrator/simulator/` | Team 3 AI client (`MockAiClient`, `RemoteAiClient`), `TurnManager`, `AnimationMapper`, `LatencyTracker`, and end-to-end Unity simulator tests. |

## Running Locally (`mock` Profile)

The `mock` profile enables local development and end-to-end testing without external STT, TTS, or Team 3 AI services.

```bash
# Compile and run tests
mvn test

# Run the application with the mock profile active
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

Or using a packaged JAR:

```bash
mvn clean package
java -jar target/voice-animation-orchestrator-0.1.0-SNAPSHOT.jar --spring.profiles.active=mock
```
