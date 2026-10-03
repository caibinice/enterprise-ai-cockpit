package com.example.aiagent.parking;

import com.example.aiagent.config.LlmProperties;
import com.example.aiagent.model.*;
import com.example.aiagent.repository.InMemoryEnterpriseRepository;
import com.example.aiagent.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParkingAgentServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeBaseService knowledge = new KnowledgeBaseService(new InMemoryEnterpriseRepository(json), json);
    private final ModelGateway model = mock(ModelGateway.class);
    private final ParkingAgentService agent = new ParkingAgentService(knowledge, model,
        new ChatModelCatalog(new LlmProperties(false, "mock", "", "", ChatModelCatalog.FLASH)), json);

    private ParkingAgentRequest.Context context() {
        return new ParkingAgentRequest.Context("browser-demo", Instant.now().toString(), true,
            List.of(new ParkingAgentRequest.Zone("A", 120, 86), new ParkingAgentRequest.Zone("B", 100, 62),
                new ParkingAgentRequest.Zone("C", 80, 41)), List.of(),
            List.of(new ParkingAgentRequest.Alert(1, "C", "通道停留提醒", "高", false)), "overview", "");
    }
    private ParkingAgentRequest request(String text) { return new ParkingAgentRequest(text, ChatModelCatalog.FLASH, context(), List.of()); }

    @Test void provisioningIsIdempotentAndIsolatedFromOtherKnowledge() throws Exception {
        knowledge.createKnowledgeBase(new KnowledgeBaseRequest("人力资源", "私人资料", "HR"));
        assertThat(agent.bootstrap()).containsEntry("imported", 5).containsEntry("documents", 5);
        assertThat(agent.bootstrap()).containsEntry("imported", 0).containsEntry("documents", 5);
        assertThat(knowledge.list()).hasSize(2);
    }
    @Test void reportsAreComputedFromSnapshotNotLanguageModel() throws Exception {
        var output = agent.respond(request("查看停车报表"), ChatModelCatalog.FLASH);
        var report = json.readTree(output.stream().filter(e -> e.event().equals("report")).findFirst().orElseThrow().data());
        assertThat(report.path("data").path("free").asInt()).isEqualTo(111);
        assertThat(report.path("data").path("rate").asInt()).isEqualTo(63);
        assertThat(report.path("source").asText()).isEqualTo("browser-demo");
        verifyNoInteractions(model);
    }
    @Test void recommendationLocatesBestZoneAndProvidesExactCount() throws Exception {
        var output = agent.respond(request("推荐停车区"), ChatModelCatalog.FLASH);
        var action = json.readTree(output.stream().filter(e -> e.event().equals("action")).findFirst().orElseThrow().data());
        assertThat(action.path("target").asText()).isEqualTo("C");
        var report = json.readTree(output.stream().filter(e -> e.event().equals("report")).findFirst().orElseThrow().data());
        assertThat(report.path("data").path("free").asInt()).isEqualTo(39);
    }
    @Test void rejectsUnknownToolsArbitraryCoordinatesAndTooManyActions() throws Exception {
        for (String input : List.of("[{\"type\":\"eval\",\"target\":\"alert(1)\"}]",
            "[{\"type\":\"scene.focus\",\"target\":\"D\"}]",
            "[{\"type\":\"scene.focus\",\"target\":\"A\",\"x\":1}]",
            "[{},{},{},{},{}]", "{}"))
            assertThatThrownBy(() -> ParkingAgentService.parseActions(json.readTree(input))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ParkingAgentService.parseActions(json.readTree("[{\"type\":\"scene.focus\",\"target\":\"A\"}]")))
            .containsExactly(new ParkingAgentService.Action("scene.focus", "A"));
    }
    @Test void invalidModelPlanNeverPartiallyExecutes() throws Exception {
        when(model.enabled()).thenReturn(true);
        when(model.jsonAnswer(anyString(), anyString(), anyString(), anyInt())).thenReturn(
            "{\"answer\":\"hello\",\"actions\":[{\"type\":\"scene.focus\",\"target\":\"A\"},{\"type\":\"gate.open\",\"target\":\"A\"}]}");
        var output = agent.respond(request("开门并带我过去"), ChatModelCatalog.FLASH);
        assertThat(output).noneMatch(e -> e.event().equals("action"));
        assertThat(output).anyMatch(e -> e.data().contains("local-guide-fallback"));
    }
    @Test void modelPlansCombinationWithOnlyParkingKnowledge() throws Exception {
        long unrelated = knowledge.createKnowledgeBase(new KnowledgeBaseRequest("其他资料", "", "OTHER"));
        knowledge.importDocument(unrelated, "SECRET-HR", "急诊停车私人资料", Map.of());
        agent.bootstrap();
        when(model.enabled()).thenReturn(true); when(model.provider()).thenReturn("test-model");
        when(model.jsonAnswer(anyString(), anyString(), eq(ChatModelCatalog.FLASH), eq(8192)))
            .thenAnswer(call -> {
                String prompt = call.getArgument(1);
                assertThat(prompt).contains("停车").doesNotContain("SECRET-HR");
                return "{\"answer\":\"将展示急诊区和演示告警[1]\",\"actions\":[{\"type\":\"scene.focus\",\"target\":\"C\"},{\"type\":\"report.show\",\"target\":\"alerts\"}]}";
            });
        var output = agent.respond(request("看急诊停车区并列出停车告警"), ChatModelCatalog.FLASH);
        assertThat(output).filteredOn(e -> e.event().equals("action")).hasSize(2);
        assertThat(output).anyMatch(e -> e.event().equals("references") && e.data().contains("停车"));
    }
    @Test void noParkingKnowledgeDoesNotSearchAllEnterpriseKnowledge() throws Exception {
        long unrelated = knowledge.createKnowledgeBase(new KnowledgeBaseRequest("其他资料", "", "OTHER"));
        knowledge.importDocument(unrelated, "SECRET", "停车资料", Map.of());
        var output = agent.respond(request("介绍停车场"), ChatModelCatalog.FLASH);
        assertThat(output).anyMatch(e -> e.event().equals("references") && e.data().equals("[]"));
    }
    @Test void rejectsDuplicateZonesInvalidOccupancyAndStaleSnapshot() {
        var c = context();
        for (List<ParkingAgentRequest.Zone> zones : List.of(
            List.of(new ParkingAgentRequest.Zone("A", 1, 2), c.zones().get(1), c.zones().get(2)),
            List.of(c.zones().get(0), c.zones().get(0), c.zones().get(2)))) {
            var invalid = new ParkingAgentRequest.Context(c.source(), c.observedAt(), true, zones, c.events(), c.alerts(), "", "");
            assertThatThrownBy(() -> ParkingAgentService.validateContext(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        var stale = new ParkingAgentRequest.Context(c.source(), "2020-01-01T00:00:00Z", true, c.zones(), c.events(), c.alerts(), "", "");
        assertThatThrownBy(() -> ParkingAgentService.validateContext(stale)).hasMessageContaining("过期");
    }
    @Test void tourControlsAreBoundedCommandsAndDoNotCallModel() throws Exception {
        for (String text : List.of("开始园区导览", "暂停导览", "继续导览", "停止导览"))
            assertThat(agent.respond(request(text), ChatModelCatalog.FLASH)).filteredOn(e -> e.event().equals("action")).hasSize(1);
        verifyNoInteractions(model);
    }
    @Test void fullParkingDoesNotRecommendAnOccupiedZone() throws Exception {
        var c = context();
        var full = new ParkingAgentRequest.Context(c.source(), c.observedAt(), true,
            c.zones().stream().map(z -> new ParkingAgentRequest.Zone(z.id(), z.capacity(), z.capacity())).toList(),
            c.events(), c.alerts(), "", "");
        var output = agent.respond(new ParkingAgentRequest("推荐停车区", ChatModelCatalog.FLASH, full, List.of()), ChatModelCatalog.FLASH);
        assertThat(output).noneMatch(e -> e.event().equals("action") && e.data().contains("scene.focus"));
        assertThat(output).anyMatch(e -> e.event().equals("answer") && e.data().contains("满位"));
    }
    @Test void reactiveStreamEndsInsteadOfLeakingHeartbeatSubscription() {
        assertThat(agent.stream(request("查看停车报表")).collectList().block(java.time.Duration.ofSeconds(5)))
            .last().satisfies(e -> assertThat(e.event()).isEqualTo("done"));
    }
}
