package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ChatModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;
    private final McpToolService mcpToolService;
    @Value("${app.rag.top-k:6}")
    private int topK = 6;

    @Autowired
    public AiChatService(
        EnterpriseRepository repository,
        KnowledgeBaseService knowledgeBaseService,
        ModelGateway modelGateway,
        ChatModelCatalog modelCatalog,
        ObjectMapper objectMapper,
        ObjectProvider<McpToolService> mcpToolService
    ) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
        this.mcpToolService = mcpToolService.getIfAvailable();
    }

    /** Compatibility helper for service-level tests. */
    public AiChatService(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService,
                         ModelGateway modelGateway, ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.modelCatalog = new ChatModelCatalog(
            new com.example.aiagent.config.LlmProperties(
                false,
                "mock",
                "",
                "",
                ChatModelCatalog.FLASH
            )
        );
        this.objectMapper = objectMapper;
        this.mcpToolService = null;
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
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("conversationId", context.conversationId());
            metadata.put("llmEnabled", modelGateway.enabled());
            metadata.put("model", context.model());
            metadata.put("knowledgeBaseCount", request.knowledgeBaseIds() == null ? 0 : request.knowledgeBaseIds().size());
            metadata.put("mcpToolIds", request.mcpToolIds() == null ? List.of() : request.mcpToolIds());
            Flux<StreamEvent> meta = Flux.just(new StreamEvent("meta", json(metadata)));
            Flux<StreamEvent> tokens = modelGateway.streamAnswer(
                    context.modelQuestion(),
                    context.references(),
                    context.model()
                )
                .filter(token -> token != null && !token.isEmpty())
                .doOnNext(answer::append)
                .map(token -> new StreamEvent("token", token));
            Flux<StreamEvent> tail = Flux.defer(() -> {
                String finalAnswer = answer.toString();
                repository.saveChatMessage(context.conversationId(), "user", request.message());
                repository.saveChatMessage(context.conversationId(), "assistant", finalAnswer);
                List<StreamEvent> events = new ArrayList<>();
                events.add(new StreamEvent("references", json(context.references())));
                context.toolResults().forEach(result ->
                    events.add(new StreamEvent("tool", json(result))));
                if (request.enableChart()) events.add(new StreamEvent("chart", modelGateway.chart(request.message(), context.references())));
                events.add(new StreamEvent("done", json(Map.of(
                    "conversationId",
                    context.conversationId(),
                    "model",
                    context.model()
                ))));
                return Flux.fromIterable(events);
            });
            return Flux.concat(meta, tokens, tail);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public ChatResponse chat(ChatStreamRequest request) {
        ChatContext context = prepare(request);
        String answer = modelGateway.answer(
            context.modelQuestion(),
            context.references(),
            context.model()
        );
        repository.saveChatMessage(context.conversationId(), "user", request.message());
        repository.saveChatMessage(context.conversationId(), "assistant", answer);
        String chart = request.enableChart() ? modelGateway.chart(request.message(), context.references()) : null;
        return new ChatResponse(context.conversationId(), answer, modelGateway.enabled(), context.references(), chart);
    }

    private ChatContext prepare(ChatStreamRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
            ? UUID.randomUUID().toString() : request.conversationId();
        String model = modelCatalog.resolve(request.model());
        List<RetrievedKnowledgeChunk> references = knowledgeBaseService.search(request.message(), request.knowledgeBaseIds(), request.metadataFilter(), topK);
        List<String> selectedToolIds = request.mcpToolIds() == null
            ? List.of()
            : request.mcpToolIds();
        if (request.enableTools() && selectedToolIds.isEmpty()) {
            selectedToolIds = List.of("weather");
        }
        List<McpExecutionResult> toolResults =
            request.enableTools() && mcpToolService != null
                ? mcpToolService.executeSelected(request.message(), selectedToolIds)
                : List.of();
        List<ConversationMessage> history = repository.findChatMessages(
            conversationId,
            8
        );
        String modelQuestion = buildQuestion(history, request.message(), toolResults);
        return new ChatContext(
            conversationId,
            model,
            references,
            modelQuestion,
            toolResults
        );
    }

    private String buildQuestion(
        List<ConversationMessage> history,
        String question,
        List<McpExecutionResult> toolResults
    ) {
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            prompt.append("最近对话：\n");
            history.forEach(message -> prompt
                .append("assistant".equals(message.role()) ? "助手" : "用户")
                .append("：")
                .append(message.content())
                .append('\n'));
            prompt.append('\n');
        }
        List<McpExecutionResult> successfulTools = toolResults.stream()
            .filter(result -> "success".equals(result.status()))
            .toList();
        if (!successfulTools.isEmpty()) {
            prompt.append("本轮 MCP 工具结果：\n");
            successfulTools.forEach(result -> prompt
                .append("- ")
                .append(result.name())
                .append("：")
                .append(result.output())
                .append('\n'));
            prompt.append('\n');
        }
        prompt.append("当前问题：\n").append(question);
        return prompt.toString();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { return String.valueOf(value); }
    }

    private record ChatContext(
        String conversationId,
        String model,
        List<RetrievedKnowledgeChunk> references,
        String modelQuestion,
        List<McpExecutionResult> toolResults
    ) {}
}
