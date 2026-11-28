package com.team5.voiceorchestrator.ai;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAiClientTest {

    @Test
    void sendsInternalServiceKeyHeaderAndParsesResponse() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction exchange = req -> {
            captured.set(req);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"replyText":"שלום חבר!","ttsDirectives":{"emotion":"warm"},"animationTriggerId":"talk_warm"}
                            """)
                    .build());
        };

        RemoteAiClient client = createClient("secret-key-123", exchange);
        AiTurnResponse response = client.requestTurn(new AiTurnRequest("session-uuid-1", "היי")).join();

        assertThat(captured.get().headers().getFirst(RemoteAiClient.INTERNAL_SERVICE_KEY_HEADER))
                .isEqualTo("secret-key-123");
        assertThat(response.replyText()).isEqualTo("שלום חבר!");
        assertThat(response.animationTriggerId()).isEqualTo("talk_warm");
    }

    @Test
    void returnsFallbackWithoutRetryingOnServerError() {
        AtomicInteger callCount = new AtomicInteger(0);
        ExchangeFunction exchange = req -> {
            callCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("error").build());
        };

        RemoteAiClient client = createClient("secret-key-123", exchange);
        AiTurnResponse response = client.requestTurn(new AiTurnRequest("session-uuid-2", "היי")).join();

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(response.animationTriggerId()).isEqualTo("talk_warm");
        assertThat(response.replyText()).isNotBlank();
    }

    @Test
    void throwsAiSessionClosedExceptionOnHttp410Gone() {
        ExchangeFunction exchange = req -> Mono.just(
                ClientResponse.create(HttpStatus.GONE).body("session closed").build());

        RemoteAiClient client = createClient("secret-key-123", exchange);

        assertThatThrownBy(() -> client.requestTurn(new AiTurnRequest("session-uuid-410", "היי")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AiSessionClosedException.class);
    }

    private static RemoteAiClient createClient(String internalKey, ExchangeFunction exchange) {
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getAi().setBaseUrl("http://localhost:8081");
        properties.getAi().setTurnEndpoint("/api/v1/turn");
        properties.getAi().setTimeoutMs(2000);
        properties.getAi().setInternalServiceKey(internalKey);
        return new RemoteAiClient(properties, WebClient.builder().exchangeFunction(exchange));
    }
}
