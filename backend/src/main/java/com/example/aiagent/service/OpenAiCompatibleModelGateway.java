package com.example.aiagent.service;

import com.example.aiagent.config.LlmProperties;
import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelGateway implements ModelGateway {
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final WebClient webClient;
    private final MockModelGateway fallback;

    public OpenAiCompatibleModelGateway(LlmProperties properties, ObjectMapper objectMapper, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.webClient = webClientBuilder.build();
        this.fallback = new MockModelGateway(objectMapper);
    }

    @Override
    public boolean enabled() {
        return properties.enabled() && StringUtils.hasText(properties.apiKey()) && !"demo-key".equals(properties.apiKey());
    }

    @Override
    public String answer(String question, List<RetrievedKnowledgeChunk> references) {
        if (!enabled()) return fallback.answer(question, references);
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You are an enterprise AI cockpit assistant. Prefer the retrieved knowledge base evidence. If evidence is insufficient, say so clearly. Answer in the user's requested language."));
            messages.add(Map.of("role", "user", "content", "Knowledge base evidence:\n" + buildContext(references) + "\nUser question:\n" + question));
            Map<String, Object> body = Map.of(
                "model", StringUtils.hasText(properties.model()) ? properties.model() : "gpt-5.4-mini",
                "messages", messages,
                "temperature", 0.2,
                "max_tokens", 1200
            );
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(properties.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : fallback.answer(question, references);
        } catch (Exception ex) {
            return "LLM call failed; falling back to local RAG summary. Error: " + ex.getMessage() + "\n\n" + fallback.answer(question, references);
        }
    }

    /**
     * Consumes the upstream provider's SSE response instead of splitting a completed answer locally.
     * DeepSeek exposes the same OpenAI-compatible /chat/completions stream contract.
     */
    @Override
    public Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references) {
        if (!enabled()) return fallback.streamAnswer(question, references);
        return Flux.defer(() -> webClient.post()
                .uri(normalizeBaseUrl(properties.baseUrl()) + "/chat/completions")
                .header("Authorization", "Bearer " + properties.apiKey())
                .bodyValue(streamBody(question, references))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(StringUtils::hasText)
                .takeUntil("[DONE]"::equals)
                .map(this::parseToken)
                .filter(StringUtils::hasText))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(ex -> Flux.just("LLM stream failed; using local RAG fallback. Error: " + ex.getMessage() + "\n\n" + fallback.answer(question, references)));
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        return fallback.chart(question, references);
    }

    private String buildContext(List<RetrievedKnowledgeChunk> references) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RetrievedKnowledgeChunk ref = references.get(i);
            sb.append("[Reference ").append(i + 1).append(" | ").append(ref.title()).append("]\n")
              .append(ref.content()).append("\nmetadata=").append(ref.metadata()).append("\n\n");
        }
        return sb.toString();
    }

    private Map<String, Object> streamBody(String question, List<RetrievedKnowledgeChunk> references) {
        return Map.of(
            "model", StringUtils.hasText(properties.model()) ? properties.model() : "deepseek-v4-flash",
            "messages", messages(question, references),
            "temperature", 0.2,
            "max_tokens", 1200,
            "stream", true
        );
    }

    private List<Map<String, String>> messages(String question, List<RetrievedKnowledgeChunk> references) {
        return List.of(
            Map.of("role", "system", "content", "You are an enterprise AI cockpit assistant. Prefer the retrieved knowledge base evidence. If evidence is insufficient, say so clearly. Answer in the user's requested language."),
            Map.of("role", "user", "content", "Knowledge base evidence:\n" + buildContext(references) + "\nUser question:\n" + question)
        );
    }

    private String parseToken(String data) {
        if ("[DONE]".equals(data)) return "";
        try {
            JsonNode content = objectMapper.readTree(data).path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StringUtils.hasText(baseUrl) ? baseUrl : "https://api.okinto.com/v1";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
