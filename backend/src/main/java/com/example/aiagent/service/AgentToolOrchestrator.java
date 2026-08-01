package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.McpToolCall;
import com.example.aiagent.model.McpToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small, bounded agent loop: the model selects MCP tools from their discovered
 * schemas, the host validates/executes them, and the next planning turn sees
 * the observations. No user text is matched here to select a province or tool.
 */
final class AgentToolOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentToolOrchestrator.class);
    private static final int MAX_STEPS = 3;
    private static final String PLANNER_PROMPT = """
        你是企业智能座舱的 MCP 工具规划器。MCP 工具由模型根据用户目标自主选择，应用只负责授权校验和执行。

        仅输出一个 JSON 对象，不要输出 Markdown、HTML 或思维链：
        {
          "summary": "一句话说明本轮意图与下一步动作，不披露隐含推理过程",
          "intent": "weather | time | calculate | maps | none",
          "scope": {
            "type": "single_city | administrative_region | country | explicit_cities | none",
            "name": "用户要求的地理范围；无则为空字符串",
            "requiresEnumeration": true,
            "locations": [
              {"label": "面向用户展示的城市名", "query": "天气地理编码可直接识别的标准城市名"}
            ]
          },
          "toolCalls": [
            {"name": "必须来自 availableTools.name", "arguments": {}}
          ],
          "complete": false
        }

        规则：
        1. 只能调用 availableTools 中列出的工具并严格遵守 inputSchema；没有必要调用时返回空 toolCalls。
        2. observations 是前序工具结果。需要依赖结果的任务要分轮执行，不得猜测前序结果。
        3. 先在 scope 中做语义判断，再选择工具；不要按关键词机械匹配。
           - 单城市为 single_city；省/州/自治区等含下级城市的行政区为 administrative_region；国家为 country；
             用户直接列出多个城市为 explicit_cities。requiresEnumeration 表示是否还需展开城市。
           - country 的 locations 必须给出不超过 20 个行政中心/主要城市；中国以外城市的 query 使用国际通用英文/罗马字，
             例如 {"label":"东京","query":"Tokyo"}，不可把国家名当 query。explicit_cities 必须完整放入 locations；
             single_city 放一个城市。administrative_region 可留空，交由行政区工具取得权威下级城市。
        4. 用户要求某省/国家的全部或各城市天气时，不要把“浙江所有城市”当成一个城市。
           - intent 必须为 weather。若 maps_district 可用，第一轮只查询行政区下级城市，complete=false；
             下一轮从其 children 提取城市名称调用 queryWeather。
           - 若 maps_district 不可用，可依据可靠常识列出省级行政区的地级市，或一个国家不超过 20 个行政中心/主要城市，
             在 queryWeather.cities 中逐项传入，并用 region 说明范围。不能确认“全部”时在 summary 标明采用代表城市范围。
           - 两个及以上城市必须使用一次 queryWeather 批量调用。maps_weather 仅用于单个中国城市预报，禁止为批量任务逐城调用。
           - 省、自治区或国家名称不是 city，绝不能把“浙江省”“日本”等直接传给 queryWeather.city。
        5. 可以在同一轮调用相互独立的工具；依赖调用必须等待 observation。不得重复 identicalCalls 中已经完成的调用。
        6. 所有工具均为只读。工具失败后不要无限重试；最多换一种合法参数再试一次。
        7. 当无需更多工具时 complete=true。调用 maps_district 后仍需天气数据时不得提前 complete。
        """;

    private final McpToolService mcpToolService;
    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    AgentToolOrchestrator(
        McpToolService mcpToolService,
        ModelGateway modelGateway,
        ObjectMapper objectMapper
    ) {
        this.mcpToolService = mcpToolService;
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
    }

    Result execute(String question, List<String> selectedIds, String model) {
        if (mcpToolService == null || selectedIds == null || selectedIds.isEmpty()) {
            return new Result("", List.of());
        }
        List<McpToolDefinition> available = mcpToolService.availableTools(selectedIds);
        if (available.isEmpty()) {
            return new Result(
                "工具目录暂时不可用，已使用兼容调用策略。",
                mcpToolService.executeSelected(question, selectedIds)
            );
        }

        List<McpExecutionResult> observations = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        Set<String> identicalCalls = new LinkedHashSet<>();
        boolean weatherIntent = false;
        for (int step = 1; step <= MAX_STEPS; step += 1) {
            String raw = modelGateway.jsonAnswer(
                PLANNER_PROMPT,
                plannerInput(question, available, observations, identicalCalls, step),
                model,
                4096
            );
            Plan plan = parsePlan(raw);
            if (plan == null) {
                log.warn("Model returned an invalid MCP plan at step {}", step);
                if (observations.isEmpty()) {
                    return new Result(
                        "模型规划格式异常，已使用兼容调用策略。",
                        mcpToolService.executeSelected(question, selectedIds)
                    );
                }
                break;
            }
            log.info(
                "MCP plan step {} intent={} scopeType={} scopeName={} calls={}",
                step,
                plan.intent(),
                plan.scope().type(),
                plan.scope().name(),
                plan.toolCalls().stream().map(McpToolCall::name).toList()
            );
            if (!plan.summary().isBlank()) summaries.add(plan.summary());
            weatherIntent = weatherIntent || "weather".equals(plan.intent());
            List<McpToolCall> plannedCalls = enforceStructuredScope(
                plan,
                available,
                observations
            );
            plannedCalls = deferDependentWeatherCalls(plannedCalls);
            McpToolCall groundedWeatherCall = weatherIntent
                && selectedIds.stream().anyMatch("weather"::equalsIgnoreCase)
                && observations.stream().noneMatch(result ->
                    "weather".equals(result.id()) && "success".equals(result.status()))
                    ? districtWeatherContinuation(observations, available)
                    : null;
            if (groundedWeatherCall != null) {
                List<McpToolCall> groundedCalls = new ArrayList<>();
                plannedCalls.stream()
                    .filter(call -> !"queryWeather".equalsIgnoreCase(call.name()))
                    .filter(call -> !"maps_weather".equalsIgnoreCase(call.name()))
                    .forEach(groundedCalls::add);
                groundedCalls.add(groundedWeatherCall);
                plannedCalls = List.copyOf(groundedCalls);
            }
            List<McpToolCall> freshCalls = plannedCalls.stream()
                .filter(call -> identicalCalls.add(callKey(call)))
                .limit(6)
                .toList();
            if (freshCalls.isEmpty()) break;
            List<McpExecutionResult> stepResults = mcpToolService.executeCalls(freshCalls, selectedIds);
            observations.addAll(stepResults);

            boolean districtNeedsFollowUp = selectedIds.stream().anyMatch("weather"::equalsIgnoreCase)
                && freshCalls.stream().anyMatch(call -> "maps_district".equalsIgnoreCase(call.name()))
                && "weather".equals(plan.intent())
                && observations.stream().noneMatch(result ->
                    "weather".equals(result.id()) && "success".equals(result.status()));
            if (plan.complete() && !districtNeedsFollowUp) break;
        }
        if (
            weatherIntent
                && observations.stream().noneMatch(result ->
                    "weather".equals(result.id()) && "success".equals(result.status()))
        ) {
            McpToolCall continuation = districtWeatherContinuation(observations, available);
            if (continuation != null && identicalCalls.add(callKey(continuation))) {
                summaries.add("使用行政区结构化结果完成批量天气查询");
                observations.addAll(mcpToolService.executeCalls(List.of(continuation), selectedIds));
            }
        }
        return new Result(String.join(" → ", summaries), List.copyOf(observations));
    }

    private List<McpToolCall> deferDependentWeatherCalls(List<McpToolCall> calls) {
        boolean hasDistrict = calls.stream().anyMatch(call ->
            "maps_district".equalsIgnoreCase(call.name()));
        if (!hasDistrict) return calls;
        return calls.stream()
            .filter(call -> !"queryWeather".equalsIgnoreCase(call.name()))
            .filter(call -> !"maps_weather".equalsIgnoreCase(call.name()))
            .toList();
    }

    private List<McpToolCall> enforceStructuredScope(
        Plan plan,
        List<McpToolDefinition> available,
        List<McpExecutionResult> observations
    ) {
        if (!"weather".equals(plan.intent()) || !observations.isEmpty()) return plan.toolCalls();
        Scope scope = plan.scope();
        if (scope == null || scope.type().isBlank() || "none".equals(scope.type())) {
            return plan.toolCalls();
        }

        boolean districtAvailable = available.stream().anyMatch(definition ->
            "maps_district".equalsIgnoreCase(definition.name()));
        if ("administrative_region".equals(scope.type())
            && scope.requiresEnumeration()
            && districtAvailable
            && !scope.name().isBlank()) {
            return replaceWeatherCalls(plan.toolCalls(), new McpToolCall(
                "maps_district",
                Map.of("keywords", scope.name(), "subdistrict", 1)
            ));
        }

        List<PlannedLocation> locations = scope.locations();
        if (locations.isEmpty()) locations = weatherLocationsFromCalls(plan.toolCalls());
        if ("country".equals(scope.type())) {
            locations = locations.stream()
                .filter(location -> !location.query().equalsIgnoreCase(scope.name()))
                .filter(location -> !location.label().equalsIgnoreCase(scope.name()))
                .limit(20)
                .toList();
            if (locations.size() < 2) return withoutWeatherCalls(plan.toolCalls());
        }
        if ("single_city".equals(scope.type()) && locations.isEmpty() && !scope.name().isBlank()) {
            locations = List.of(new PlannedLocation(scope.name(), scope.name()));
        }
        if (locations.isEmpty()) return plan.toolCalls();
        return replaceWeatherCalls(plan.toolCalls(), weatherCall(scope.name(), locations));
    }

    private List<McpToolCall> replaceWeatherCalls(List<McpToolCall> calls, McpToolCall replacement) {
        List<McpToolCall> normalized = new ArrayList<>();
        calls.stream()
            .filter(call -> !"queryWeather".equalsIgnoreCase(call.name()))
            .filter(call -> !"maps_weather".equalsIgnoreCase(call.name()))
            .filter(call -> !"maps_district".equalsIgnoreCase(call.name()))
            .forEach(normalized::add);
        normalized.add(replacement);
        return List.copyOf(normalized);
    }

    private List<McpToolCall> withoutWeatherCalls(List<McpToolCall> calls) {
        return calls.stream()
            .filter(call -> !"queryWeather".equalsIgnoreCase(call.name()))
            .filter(call -> !"maps_weather".equalsIgnoreCase(call.name()))
            .toList();
    }

    private McpToolCall weatherCall(String region, List<PlannedLocation> locations) {
        return new McpToolCall("queryWeather", Map.of(
            "cities", locations.stream().map(PlannedLocation::query).toList(),
            "displayNames", locations.stream().map(PlannedLocation::label).toList(),
            "region", region
        ));
    }

    private List<PlannedLocation> weatherLocationsFromCalls(List<McpToolCall> calls) {
        for (McpToolCall call : calls) {
            if (!"queryWeather".equalsIgnoreCase(call.name())) continue;
            Object rawCities = call.arguments().get("cities");
            if (!(rawCities instanceof List<?> cities)) continue;
            return cities.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(city -> !city.isBlank())
                .distinct()
                .limit(20)
                .map(city -> new PlannedLocation(city, city))
                .toList();
        }
        return List.of();
    }

    private McpToolCall districtWeatherContinuation(
        List<McpExecutionResult> observations,
        List<McpToolDefinition> available
    ) {
        boolean weatherAvailable = available.stream().anyMatch(definition ->
            "queryWeather".equalsIgnoreCase(definition.name()));
        if (!weatherAvailable) return null;
        for (int index = observations.size() - 1; index >= 0; index -= 1) {
            McpExecutionResult result = observations.get(index);
            if (!"amap".equals(result.id()) || !"success".equals(result.status())) continue;
            try {
                JsonNode root = objectMapper.readTree(result.output());
                log.info(
                    "Amap district observation queryType={} childCount={} fields={}",
                    root.path("queryType").asText(""),
                    root.path("children").isArray() ? root.path("children").size() : 0,
                    java.util.stream.StreamSupport.stream(
                        ((Iterable<String>) () -> root.fieldNames()).spliterator(),
                        false
                    ).toList()
                );
                if (!"amap-district".equals(root.path("queryType").asText())
                    || !root.path("children").isArray()) continue;
                List<String> cities = new ArrayList<>();
                List<Map<String, Object>> locations = new ArrayList<>();
                root.path("children").forEach(child -> {
                    String name = child.path("name").asText("").trim();
                    if (name.isBlank() || cities.size() >= 20) return;
                    cities.add(name);
                    String[] center = child.path("center").asText("").split(",");
                    if (center.length != 2) return;
                    try {
                        double longitude = Double.parseDouble(center[0].trim());
                        double latitude = Double.parseDouble(center[1].trim());
                        locations.add(Map.of(
                            "city", name,
                            "longitude", longitude,
                            "latitude", latitude,
                            "admin1", root.path("name").asText(""),
                            "country", "中国"
                        ));
                    } catch (NumberFormatException ignored) {
                        // The weather MCP will geocode this city if Amap omitted a usable center.
                    }
                });
                if (cities.isEmpty()) continue;
                Map<String, Object> arguments = new java.util.LinkedHashMap<>();
                arguments.put("cities", cities);
                arguments.put("region", root.path("name").asText(root.path("keyword").asText("")));
                if (!locations.isEmpty()) arguments.put("locations", locations);
                return new McpToolCall("queryWeather", arguments);
            } catch (Exception ignored) {
                // Try an earlier successful Amap observation.
            }
        }
        return null;
    }

    private String plannerInput(
        String question,
        List<McpToolDefinition> available,
        List<McpExecutionResult> observations,
        Set<String> identicalCalls,
        int step
    ) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "step", step,
                "userRequest", question == null ? "" : question,
                "availableTools", available,
                "observations", observations,
                "identicalCalls", identicalCalls
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize agent planning context", ex);
        }
    }

    private Plan parsePlan(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject() || !root.path("toolCalls").isArray()) return null;
            List<McpToolCall> calls = new ArrayList<>();
            for (JsonNode node : root.path("toolCalls")) {
                String name = node.path("name").asText("").trim();
                if (name.isBlank() || !node.path("arguments").isObject()) continue;
                Map<String, Object> arguments = objectMapper.convertValue(
                    node.path("arguments"),
                    new TypeReference<>() {}
                );
                calls.add(new McpToolCall(name, arguments));
            }
            String summary = root.path("summary").asText("").trim();
            String intent = root.path("intent").asText("").trim().toLowerCase();
            if (intent.isBlank() && (
                summary.contains("天气") || calls.stream().anyMatch(call ->
                    "queryWeather".equalsIgnoreCase(call.name())
                        || "maps_weather".equalsIgnoreCase(call.name()))
            )) intent = "weather";
            JsonNode scopeNode = root.path("scope");
            String scopeType = normalizeScopeType(scopeNode.path("type").asText(""));
            String scopeName = scopeNode.path("name").asText("").trim();
            List<PlannedLocation> locations = new ArrayList<>();
            if (scopeNode.path("locations").isArray()) {
                scopeNode.path("locations").forEach(location -> {
                    if (locations.size() >= 20) return;
                    String label;
                    String query;
                    if (location.isObject()) {
                        label = location.path("label").asText("").trim();
                        query = location.path("query").asText("").trim();
                    } else {
                        label = location.asText("").trim();
                        query = label;
                    }
                    if (label.isBlank()) label = query;
                    if (query.isBlank()) query = label;
                    if (!label.isBlank() && !query.isBlank()) {
                        locations.add(new PlannedLocation(label, query));
                    }
                });
            }
            return new Plan(
                summary,
                intent,
                new Scope(
                    scopeType,
                    scopeName,
                    scopeNode.path("requiresEnumeration").asBoolean(false),
                    List.copyOf(locations)
                ),
                List.copyOf(calls),
                root.path("complete").asBoolean(calls.isEmpty())
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeScopeType(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase()) {
            case "province", "state", "region", "administrative-region", "administrative_region" ->
                "administrative_region";
            case "nation", "country" -> "country";
            case "cities", "city_list", "explicit-cities", "explicit_cities" -> "explicit_cities";
            case "city", "single-city", "single_city" -> "single_city";
            default -> "none";
        };
    }

    private String callKey(McpToolCall call) {
        try {
            return call.name().toLowerCase() + ':' + objectMapper.writeValueAsString(call.arguments());
        } catch (Exception ex) {
            return call.name().toLowerCase() + ':' + call.arguments();
        }
    }

    static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < raw.length(); index += 1) {
            char current = raw.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth += 1;
            else if (current == '}' && --depth == 0) return raw.substring(start, index + 1);
        }
        return null;
    }

    record Result(String summary, List<McpExecutionResult> toolResults) {
    }

    private record Plan(
        String summary,
        String intent,
        Scope scope,
        List<McpToolCall> toolCalls,
        boolean complete
    ) {
    }

    private record Scope(
        String type,
        String name,
        boolean requiresEnumeration,
        List<PlannedLocation> locations
    ) {
    }

    private record PlannedLocation(String label, String query) {
    }
}
