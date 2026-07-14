package com.example.aiagent.service;

import com.example.aiagent.model.ChatStreamRequest;
import com.example.aiagent.model.KnowledgeBaseRequest;
import com.example.aiagent.model.ReportTemplateRequest;
import com.example.aiagent.repository.InMemoryEnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
