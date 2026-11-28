package com.team5.voiceorchestrator.ai;

import com.team5.voiceorchestrator.config.OrchestratorProperties;
import com.team5.voiceorchestrator.contracts.AiTurnRequest;
import com.team5.voiceorchestrator.contracts.AiTurnResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Production {@link AiClient} communicating with Team 3's AI conversation service
 * ({@code POST /api/v1/turn}, Contract 4).
 * <p>
 * Sends {@code X-Internal-Service-Key} when configured, performs a single attempt without
 * automatic retries to avoid duplicate turns, maps HTTP 410 Gone to {@link AiSessionClosedException},
 * and falls back to a safe Hebrew response on transient errors or timeouts.
 */
@Service
@Profile("!mock")
public class RemoteAiClient implements AiClient {

    public static final String INTERNAL_SERVICE_KEY_HEADER = "X-Internal-Service-Key";

    private static final Logger log = LoggerFactory.getLogger(RemoteAiClient.class);
    private static final AiTurnResponse SAFE_FALLBACK = new AiTurnResponse(
            "אני כאן איתך, אפשר להגיד לי את זה שוב?",
            Map.of("emotion", "calm", "pace", "slow"),
            "talk_warm"
    );

    private final OrchestratorProperties properties;
    private final WebClient webClient;

    public RemoteAiClient(OrchestratorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public CompletableFuture<AiTurnResponse> requestTurn(AiTurnRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return CompletableFuture.completedFuture(SAFE_FALLBACK);
        }

        String url = buildTurnUrl();
        long timeoutMs = Math.max(500L, properties.getAi().getTimeoutMs());
        String serviceKey = properties.getAi().getInternalServiceKey();

        WebClient.RequestBodySpec spec = webClient.post()
                .uri(url)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .accept(MediaType.APPLICATION_JSON);

        if (serviceKey != null && !serviceKey.isBlank()) {
            spec = spec.header(INTERNAL_SERVICE_KEY_HEADER, serviceKey);
        }

        return spec.bodyValue(request)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.GONE.value()) {
                        return response.releaseBody()
                                .then(Mono.error(new AiSessionClosedException(request.sessionId())));
                    }
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    log.warn("Team 3 AI returned HTTP {} for session '{}': {}",
                                            response.statusCode().value(), request.sessionId(), body);
                                    return Mono.just(SAFE_FALLBACK);
                                });
                    }
                    return response.bodyToMono(AiTurnResponse.class)
                            .map(res -> (res == null || res.replyText() == null || res.replyText().isBlank())
                                    ? SAFE_FALLBACK
                                    : res);
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(err -> {
                    if (err instanceof AiSessionClosedException) {
                        return Mono.error(err);
                    }
                    log.warn("Team 3 AI request failed for session '{}' (no retry; using fallback): {}",
                            request.sessionId(), err.toString());
                    return Mono.just(SAFE_FALLBACK);
                })
                .toFuture();
    }

    private String buildTurnUrl() {
        String baseUrl = properties.getAi().getBaseUrl();
        String endpoint = properties.getAi().getTurnEndpoint();
        if (baseUrl.endsWith("/") && endpoint.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + endpoint;
        }
        if (!baseUrl.endsWith("/") && !endpoint.startsWith("/")) {
            return baseUrl + "/" + endpoint;
        }
        return baseUrl + endpoint;
    }
}
