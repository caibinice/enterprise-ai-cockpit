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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "true")
public class OpenAiCompatibleModelGateway implements ModelGateway {
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MockModelGateway fallback;

    public OpenAiCompatibleModelGateway(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
            messages.add(Map.of("role", "system", "content", "???????????????????????????????????????"));
            messages.add(Map.of("role", "user", "content", "??????\n" + buildContext(references) + "\n?????" + question));
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
            return "???????????????????????" + ex.getMessage() + "\n\n" + fallback.answer(question, references);
        }
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        return fallback.chart(question, references);
    }

    private String buildContext(List<RetrievedKnowledgeChunk> references) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RetrievedKnowledgeChunk ref = references.get(i);
            sb.append("[?? ").append(i + 1).append(" | ").append(ref.title()).append("]\n")
              .append(ref.content()).append("\nmetadata=").append(ref.metadata()).append("\n\n");
        }
        return sb.toString();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StringUtils.hasText(baseUrl) ? baseUrl : "https://api.okinto.com/v1";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
