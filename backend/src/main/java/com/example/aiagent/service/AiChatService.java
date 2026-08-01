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
    private final AgentToolOrchestrator agentToolOrchestrator;
    private final StructuredAgentResponseService structuredResponseService;
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
        this.agentToolOrchestrator = new AgentToolOrchestrator(
            mcpToolService,
            modelGateway,
            objectMapper
        );
        this.structuredResponseService = new StructuredAgentResponseService(
            objectMapper,
            modelGateway
        );
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
        this.agentToolOrchestrator = new AgentToolOrchestrator(null, modelGateway, objectMapper);
        this.structuredResponseService = new StructuredAgentResponseService(objectMapper, modelGateway);
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
            boolean chartRequested = toolResultChartService.shouldGenerate(
                request.message(),
                request.enableChart()
            );
            StructuredAgentResponseService.Result generated = structuredResponseService.compose(
                context.modelQuestion(),
                context.references(),
                context.toolResults(),
                context.model(),
                chartRequested
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("conversationId", context.conversationId());
            metadata.put("llmEnabled", modelGateway.enabled());
            metadata.put("model", context.model());
            metadata.put("knowledgeBaseCount", request.knowledgeBaseIds() == null ? 0 : request.knowledgeBaseIds().size());
            metadata.put("mcpToolIds", request.mcpToolIds() == null ? List.of() : request.mcpToolIds());
            metadata.put("agenticToolPlanning", true);
            Flux<StreamEvent> meta = Flux.just(new StreamEvent("meta", json(metadata)));
            Flux<StreamEvent> plan = context.planSummary().isBlank()
                ? Flux.empty()
                : Flux.just(new StreamEvent("plan", json(Map.of("summary", context.planSummary()))));
            Flux<StreamEvent> tools = Flux.fromIterable(context.toolResults())
                .map(result -> new StreamEvent("tool", json(result)));
            Flux<StreamEvent> tokens = Flux.fromIterable(chunk(generated.answer(), 72))
                .map(token -> new StreamEvent("token", token));
            Flux<StreamEvent> tail = Flux.defer(() -> {
                repository.saveChatMessage(context.conversationId(), "user", request.message());
                repository.saveChatMessage(context.conversationId(), "assistant", generated.answer());
                List<StreamEvent> events = new ArrayList<>();
                events.add(new StreamEvent("references", json(context.references())));
                generated.chartSpecs().forEach(chart -> events.add(new StreamEvent("chart", chart)));
                events.add(new StreamEvent("done", json(Map.of(
                    "conversationId",
                    context.conversationId(),
                    "model",
                    context.model()
                ))));
                return Flux.fromIterable(events);
            });
            return Flux.concat(meta, plan, tools, tokens, tail);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public ChatResponse chat(ChatStreamRequest request) {
        ChatContext context = prepare(request);
        StructuredAgentResponseService.Result generated = structuredResponseService.compose(
            context.modelQuestion(),
            context.references(),
            context.toolResults(),
            context.model(),
            toolResultChartService.shouldGenerate(request.message(), request.enableChart())
        );
        repository.saveChatMessage(context.conversationId(), "user", request.message());
        repository.saveChatMessage(context.conversationId(), "assistant", generated.answer());
        String firstChart = generated.chartSpecs().isEmpty() ? null : generated.chartSpecs().get(0);
        return new ChatResponse(
            context.conversationId(),
            generated.answer(),
            modelGateway.enabled(),
            context.references(),
            firstChart
        );
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
        AgentToolOrchestrator.Result orchestration = request.enableTools() && mcpToolService != null
            ? agentToolOrchestrator.execute(request.message(), selectedToolIds, model)
            : new AgentToolOrchestrator.Result("", List.of());
        List<McpExecutionResult> toolResults = orchestration.toolResults();
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
        String modelQuestion = buildQuestion(
            history,
            request.message(),
            orchestration.summary(),
            toolResults
        );
        return new ChatContext(
            conversationId,
            model,
            references,
            modelQuestion,
            toolResults,
            orchestration.summary()
        );
    }

    private String buildQuestion(
        List<ConversationMessage> history,
        String question,
        String planSummary,
        List<McpExecutionResult> toolResults
    ) {
        StringBuilder prompt = new StringBuilder();
        if (planSummary != null && !planSummary.isBlank()) {
            prompt.append("智能体工具规划摘要：").append(planSummary).append("\n\n");
        }
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

    private List<String> chunk(String answer, int size) {
        if (answer == null || answer.isEmpty()) return List.of("");
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < answer.length(); start += size) {
            chunks.add(answer.substring(start, Math.min(answer.length(), start + size)));
        }
        return chunks;
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
        List<McpExecutionResult> toolResults,
        String planSummary
    ) {}
}
