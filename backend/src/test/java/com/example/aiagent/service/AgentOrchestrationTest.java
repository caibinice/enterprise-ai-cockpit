package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.McpToolCall;
import com.example.aiagent.model.McpToolDefinition;
import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void modelPlansDistrictThenWeatherWithoutProvinceKeywordRules() {
        McpToolService mcp = mock(McpToolService.class);
        when(mcp.availableTools(any())).thenReturn(List.of(
            new McpToolDefinition(
                "amap",
                "maps_district",
                "查询行政区下级城市",
                Map.of("type", "object", "required", List.of("keywords"))
            ),
            new McpToolDefinition(
                "weather",
                "queryWeather",
                "批量查询城市天气",
                Map.of("type", "object", "required", List.of("cities"))
            )
        ));
        AtomicInteger execution = new AtomicInteger();
        when(mcp.executeCalls(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<McpToolCall> calls = invocation.getArgument(0);
            if (execution.getAndIncrement() == 0) {
                assertThat(calls).singleElement().satisfies(call -> {
                    assertThat(call.name()).isEqualTo("maps_district");
                    assertThat(call.arguments()).containsEntry("keywords", "浙江省");
                });
                return List.of(new McpExecutionResult(
                    "amap",
                    "高德地图",
                    "success",
                    "{\"queryType\":\"amap-district\",\"name\":\"浙江省\",\"children\":[{\"name\":\"杭州市\"},{\"name\":\"宁波市\"}]}"
                ));
            }
            assertThat(calls).singleElement().satisfies(call -> {
                assertThat(call.name()).isEqualTo("queryWeather");
                assertThat(call.arguments()).containsEntry("region", "浙江省");
                assertThat(call.arguments().get("cities")).isEqualTo(List.of("杭州市", "宁波市"));
            });
            return List.of(new McpExecutionResult(
                "weather",
                "实时天气",
                "success",
                "{\"region\":\"浙江省\",\"count\":2}"
            ));
        });

        AgentToolOrchestrator orchestrator = new AgentToolOrchestrator(
            mcp,
            new SequencedPlanningGateway(),
            objectMapper
        );
        AgentToolOrchestrator.Result result = orchestrator.execute(
            "把浙江下辖城市今天的天气列出来",
            List.of("weather", "amap"),
            ChatModelCatalog.FLASH
        );

        assertThat(result.toolResults()).extracting(McpExecutionResult::id)
            .containsExactly("amap", "weather");
        assertThat(result.summary()).contains("行政区", "天气");
        verify(mcp, never()).executeSelected(any(), any());
    }

    @Test
    void countryScopeUsesModelPlannedDisplayNamesAndCanonicalQueries() {
        McpToolService mcp = mock(McpToolService.class);
        when(mcp.availableTools(any())).thenReturn(List.of(new McpToolDefinition(
            "weather",
            "queryWeather",
            "批量查询城市天气",
            Map.of("type", "object", "required", List.of("cities"))
        )));
        when(mcp.executeCalls(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<McpToolCall> calls = invocation.getArgument(0);
            assertThat(calls).singleElement().satisfies(call -> {
                assertThat(call.name()).isEqualTo("queryWeather");
                assertThat(call.arguments().get("cities")).isEqualTo(List.of("Tokyo", "Osaka"));
                assertThat(call.arguments().get("displayNames")).isEqualTo(List.of("东京", "大阪"));
                assertThat(call.arguments()).containsEntry("region", "日本");
            });
            return List.of(new McpExecutionResult(
                "weather",
                "实时天气",
                "success",
                "{\"region\":\"日本\",\"count\":2}"
            ));
        });
        ModelGateway gateway = new StaticStructuredGateway("""
            {"summary":"选取日本主要城市并查询天气","intent":"weather",
             "scope":{"type":"country","name":"日本","requiresEnumeration":true,"locations":[
               {"label":"东京","query":"Tokyo"},{"label":"大阪","query":"Osaka"}
             ]},
             "toolCalls":[{"name":"queryWeather","arguments":{"cities":["日本"]}}],"complete":true}
            """);

        AgentToolOrchestrator.Result result = new AgentToolOrchestrator(
            mcp,
            gateway,
            objectMapper
        ).execute("列出这个国家的主要城市天气", List.of("weather"), ChatModelCatalog.FLASH);

        assertThat(result.toolResults()).singleElement()
            .extracting(McpExecutionResult::status)
            .isEqualTo("success");
    }

    @Test
    void explicitNoChartOverridesChartKeywordsAndUiToggle() {
        ToolResultChartService charts = new ToolResultChartService(objectMapper);
        assertThat(charts.shouldGenerate("列出城市天气，不需要图表", false)).isFalse();
        assertThat(charts.shouldGenerate("列出城市天气，不需要图表", true)).isFalse();
        assertThat(charts.shouldGenerate("展示气温柱状图", false)).isTrue();
    }

    @Test
    void structuredResponseDropsHtmlAndBindsMultipleChartsToTrustedToolData() throws Exception {
        ModelGateway gateway = new StaticStructuredGateway("""
            {
              "answer":"浙江城市天气如下。<script>alert('x')</script>",
              "charts":[
                {
                  "id":"temperature","title":"浙江城市气温","type":"bar","source":"tool:weather",
                  "categoryField":"city","xAxisLabel":"城市","yAxisLabel":"气温 (°C)",
                  "series":[{"name":"实时气温","field":"temperatureC","unit":"°C"}],"data":[]
                },
                {
                  "id":"humidity","title":"浙江城市湿度","type":"line","source":"tool:weather",
                  "categoryField":"city","xAxisLabel":"城市","yAxisLabel":"湿度 (%)",
                  "series":[{"name":"相对湿度","field":"humidityPercent","unit":"%"}],"data":[]
                }
              ]
            }
            """);
        List<McpExecutionResult> tools = List.of(new McpExecutionResult(
            "weather",
            "实时天气",
            "success",
            """
            {"region":"浙江省","source":"Open-Meteo","cities":[
              {"city":"杭州","temperatureC":31.5,"humidityPercent":62},
              {"city":"宁波","temperatureC":30.2,"humidityPercent":71}
            ]}
            """
        ));

        StructuredAgentResponseService.Result result = new StructuredAgentResponseService(
            objectMapper,
            gateway
        ).compose("浙江天气", List.of(), tools, ChatModelCatalog.FLASH, true);

        assertThat(result.answer()).isEqualTo("浙江城市天气如下。");
        assertThat(result.chartSpecs()).hasSize(2);
        JsonNode temperature = objectMapper.readTree(result.chartSpecs().get(0));
        JsonNode humidity = objectMapper.readTree(result.chartSpecs().get(1));
        assertThat(temperature.path("xAxis").path("data").toString()).contains("杭州", "宁波");
        assertThat(temperature.path("series").path(0).path("data").path(0).asDouble()).isEqualTo(31.5);
        assertThat(humidity.path("series").path(0).path("type").asText()).isEqualTo("line");
        assertThat(humidity.path("series").path(0).path("data").path(1).asDouble()).isEqualTo(71.0);
    }

    private static final class SequencedPlanningGateway implements ModelGateway {
        private int step;

        @Override public boolean enabled() { return true; }

        @Override
        public String jsonAnswer(String systemPrompt, String userPrompt, String model, int maxTokens) {
            step += 1;
            if (step == 1) {
                return """
                    {"summary":"识别为浙江行政区批量天气，先解析下级城市", "intent":"weather",
                     "scope":{"type":"administrative_region","name":"浙江省","requiresEnumeration":true,"locations":[]},
                     "toolCalls":[
                      {"name":"queryWeather","arguments":{"cities":["浙江省"]}}
                    ], "complete":false}
                    """;
            }
            return """
                {"summary":"取得下级城市后批量查询天气", "intent":"weather",
                 "scope":{"type":"administrative_region","name":"浙江省","requiresEnumeration":true,"locations":[]},
                 "toolCalls":[
                  {"name":"maps_weather","arguments":{"city":"浙江省"}}
                ], "complete":true}
                """;
        }

        @Override public String answer(String question, List<RetrievedKnowledgeChunk> references, String model) { return ""; }
        @Override public Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references, String model) { return Flux.empty(); }
        @Override public String chart(String question, List<RetrievedKnowledgeChunk> references) { return "{}"; }
    }

    private record StaticStructuredGateway(String response) implements ModelGateway {
        @Override public boolean enabled() { return true; }
        @Override public String jsonAnswer(String systemPrompt, String userPrompt, String model, int maxTokens) { return response; }
        @Override public String answer(String question, List<RetrievedKnowledgeChunk> references, String model) { return ""; }
        @Override public Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references, String model) { return Flux.empty(); }
        @Override public String chart(String question, List<RetrievedKnowledgeChunk> references) { return "{}"; }
    }
}
