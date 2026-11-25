package com.team5.voiceorchestrator.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.TherapistControlMessage;
import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TherapistWebSocketHandlerTest {

    private static final String VALID_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0aGVyYXBpc3QtMSJ9.signaturePart123";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SessionRegistry sessionRegistry;
    private AtomicReference<String> capturedSteeringSession;
    private AtomicReference<String> capturedSteeringInstruction;
    private TherapistWebSocketHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionRegistry = new SessionRegistry();
        capturedSteeringSession = new AtomicReference<>();
        capturedSteeringInstruction = new AtomicReference<>();

        OrchestratorProperties properties = new OrchestratorProperties();
        Executor directExecutor = Runnable::run;
        ObjectProvider<TherapistWebSocketHandler.LiveSteeringListener> listenerProvider = mock(ObjectProvider.class);
        when(listenerProvider.getIfAvailable()).thenReturn((sid, instr) -> {
            capturedSteeringSession.set(sid);
            capturedSteeringInstruction.set(instr);
        });

        handler = new TherapistWebSocketHandler(
                sessionRegistry,
                objectMapper,
                properties,
                directExecutor,
                listenerProvider
        );
    }

    @AfterEach
    void tearDown() {
        sessionRegistry.shutdown();
    }

    private static WebSocketSession mockSession(String id, String uriString) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(uriString == null ? null : URI.create(uriString));
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void rejectsInvalidJwtAndUnauthenticatedSteeringWithPolicyViolation() throws Exception {
        WebSocketSession badJwtSession = mockSession("t-bad", "ws://localhost:8080/ws/therapist?sessionId=s-1");
        handler.afterConnectionEstablished(badJwtSession);

        String badAuth = objectMapper.writeValueAsString(TherapistControlMessage.auth("s-1", "not-a-jwt", 100L));
        handler.handleTextMessage(badJwtSession, new TextMessage(badAuth));

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(badJwtSession).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());

        WebSocketSession unauthSession = mockSession("t-unauth", "ws://localhost:8080/ws/therapist?sessionId=s-2");
        handler.afterConnectionEstablished(unauthSession);

        String steering = objectMapper.writeValueAsString(
                TherapistControlMessage.liveSteering("s-2", "תעודד את הילד", 101L));
        handler.handleTextMessage(unauthSession, new TextMessage(steering));

        ArgumentCaptor<CloseStatus> unauthClose = ArgumentCaptor.forClass(CloseStatus.class);
        verify(unauthSession).close(unauthClose.capture());
        assertThat(unauthClose.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void authenticatesJwtRecordsSteeringAndPushesTranscriptEvents() throws Exception {
        WebSocketSession session = mockSession("t-ok", "ws://localhost:8080/ws/therapist?sessionId=child-707");
        handler.afterConnectionEstablished(session);

        String authJson = objectMapper.writeValueAsString(
                TherapistControlMessage.auth("child-707", VALID_JWT, 1000L));
        handler.handleTextMessage(session, new TextMessage(authJson));

        ActiveSession active = sessionRegistry.getSession("child-707").orElseThrow();
        assertThat(active.isTherapistAuthenticated()).isTrue();

        String instruction = "תעבור לטון מעודד ותשאל על המשחק בהפסקה";
        String steeringJson = objectMapper.writeValueAsString(
                TherapistControlMessage.liveSteering("child-707", instruction, 1010L));
        handler.handleTextMessage(session, new TextMessage(steeringJson));

        assertThat(capturedSteeringSession.get()).isEqualTo("child-707");
        assertThat(capturedSteeringInstruction.get()).isEqualTo(instruction);
        assertThat(active.pollLiveSteering()).isEqualTo(instruction);

        TherapistTranscriptEvent childEvent = TherapistTranscriptEvent.childUtterance(
                "child-707", "turn-1", "קצת עצוב לי היום", 1020L);
        assertThat(handler.sendTranscriptEvent("child-707", childEvent).join()).isTrue();

        TherapistTranscriptEvent characterEvent = TherapistTranscriptEvent.characterReply(
                "child-707", "turn-1", "אני כאן איתך!", "talk_warm", 1030L);
        assertThat(handler.sendTranscriptEvent("child-707", characterEvent).join()).isTrue();

        ArgumentCaptor<TextMessage> sentCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(sentCaptor.capture());

        TherapistTranscriptEvent decodedChild = objectMapper.readValue(
                sentCaptor.getAllValues().get(0).getPayload(), TherapistTranscriptEvent.class);
        TherapistTranscriptEvent decodedChar = objectMapper.readValue(
                sentCaptor.getAllValues().get(1).getPayload(), TherapistTranscriptEvent.class);
        assertThat(decodedChild).isEqualTo(childEvent);
        assertThat(decodedChar).isEqualTo(characterEvent);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(sessionRegistry.getSession("child-707")).isEmpty();
    }

    @Test
    void repliesPongToHeartbeatPingAndSupportsAuthWithoutQuerySessionId() throws Exception {
        WebSocketSession session = mockSession("t-noquery", "ws://localhost:8080/ws/therapist");
        handler.afterConnectionEstablished(session);

        String authJson = objectMapper.writeValueAsString(
                TherapistControlMessage.auth("child-808", VALID_JWT, 2000L));
        handler.handleTextMessage(session, new TextMessage(authJson));

        assertThat(sessionRegistry.getSession("child-808")).isPresent();
        assertThat(sessionRegistry.getSession("child-808").get().isTherapistAuthenticated()).isTrue();

        String pingJson = objectMapper.writeValueAsString(
                new TherapistControlMessage(TherapistControlMessage.TYPE_PING, "child-808", null, null, 2010L));
        handler.handleTextMessage(session, new TextMessage(pingJson));

        ArgumentCaptor<TextMessage> pongCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(pongCaptor.capture());
        TherapistControlMessage pong = objectMapper.readValue(
                pongCaptor.getValue().getPayload(), TherapistControlMessage.class);
        assertThat(pong.type()).isEqualTo(TherapistControlMessage.TYPE_PONG);
        assertThat(pong.sessionId()).isEqualTo("child-808");
    }
}
