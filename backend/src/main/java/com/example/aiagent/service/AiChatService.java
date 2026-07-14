package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class AiChatService {
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final McpWeatherService mcpWeatherService;
    @Value("${app.rag.top-k:6}")
    private int topK = 6;

    @Autowired
    public AiChatService(
        EnterpriseRepository repository,
        KnowledgeBaseService knowledgeBaseService,
        ModelGateway modelGateway,
        ObjectMapper objectMapper,
        ObjectProvider<McpWeatherService> mcpWeatherService
    ) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.mcpWeatherService = mcpWeatherService.getIfAvailable();
    }

    /** Compatibility helper for service-level tests. */
    public AiChatService(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService,
                         ModelGateway modelGateway, ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.mcpWeatherService = null;
    }

    /**
     * Legacy collection API retained for tests and callers that need to buffer
     * the stream. The HTTP endpoint uses streamReactive() directly.
     */
    public List<StreamEvent> stream(ChatStreamRequest request) {
        List<StreamEvent> events = streamReactive(request).collectList().block();
        return events == null ? List.of() : events;
    }

    /** True reactive chain: retrieval and model work are deferred to a bounded elastic scheduler. */
    public Flux<StreamEvent> streamReactive(ChatStreamRequest request) {
        return Flux.defer(() -> {
            ChatContext context = prepare(request);
            StringBuilder answer = new StringBuilder();
            Flux<StreamEvent> meta = Flux.just(new StreamEvent("meta", json(MapLike.of(
                "conversationId", context.conversationId(), "llmEnabled", modelGateway.enabled()))));
            Flux<StreamEvent> tokens = modelGateway.streamAnswer(context.modelQuestion(), context.references())
                .filter(token -> token != null && !token.isEmpty())
                .doOnNext(answer::append)
                .map(token -> new StreamEvent("token", token));
            Flux<StreamEvent> tail = Flux.defer(() -> {
                String finalAnswer = answer.toString();
                repository.saveChatMessage(context.conversationId(), "user", request.message());
                repository.saveChatMessage(context.conversationId(), "assistant", finalAnswer);
                List<StreamEvent> events = new ArrayList<>();
                events.add(new StreamEvent("references", json(context.references())));
                if (request.enableTools()) {
                    events.add(new StreamEvent("tool", context.toolMessage() == null || context.toolMessage().isBlank()
                        ? "Internal tools enabled: report lookup, data-source snapshot, and safe read-only short query."
                        : context.toolMessage()));
                }
                if (request.enableChart()) events.add(new StreamEvent("chart", modelGateway.chart(request.message(), context.references())));
                events.add(new StreamEvent("done", json(MapLike.of("conversationId", context.conversationId()))));
                return Flux.fromIterable(events);
            });
            return Flux.concat(meta, tokens, tail);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public ChatResponse chat(ChatStreamRequest request) {
        ChatContext context = prepare(request);
        String answer = modelGateway.answer(context.modelQuestion(), context.references());
        repository.saveChatMessage(context.conversationId(), "user", request.message());
        repository.saveChatMessage(context.conversationId(), "assistant", answer);
        String chart = request.enableChart() ? modelGateway.chart(request.message(), context.references()) : null;
        return new ChatResponse(context.conversationId(), answer, modelGateway.enabled(), context.references(), chart);
    }

    private ChatContext prepare(ChatStreamRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
            ? UUID.randomUUID().toString() : request.conversationId();
        List<RetrievedKnowledgeChunk> references = knowledgeBaseService.search(request.message(), request.knowledgeBaseIds(), request.metadataFilter(), topK);
        String toolMessage = null;
        if (request.enableTools() && mcpWeatherService != null) {
            try {
                toolMessage = mcpWeatherService.queryIfRequested(request.message());
            } catch (RuntimeException ex) {
                toolMessage = "MCP weather call failed: " + ex.getMessage();
            }
        }
        String modelQuestion = toolMessage == null || toolMessage.isBlank()
            ? request.message()
            : request.message() + "\n\n" + toolMessage;
        return new ChatContext(conversationId, references, modelQuestion, toolMessage);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { return String.valueOf(value); }
    }

    private record ChatContext(String conversationId, List<RetrievedKnowledgeChunk> references, String modelQuestion, String toolMessage) {}
    private record MapLike() {
        static java.util.Map<String, Object> of(String k1, Object v1) { return java.util.Map.of(k1, v1); }
        static java.util.Map<String, Object> of(String k1, Object v1, String k2, Object v2) { return java.util.Map.of(k1, v1, k2, v2); }
    }
}
