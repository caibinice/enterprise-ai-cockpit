package com.example.aiagent.parking;

import com.example.aiagent.security.*;
import com.example.aiagent.controller.ApiExceptionHandler;
import com.example.aiagent.model.StreamEvent;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParkingAgentControllerTest {
    private final ParkingAgentService agent = mock(ParkingAgentService.class);
    private final ActionAuthService auth = new ActionAuthService("test-password", "test-secret-with-more-than-32-characters", 30);
    private final WebTestClient client = WebTestClient.bindToController(new ParkingAgentController(agent))
        .controllerAdvice(new ApiExceptionHandler()).webFilter(new ActionAuthWebFilter(auth)).build();
    @Test void publicCatalogIsReadOnlyAndBothPostRoutesRequireOperationToken() {
        when(agent.catalog()).thenReturn(Map.of("dataSource", "browser-demo"));
        client.get().uri("/api/parking-agent/catalog").exchange().expectStatus().isOk();
        for (String route : List.of("/api/parking-agent/stream", "/api/parking-agent/knowledge/bootstrap"))
            client.post().uri(route).bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
    }
    @Test void authorizedBootstrapAndStreamReuseExistingToken() throws Exception {
        String token = "Bearer " + auth.verifyAndIssue("test-password");
        when(agent.bootstrap()).thenReturn(Map.of("documents", 5));
        when(agent.stream(any())).thenReturn(Flux.just(new StreamEvent("answer", "{\"text\":\"hello\"}"), new StreamEvent("done", "[DONE]")));
        client.post().uri("/api/parking-agent/knowledge/bootstrap").header("Authorization", token)
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.documents").isEqualTo(5);
        client.post().uri("/api/parking-agent/stream").header("Authorization", token).bodyValue(validBody())
            .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith("text/event-stream");
    }
    @Test void requestValidationRejectsMissingContextAndUnboundedInput() {
        String token = "Bearer " + auth.verifyAndIssue("test-password");
        client.post().uri("/api/parking-agent/stream").header("Authorization", token)
            .bodyValue(Map.of("message", "hello")).exchange().expectStatus().isBadRequest();
        var body = new HashMap<>(validBody()); body.put("message", "x".repeat(2001));
        client.post().uri("/api/parking-agent/stream").header("Authorization", token).bodyValue(body)
            .exchange().expectStatus().isBadRequest();
        verify(agent, never()).stream(any());
    }
    private Map<String, Object> validBody() {
        return Map.of("message", "查看停车报表", "context", Map.of("source", "browser-demo", "observedAt", Instant.now().toString(),
            "sceneReady", true, "zones", List.of(Map.of("id", "A", "capacity", 120, "occupied", 86),
            Map.of("id", "B", "capacity", 100, "occupied", 62), Map.of("id", "C", "capacity", 80, "occupied", 41)),
            "events", List.of(), "alerts", List.of()));
    }
}
