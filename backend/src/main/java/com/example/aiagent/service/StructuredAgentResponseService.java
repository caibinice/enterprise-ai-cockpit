package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts a strict model JSON contract into safe answer text and ECharts options. */
final class StructuredAgentResponseService {
    private static final Set<String> CHART_TYPES = Set.of("bar", "line", "pie");
    private static final Set<String> WEATHER_FIELDS = Set.of(
        "temperatureC", "apparentTemperatureC", "humidityPercent", "windSpeedKmh"
    );
    private static final String RESPONSE_PROMPT = """
        你是企业智能座舱的最终回答器。你会收到用户问题、知识证据和已经执行的 MCP 结果。
        只输出一个 JSON 对象，禁止输出 JSON 之外的文字、Markdown 围栏、HTML、script、canvas、JavaScript 或 CDN 链接。

        固定结构：
        {
          "answer": "面向用户的自然语言 Markdown，可含表格；不要说‘根据知识库/检索结果/MCP调用’，不要描述内部实现",
          "charts": [
            {
              "id": "稳定且简短的英文标识",
              "title": "图表标题",
              "type": "bar | line | pie",
              "source": "tool:weather | inline",
              "categoryField": "city",
              "xAxisLabel": "城市",
              "yAxisLabel": "气温 (°C)",
              "series": [
                {"name": "实时气温", "field": "temperatureC", "unit": "°C"}
              ],
              "data": []
            }
          ]
        }

        图表规则：
        1. 用户没有要求可视化且文字更清楚时 charts=[]；明确要求图表时必须返回至少一个图表指令。
        2. 天气图必须 source=tool:weather，data=[]，应用会从受信任工具结果按字段绑定真实数据。
           可用数值字段仅有 temperatureC、apparentTemperatureC、humidityPercent、windSpeedKmh。
        3. 知识或普通分析图使用 source=inline；data 格式为
           [{"label":"分类", "values":{"系列字段": 12.3}}]，series.field 必须对应 values 的字段。
        4. 最多 4 张图、每张最多 50 个分类和 6 个系列。只允许 bar、line、pie，不返回任意 ECharts/HTML 配置。
        5. 同一回答可返回多张图，例如气温柱状图、湿度折线图和天气类型饼图；不要把图表代码写进 answer。
        6. 工具 status=success 时忠实使用完整结果；status=error 时明确服务暂不可用，绝不编造实时数据。
        7. 知识证据存在时直接组织结论，可用 [Reference N] 标注关键依据，但避免“根据知识库内容可知”等机械开场。
        """;

    private final ObjectMapper objectMapper;
    private final ModelGateway modelGateway;
    private final ToolResultChartService fallbackCharts;

    StructuredAgentResponseService(ObjectMapper objectMapper, ModelGateway modelGateway) {
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
        this.fallbackCharts = new ToolResultChartService(objectMapper);
    }

    Result compose(
        String modelQuestion,
        List<RetrievedKnowledgeChunk> references,
        List<McpExecutionResult> toolResults,
        String model,
        boolean chartRequested
    ) {
        String raw = modelGateway.jsonAnswer(
            RESPONSE_PROMPT,
            responseInput(modelQuestion, references, toolResults, chartRequested),
            model,
            6000
        );
        JsonNode root = parseRoot(raw);
        String answer = root == null ? sanitizeAnswer(raw) : sanitizeAnswer(root.path("answer").asText(""));
        List<String> charts = root == null
            ? new ArrayList<>()
            : chartRequested
                ? parseCharts(root.path("charts"), toolResults)
                : new ArrayList<>();

        if (!isUsableAnswer(answer)) {
            answer = fallbackCharts.weatherAnswer(toolResults);
        }
        if (!isUsableAnswer(answer)) {
            answer = sanitizeAnswer(modelGateway.answer(modelQuestion, references, model));
        }
        if (!isUsableAnswer(answer)) {
            answer = "本轮没有生成可展示的回答，请重试或切换模型。";
        }

        if (chartRequested && charts.isEmpty()) {
            String weather = fallbackCharts.weatherChart(toolResults);
            if (weather != null) charts.add(weather);
            else {
                String generic = modelGateway.chart(modelQuestion, references);
                if (isValidEcharts(generic)) charts.add(generic);
            }
        }
        return new Result(answer, List.copyOf(charts.stream().limit(4).toList()));
    }

    private String responseInput(
        String modelQuestion,
        List<RetrievedKnowledgeChunk> references,
        List<McpExecutionResult> toolResults,
        boolean chartRequested
    ) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int index = 0; index < references.size(); index += 1) {
            RetrievedKnowledgeChunk reference = references.get(index);
            evidence.add(Map.of(
                "reference", index + 1,
                "title", reference.title(),
                "content", reference.content(),
                "metadata", reference.metadata()
            ));
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                "questionAndConversation", modelQuestion,
                "chartRequested", chartRequested,
                "knowledgeEvidence", evidence,
                "toolResults", toolResults
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize structured response context", ex);
        }
    }

    private JsonNode parseRoot(String raw) {
        String json = AgentToolOrchestrator.extractJsonObject(raw);
        if (json == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.isObject() && root.has("answer") && root.path("charts").isArray()
                ? root
                : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> parseCharts(JsonNode chartNodes, List<McpExecutionResult> toolResults) {
        List<String> charts = new ArrayList<>();
        if (!chartNodes.isArray()) return charts;
        JsonNode weather = successfulWeather(toolResults);
        for (JsonNode directive : chartNodes) {
            if (charts.size() >= 4 || !directive.isObject()) break;
            try {
                String chart = buildChart(directive, weather);
                if (chart != null) charts.add(chart);
            } catch (Exception ignored) {
                // Invalid model chart directives are dropped; deterministic fallback runs later.
            }
        }
        return charts;
    }

    private String buildChart(JsonNode directive, JsonNode weather) throws Exception {
        String type = directive.path("type").asText("").toLowerCase();
        if (!CHART_TYPES.contains(type)) return null;
        String source = directive.path("source").asText("");
        List<SeriesDirective> requestedSeries = seriesDirectives(directive.path("series"));
        if (requestedSeries.isEmpty()) return null;

        List<String> labels = new ArrayList<>();
        Map<String, List<Double>> values = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        requestedSeries.forEach(series -> {
            values.put(series.name(), new ArrayList<>());
            units.put(series.name(), series.unit());
        });

        if ("tool:weather".equals(source)) {
            if (weather == null) return null;
            JsonNode rows = weather.path("cities").isArray()
                ? weather.path("cities")
                : objectMapper.createArrayNode().add(weather);
            String categoryField = directive.path("categoryField").asText("city");
            for (JsonNode row : rows) {
                String label = row.path(categoryField).asText("").trim();
                if (label.isBlank()) continue;
                List<Double> rowValues = new ArrayList<>();
                boolean valid = true;
                for (SeriesDirective series : requestedSeries) {
                    if (!WEATHER_FIELDS.contains(series.field()) || !row.path(series.field()).isNumber()) {
                        valid = false;
                        break;
                    }
                    rowValues.add(row.path(series.field()).asDouble());
                }
                if (!valid) continue;
                labels.add(label);
                for (int index = 0; index < requestedSeries.size(); index += 1) {
                    values.get(requestedSeries.get(index).name()).add(rowValues.get(index));
                }
                if (labels.size() >= 50) break;
            }
        } else if ("inline".equals(source)) {
            JsonNode rows = directive.path("data");
            if (!rows.isArray()) return null;
            for (JsonNode row : rows) {
                String label = row.path("label").asText("").trim();
                JsonNode rowValues = row.path("values");
                if (label.isBlank() || !rowValues.isObject()) continue;
                List<Double> parsed = new ArrayList<>();
                boolean valid = true;
                for (SeriesDirective series : requestedSeries) {
                    JsonNode number = rowValues.path(series.field());
                    if (!number.isNumber() || !Double.isFinite(number.asDouble())) {
                        valid = false;
                        break;
                    }
                    parsed.add(number.asDouble());
                }
                if (!valid) continue;
                labels.add(label);
                for (int index = 0; index < requestedSeries.size(); index += 1) {
                    values.get(requestedSeries.get(index).name()).add(parsed.get(index));
                }
                if (labels.size() >= 50) break;
            }
        } else {
            return null;
        }
        if (labels.isEmpty() || values.values().stream().anyMatch(List::isEmpty)) return null;

        String title = directive.path("title").asText("数据分析图").trim();
        String sourceLabel = "tool:weather".equals(source)
            ? "数据来源：" + weather.path("source").asText("实时天气服务")
            : "结构化分析结果";
        return objectMapper.writeValueAsString(echarts(
            title,
            type,
            labels,
            values,
            units,
            directive.path("xAxisLabel").asText(""),
            directive.path("yAxisLabel").asText(""),
            sourceLabel
        ));
    }

    private Map<String, Object> echarts(
        String title,
        String type,
        List<String> labels,
        Map<String, List<Double>> values,
        Map<String, String> units,
        String xAxisLabel,
        String yAxisLabel,
        String sourceLabel
    ) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "echarts");
        spec.put("title", Map.of(
            "text", title.isBlank() ? "数据分析图" : title,
            "subtext", sourceLabel,
            "left", "center",
            "top", 8,
            "textStyle", Map.of("fontSize", 15, "fontWeight", 650, "color", "#273248"),
            "subtextStyle", Map.of("fontSize", 9, "color", "#8b95a8")
        ));
        spec.put("tooltip", Map.of("trigger", "pie".equals(type) ? "item" : "axis"));
        spec.put("legend", Map.of("top", 50, "type", "scroll"));
        if ("pie".equals(type)) {
            Map.Entry<String, List<Double>> first = values.entrySet().iterator().next();
            List<Map<String, Object>> data = new ArrayList<>();
            for (int index = 0; index < labels.size(); index += 1) {
                data.add(Map.of("name", labels.get(index), "value", first.getValue().get(index)));
            }
            spec.put("series", List.of(Map.of(
                "name", first.getKey(),
                "type", "pie",
                "radius", List.of("38%", "68%"),
                "center", List.of("50%", "60%"),
                "data", data,
                "label", Map.of("formatter", "{b}  {d}%")
            )));
        } else {
            spec.put("grid", Map.of("left", 50, "right", 18, "top", 82, "bottom", labels.size() > 8 ? 65 : 42));
            spec.put("xAxis", Map.of(
                "type", "category",
                "name", xAxisLabel,
                "data", labels,
                "axisLabel", Map.of("interval", 0, "rotate", labels.size() > 8 ? 28 : 0)
            ));
            spec.put("yAxis", Map.of(
                "type", "value",
                "name", yAxisLabel,
                "scale", true,
                "splitLine", Map.of("lineStyle", Map.of("color", "rgba(97,112,145,.12)"))
            ));
            List<Map<String, Object>> series = new ArrayList<>();
            int colorIndex = 0;
            List<String> colors = List.of("#5368f2", "#24a69a", "#8b67d9", "#ef9a56", "#4a9bea", "#d66b8f");
            for (Map.Entry<String, List<Double>> entry : values.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("type", type);
                item.put("data", entry.getValue());
                item.put("smooth", "line".equals(type));
                item.put("symbolSize", 7);
                item.put("barMaxWidth", 30);
                item.put("itemStyle", Map.of(
                    "color", colors.get(colorIndex++ % colors.size()),
                    "borderRadius", List.of(6, 6, 2, 2)
                ));
                String unit = units.getOrDefault(entry.getKey(), "");
                item.put("label", Map.of(
                    "show", labels.size() <= 20,
                    "position", "top",
                    "formatter", "{c}" + unit,
                    "fontSize", 9
                ));
                series.add(item);
            }
            spec.put("series", series);
        }
        spec.put("animationDuration", 600);
        return spec;
    }

    private List<SeriesDirective> seriesDirectives(JsonNode nodes) {
        if (!nodes.isArray()) return List.of();
        List<SeriesDirective> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            String name = node.path("name").asText("").trim();
            String field = node.path("field").asText("").trim();
            if (name.isBlank() || field.isBlank() || !names.add(name)) continue;
            result.add(new SeriesDirective(name, field, node.path("unit").asText("")));
            if (result.size() >= 6) break;
        }
        return List.copyOf(result);
    }

    private JsonNode successfulWeather(List<McpExecutionResult> toolResults) {
        for (McpExecutionResult result : toolResults) {
            if (!"weather".equals(result.id()) || !"success".equals(result.status())) continue;
            try {
                JsonNode root = objectMapper.readTree(result.output());
                if (root.isObject()) return root;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isValidEcharts(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isObject() && node.path("series").isArray();
        } catch (Exception ex) {
            return false;
        }
    }

    private String sanitizeAnswer(String value) {
        if (value == null) return "";
        String sanitized = value
            .replaceAll("(?is)```(?:html|javascript|js)?\\s*.*?```", "")
            .replaceAll("(?is)<script\\b[^>]*>.*?</script>", "")
            .replaceAll("(?is)<style\\b[^>]*>.*?</style>", "")
            .replaceAll("(?is)<canvas\\b[^>]*>.*?</canvas>", "")
            .replaceAll("(?is)</?[a-z][^>]*>", "")
            .replace("根据知识库中的信息，", "")
            .replace("根据知识库内容，", "")
            .replace("根据检索结果，", "")
            .trim();
        String[] markers = {
            "document.getElementById", "new Chart(", "Chart.js", "const ctx",
            "cdn.jsdelivr.net", "html<div", "javascript:"
        };
        int cutoff = sanitized.length();
        for (String marker : markers) {
            int index = sanitized.toLowerCase().indexOf(marker.toLowerCase());
            if (index >= 0) cutoff = Math.min(cutoff, index);
        }
        return sanitized.substring(0, cutoff).trim();
    }

    private boolean isUsableAnswer(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return !answer.contains("No matching enterprise knowledge base evidence")
            && !answer.contains("模型服务暂时不可用，已切换为本地 RAG")
            && !answer.startsWith("{");
    }

    record Result(String answer, List<String> chartSpecs) {
    }

    private record SeriesDirective(String name, String field, String unit) {
    }
}
