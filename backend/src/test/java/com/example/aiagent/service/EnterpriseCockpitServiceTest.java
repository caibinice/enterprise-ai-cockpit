package com.example.aiagent.service;

import com.example.aiagent.model.ChatStreamRequest;
import com.example.aiagent.model.KnowledgeBaseRequest;
import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.ReportTemplateRequest;
import com.example.aiagent.repository.InMemoryEnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseCockpitServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void importsDocumentsAndFiltersRetrievalByMetadata() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        long kbId = knowledgeBaseService.createKnowledgeBase(new KnowledgeBaseRequest("Operations KB", "Operations analysis", "OPS"));

        knowledgeBaseService.importDocument(kbId, "monthly-sales.md", "Sales revenue increased 18 percent. East region contributed the most.", Map.of("category", "sales", "region", "east"));
        knowledgeBaseService.importDocument(kbId, "hr.md", "Hiring cycle shortened and training completion improved.", Map.of("category", "hr"));

        var salesHits = knowledgeBaseService.search("sales revenue", List.of(kbId), Map.of("category", "sales"), 5);

        assertThat(salesHits).hasSize(1);
        assertThat(salesHits.get(0).title()).isEqualTo("monthly-sales.md");
        assertThat(salesHits.get(0).metadata()).containsEntry("region", "east");
        assertThat(knowledgeBaseService.list().get(0).businessType()).isEqualTo("通用业务");
    }

    @Test
    void rejectsEmptyDocumentsAndSynchronizesVectorLifecycle() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        TrackingVectorIndex vectorIndex = new TrackingVectorIndex();
        knowledgeBaseService.setVectorIndexService(vectorIndex);
        long kbId = knowledgeBaseService.createKnowledgeBase(new KnowledgeBaseRequest("Vector KB", "", "VECTOR"));

        assertThatThrownBy(() -> knowledgeBaseService.importDocument(kbId, "empty.md", "  \r\n ", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Document content is empty");

        var document = knowledgeBaseService.importDocument(kbId, "vector.md", "single vector chunk", Map.of("stage", "one"));
        assertThat(vectorIndex.upserted).hasSize(1);

        knowledgeBaseService.updateMetadata(document.id(), Map.of("stage", "two"));
        assertThat(vectorIndex.upserted).hasSize(2);

        knowledgeBaseService.deleteDocument(document.id());
        assertThat(vectorIndex.deleted).containsExactly(vectorIndex.upserted.get(0).id());
    }

    @Test
    void reportRunCreatesChartAndSearchableKnowledgeDocument() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        ReportService reportService = new ReportService(repository, knowledgeBaseService, objectMapper);
        long kbId = knowledgeBaseService.createKnowledgeBase(new KnowledgeBaseRequest("Report KB", "Automated reports", "REPORT"));

        var run = reportService.runTemplate(new ReportTemplateRequest(
            "Sales Daily", "CRON", "0 0 9 * * ?", "mock-sales", kbId,
            "Analyze sales trend", "region,amount", true));

        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.chartSpec()).contains("xAxis").contains("series");
        assertThat(knowledgeBaseService.search("Sales Daily East", List.of(kbId), Map.of("report", "true"), 5)).isNotEmpty();
    }

    @Test
    void chatStreamReturnsTokenReferencesChartAndDoneEvents() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        AiChatService chatService = new AiChatService(repository, knowledgeBaseService, new MockModelGateway(objectMapper), objectMapper);
        long kbId = knowledgeBaseService.createKnowledgeBase(new KnowledgeBaseRequest("Cockpit KB", "", "CHAT"));
        knowledgeBaseService.importDocument(kbId, "sales.md", "East sales amount is 120 and South sales amount is 95.", Map.of("category", "sales"));

        var events = chatService.stream(new ChatStreamRequest(null, "Generate sales chart", List.of(kbId), Map.of("category", "sales"), true, true));

        assertThat(events).extracting("event").containsSubsequence("token", "references", "chart", "done");
        assertThat(events.stream().filter(e -> e.event().equals("chart")).findFirst().orElseThrow().data()).contains("echarts");
    }

    @Test
    void chatUsesSelectedModelAndRecentConversationHistory() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        CapturingModelGateway gateway = new CapturingModelGateway();
        AiChatService chatService = new AiChatService(
            repository,
            knowledgeBaseService,
            gateway,
            objectMapper
        );
        long kbId = knowledgeBaseService.createKnowledgeBase(
            new KnowledgeBaseRequest("Support KB", "", "SUPPORT", "客户服务")
        );
        knowledgeBaseService.importDocument(
            kbId,
            "refund.md",
            "退款申请应在签收后七天内提交。",
            Map.of("category", "policy")
        );

        chatService.chat(new ChatStreamRequest(
            "conversation-1",
            "退款期限是什么？",
            ChatModelCatalog.FLASH,
            List.of(kbId),
            Map.of(),
            List.of(),
            false,
            false
        ));
        chatService.chat(new ChatStreamRequest(
            "conversation-1",
            "刚才的规则适用于谁？",
            ChatModelCatalog.PRO,
            List.of(kbId),
            Map.of(),
            List.of(),
            false,
            false
        ));

        assertThat(gateway.lastModel).isEqualTo(ChatModelCatalog.PRO);
        assertThat(gateway.lastQuestion)
            .contains("最近对话")
            .contains("退款期限是什么？")
            .contains("当前问题")
            .contains("刚才的规则适用于谁？");
    }

    @Test
    void chatPrioritizesMcpResultsAndEmitsToolBeforeTokens() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        CapturingModelGateway gateway = new CapturingModelGateway();
        McpToolService mcp = mock(McpToolService.class);
        when(mcp.executeSelected(any(), any())).thenReturn(List.of(
            new McpExecutionResult(
                "weather",
                "实时天气",
                "success",
                "{\"city\":\"常州\",\"temperatureC\":33.6,\"source\":\"Open-Meteo\"}"
            )
        ));
        AiChatService chatService = new AiChatService(
            repository,
            knowledgeBaseService,
            gateway,
            new ChatModelCatalog(new com.example.aiagent.config.LlmProperties(
                true,
                "openai-compatible",
                "https://example.test",
                "test-key",
                ChatModelCatalog.FLASH
            )),
            objectMapper,
            mcp
        );
        ChatStreamRequest request = new ChatStreamRequest(
            null,
            "今天气怎么样",
            ChatModelCatalog.FLASH,
            List.of(),
            Map.of(),
            List.of("weather"),
            true,
            false
        );

        var events = chatService.stream(request);

        assertThat(events).extracting("event")
            .containsSubsequence("meta", "tool", "token", "references", "done");
        assertThat(gateway.lastQuestion)
            .contains("status=success")
            .contains("实时上下文")
            .contains("优先于知识库")
            .contains("Open-Meteo")
            .contains("今天气怎么样");
    }

    @Test
    void chatExplainsToolFailureWithoutBlamingTheKnowledgeBase() {
        InMemoryEnterpriseRepository repository = new InMemoryEnterpriseRepository(objectMapper);
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(repository, objectMapper);
        CapturingModelGateway gateway = new CapturingModelGateway();
        McpToolService mcp = mock(McpToolService.class);
        when(mcp.executeSelected(any(), any())).thenReturn(List.of(
            new McpExecutionResult(
                "weather",
                "实时天气",
                "error",
                "实时天气暂时不可用，已安全降级，请稍后重试。"
            )
        ));
        AiChatService chatService = new AiChatService(
            repository,
            knowledgeBaseService,
            gateway,
            new ChatModelCatalog(new com.example.aiagent.config.LlmProperties(
                true,
                "openai-compatible",
                "https://example.test",
                "test-key",
                ChatModelCatalog.FLASH
            )),
            objectMapper,
            mcp
        );

        chatService.chat(new ChatStreamRequest(
            null,
            "今天气怎么样",
            ChatModelCatalog.FLASH,
            List.of(),
            Map.of(),
            List.of("weather"),
            true,
            false
        ));

        assertThat(gateway.lastQuestion)
            .contains("status=error")
            .contains("工具暂时不可用")
            .contains("不要编造实时数据")
            .contains("实时天气暂时不可用");
    }

    @Test
    void speechMockRoundTripWorksWithoutProviderKey() {
        SpeechService speechService = new SpeechService(objectMapper);

        var text = speechService.transcribe("audio/webm", "sales situation".getBytes(StandardCharsets.UTF_8));
        var audio = speechService.synthesize("sales increased");

        assertThat(text.text()).contains("sales situation");
        assertThat(audio.audioUrl()).startsWith("data:audio/wav;base64,");
    }

    @Test
    void chartSpecRemainsValidWhenTitleContainsJsonCharacters() throws Exception {
        var chart = new MockModelGateway(objectMapper).chart("Sales \"Q1\"\ntrend", List.of());

        assertThat(objectMapper.readTree(chart).path("title").path("text").asText()).isEqualTo("Sales \"Q1\"\ntrend");
    }

    private static final class TrackingVectorIndex implements VectorIndexService {
        private final List<com.example.aiagent.model.RetrievedKnowledgeChunk> upserted = new ArrayList<>();
        private final List<Long> deleted = new ArrayList<>();

        @Override public boolean enabled() { return true; }
        @Override public void upsert(com.example.aiagent.model.RetrievedKnowledgeChunk chunk) { upserted.add(chunk); }
        @Override public void delete(long chunkId) { deleted.add(chunkId); }
        @Override public List<com.example.aiagent.model.RetrievedKnowledgeChunk> search(String query, List<Long> knowledgeBaseIds, int topK) { return List.of(); }
        @Override public String status() { return "test"; }
    }

    private static final class CapturingModelGateway implements ModelGateway {
        private String lastQuestion;
        private String lastModel;

        @Override public boolean enabled() { return true; }

        @Override
        public String answer(
            String question,
            List<com.example.aiagent.model.RetrievedKnowledgeChunk> references,
            String model
        ) {
            lastQuestion = question;
            lastModel = model;
            return "captured";
        }

        @Override
        public Flux<String> streamAnswer(
            String question,
            List<com.example.aiagent.model.RetrievedKnowledgeChunk> references,
            String model
        ) {
            return Flux.just(answer(question, references, model));
        }

        @Override
        public String chart(
            String question,
            List<com.example.aiagent.model.RetrievedKnowledgeChunk> references
        ) {
            return "{}";
        }
    }
}
