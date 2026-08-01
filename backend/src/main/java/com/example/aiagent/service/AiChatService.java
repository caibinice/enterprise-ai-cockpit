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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelGateway modelGateway;
    private final ChatModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;
    private final McpToolService mcpToolService;
    private final ToolResultChartService toolResultChartService;
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
        this(
            repository,
            knowledgeBaseService,
            modelGateway,
            modelCatalog,
            objectMapper,
            mcpToolService.getIfAvailable()
        );
    }

    AiChatService(
        EnterpriseRepository repository,
        KnowledgeBaseService knowledgeBaseService,
        ModelGateway modelGateway,
        ChatModelCatalog modelCatalog,
        ObjectMapper objectMapper,
        McpToolService mcpToolService
    ) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
        this.mcpToolService = mcpToolService;
        this.toolResultChartService = new ToolResultChartService(objectMapper);
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
        this.toolResultChartService = new ToolResultChartService(objectMapper);
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
            Flux<StreamEvent> tools = Flux.fromIterable(context.toolResults())
                .map(result -> new StreamEvent("tool", json(result)));
            Flux<StreamEvent> tokens = modelGateway.streamAnswer(
                    context.modelQuestion(),
                    context.references(),
                    context.model()
                )
                .filter(token -> token != null && !token.isEmpty())
                .switchIfEmpty(Flux.defer(() -> Flux.just(retryOrFallbackAnswer(context))))
                .doOnNext(answer::append)
                .map(token -> new StreamEvent("token", token));
            Flux<StreamEvent> tail = Flux.defer(() -> {
                String finalAnswer = answer.toString();
                repository.saveChatMessage(context.conversationId(), "user", request.message());
                repository.saveChatMessage(context.conversationId(), "assistant", finalAnswer);
                List<StreamEvent> events = new ArrayList<>();
                events.add(new StreamEvent("references", json(context.references())));
                String chart = chart(request, context);
                if (chart != null) events.add(new StreamEvent("chart", chart));
                events.add(new StreamEvent("done", json(Map.of(
                    "conversationId",
                    context.conversationId(),
                    "model",
                    context.model()
                ))));
                return Flux.fromIterable(events);
            });
            return Flux.concat(meta, tools, tokens, tail);
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
        String chart = chart(request, context);
        return new ChatResponse(context.conversationId(), answer, modelGateway.enabled(), context.references(), chart);
    }

    private ChatContext prepare(ChatStreamRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
            ? UUID.randomUUID().toString() : request.conversationId();
        String model = modelCatalog.resolve(request.model());
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
        List<RetrievedKnowledgeChunk> references = isStandaloneWeatherQuestion(
            request.message(),
            toolResults
        )
            ? List.of()
            : knowledgeBaseService.search(
                request.message(),
                request.knowledgeBaseIds(),
                request.metadataFilter(),
                topK
            );
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
        if (toolResults != null && !toolResults.isEmpty()) {
            prompt.append("""
                本轮应用已执行的 MCP 工具结果（受信任的实时上下文）：
                - status=success 的结果优先于知识库，用它回答天气、时间等实时问题；不要声称知识库缺少实时信息。
                - status=error 表示工具暂时不可用，应明确说明服务不可用且不要编造实时数据。
                - status=ready 表示本轮没有触发该工具，不要假装已经调用。
                - 批量天气结果中的 cities 是完整结果集。若用户询问全部城市，必须覆盖全部城市并忠实使用其中数值；正文可用紧凑表格，并总结最高、最低与温差。
                - 若用户要求图表，应用会直接用同一份工具 JSON 绘图；正文不要声称无法画图，也不要自行编造图表数据。
                """);
            toolResults.forEach(result -> prompt
                .append("- ")
                .append(result.name())
                .append(" [status=")
                .append(result.status())
                .append("]：")
                .append(result.output())
                .append('\n'));
            prompt.append('\n');
        }
        if (history != null && !history.isEmpty()) {
            prompt.append("最近对话：\n");
            history.forEach(message -> prompt
                .append("assistant".equals(message.role()) ? "助手" : "用户")
                .append("：")
                .append(message.content())
                .append('\n'));
            prompt.append('\n');
        }
        prompt.append("当前问题：\n").append(question);
        return prompt.toString();
    }

    private String chart(ChatStreamRequest request, ChatContext context) {
        if (!toolResultChartService.shouldGenerate(request.message(), request.enableChart())) {
            return null;
        }
        if (toolResultChartService.hasWeatherResult(context.toolResults())) {
            return toolResultChartService.weatherChart(context.toolResults());
        }
        return modelGateway.chart(request.message(), context.references());
    }

    private String retryOrFallbackAnswer(ChatContext context) {
        log.warn(
            "Model {} completed its stream without content; retrying once without streaming",
            context.model()
        );
        String retried = modelGateway.answer(
            context.modelQuestion(),
            context.references(),
            context.model()
        );
        if (isUsableModelAnswer(retried)) return retried;
        String toolFallback = toolResultChartService.weatherAnswer(context.toolResults());
        if (toolFallback != null) {
            log.warn("Using deterministic weather answer after empty model stream and retry");
            return toolFallback;
        }
        return retried == null || retried.isBlank()
            ? "模型本轮未返回可展示内容，请重试或切换模型。"
            : retried;
    }

    private boolean isUsableModelAnswer(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return !containsAny(
            answer,
            "No matching enterprise knowledge base evidence",
            "模型服务暂时不可用，已切换为本地 RAG",
            "Local RAG summary"
        );
    }

    private boolean isStandaloneWeatherQuestion(
        String question,
        List<McpExecutionResult> toolResults
    ) {
        boolean weatherExecuted = toolResults != null && toolResults.stream()
            .anyMatch(result -> "weather".equals(result.id()) && !"ready".equals(result.status()));
        if (!weatherExecuted) return false;
        String text = question == null ? "" : question.toLowerCase();
        return !containsAny(
            text,
            "知识库", "文档", "制度", "策略", "业务", "报告", "研究", "风险",
            "量化", "结合", "根据", "参考", "compare with policy"
        );
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
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
