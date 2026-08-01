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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelGateway implements ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class);
    private final LlmProperties properties;
    private final ChatModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final WebClient webClient;
    private final MockModelGateway fallback;

    public OpenAiCompatibleModelGateway(
        LlmProperties properties,
        ChatModelCatalog modelCatalog,
        ObjectMapper objectMapper,
        WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.modelCatalog = modelCatalog;
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
    public String provider() {
        return enabled() ? "openai-compatible" : "local-rag";
    }

    @Override
    public String answer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        String selectedModel = modelCatalog.resolve(model);
        if (!enabled()) return fallback.answer(question, references, selectedModel);
        try {
            Map<String, Object> body = Map.of(
                "model", selectedModel,
                "messages", messages(question, references),
                "temperature", 0.2,
                "max_tokens", maxTokens(selectedModel)
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
            return content.isTextual() && !content.asText().isBlank()
                ? content.asText()
                : fallback.answer(question, references, selectedModel);
        } catch (Exception ex) {
            log.warn("OpenAI-compatible chat request failed: {}", ex.getMessage());
            return "模型服务暂时不可用，已切换为本地 RAG 摘要。\n\n"
                + fallback.answer(question, references, selectedModel);
        }
    }

    /**
     * Consumes the upstream provider's SSE response instead of splitting a completed answer locally.
     * DeepSeek exposes the same OpenAI-compatible /chat/completions stream contract.
     */
    @Override
    public Flux<String> streamAnswer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        String selectedModel = modelCatalog.resolve(model);
        if (!enabled()) return fallback.streamAnswer(question, references, selectedModel);
        return Flux.defer(() -> webClient.post()
                .uri(normalizeBaseUrl(properties.baseUrl()) + "/chat/completions")
                .header("Authorization", "Bearer " + properties.apiKey())
                .bodyValue(streamBody(question, references, selectedModel))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(StringUtils::hasText)
                .takeUntil("[DONE]"::equals)
                .map(this::parseToken)
                .filter(StringUtils::hasText))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(ex -> {
                log.warn("OpenAI-compatible chat stream failed: {}", ex.getMessage());
                return Flux.just(
                    "模型流式服务暂时不可用，已切换为本地 RAG 摘要。\n\n"
                        + fallback.answer(question, references, selectedModel)
                );
            });
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        return fallback.chart(question, references);
    }

    @Override
    public String jsonAnswer(
        String systemPrompt,
        String userPrompt,
        String model,
        int requestedMaxTokens
    ) {
        String selectedModel = modelCatalog.resolve(model);
        if (!enabled()) {
            return fallback.jsonAnswer(systemPrompt, userPrompt, selectedModel, requestedMaxTokens);
        }
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        );
        int outputBudget = Math.max(256, Math.min(requestedMaxTokens, maxTokens(selectedModel)));
        try {
            return completeJson(messages, selectedModel, outputBudget, true);
        } catch (Exception jsonModeError) {
            log.info(
                "Provider rejected JSON response mode for {}; retrying with prompt-only JSON contract: {}",
                selectedModel,
                jsonModeError.getMessage()
            );
            try {
                return completeJson(messages, selectedModel, outputBudget, false);
            } catch (Exception retryError) {
                log.warn("Structured model request failed for {}: {}", selectedModel, retryError.getMessage());
                return "";
            }
        }
    }

    private String completeJson(
        List<Map<String, String>> messages,
        String model,
        int outputBudget,
        boolean jsonMode
    ) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", outputBudget);
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(normalizeBaseUrl(properties.baseUrl()) + "/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode content = message.path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            int reasoningChars = message.path("reasoning_content").asText("").length();
            throw new IllegalStateException(
                "Provider returned an empty structured response"
                    + " (finishReason=" + choice.path("finish_reason").asText("unknown")
                    + ", reasoningChars=" + reasoningChars + ")"
            );
        }
        return content.asText();
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

    private Map<String, Object> streamBody(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        return Map.of(
            "model", model,
            "messages", messages(question, references),
            "temperature", 0.2,
            "max_tokens", maxTokens(model),
            "stream", true
        );
    }

    private List<Map<String, String>> messages(String question, List<RetrievedKnowledgeChunk> references) {
        return List.of(
            Map.of("role", "system", "content", ModelPromptPolicy.SYSTEM_PROMPT),
            Map.of("role", "user", "content", "知识库证据：\n" + buildContext(references) + "\n用户问题：\n" + question)
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

    private int maxTokens(String model) {
        return ChatModelCatalog.PRO.equals(model) ? 16384 : 8192;
    }
}
