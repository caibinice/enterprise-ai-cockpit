package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.McpToolCall;
import com.example.aiagent.model.McpToolDefinition;
import com.example.aiagent.model.McpToolOption;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpToolService {
    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private static final Pattern EXPRESSION = Pattern.compile("[-+*/().\\d\\s]{3,}");
    static final List<String> JIANGSU_CITIES = List.of(
        "南京", "无锡", "徐州", "常州", "苏州", "南通", "连云港",
        "淮安", "盐城", "扬州", "镇江", "泰州", "宿迁"
    );
    private static final List<String> KNOWN_CITIES = List.of(
        "上海", "常州", "南京", "无锡", "徐州", "苏州", "南通", "连云港",
        "淮安", "盐城", "扬州", "镇江", "泰州", "宿迁", "北京", "深圳",
        "广州", "杭州", "成都", "重庆", "东京", "纽约", "伦敦", "巴黎"
    );
    private static final List<McpToolOption> CATALOG = List.of(
        new McpToolOption(
            "weather",
            "实时天气",
            "通过 Open-Meteo 查询单个或多个城市的当前天气、体感温度、湿度与风速。",
            "queryWeather",
            true
        ),
        new McpToolOption(
            "time",
            "时间与时区",
            "查询指定 IANA 时区的当前日期和时间。",
            "getCurrentTime",
            true
        ),
        new McpToolOption(
            "calculator",
            "安全计算器",
            "计算包含括号和四则运算的表达式，不执行任意代码。",
            "calculate",
            true
        ),
        new McpToolOption(
            "amap",
            "高德地图",
            "通过高德开放平台解析行政区、地理编码、天气与地点搜索，适合跨城市位置任务。",
            "maps_district",
            true
        )
    );
    private static final Map<String, McpToolOption> BY_ID = catalogById();

    private final List<McpSyncClient> clients;
    private final Object initializationMonitor = new Object();
    private volatile boolean initialized;
    private volatile Map<String, McpSyncClient> clientsByTool = Map.of();
    private volatile Map<String, McpToolDefinition> definitionsByTool = Map.of();
    private volatile List<String> discoveredToolNames = List.of();

    @Autowired
    public McpToolService(ObjectProvider<List<McpSyncClient>> clients) {
        this(clients.getIfAvailable(List::of));
    }

    McpToolService(List<McpSyncClient> clients) {
        this.clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public List<McpToolOption> options() {
        boolean configured = !clients.isEmpty();
        return CATALOG.stream()
            .map(item -> new McpToolOption(
                item.id(),
                item.name(),
                item.description(),
                item.toolName(),
                configured
            ))
            .toList();
    }

    /** Returns only the concrete MCP tools that the user enabled for this chat. */
    public List<McpToolDefinition> availableTools(List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) return List.of();
        LinkedHashSet<String> selected = selectedIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(BY_ID::containsKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (selected.isEmpty()) return List.of();
        try {
            toolClients();
            return definitionsByTool.values().stream()
                .filter(definition -> selected.contains(definition.ownerId()))
                .toList();
        } catch (RuntimeException ex) {
            log.warn("Unable to describe MCP tools for model planning: {}", rootCauseMessage(ex));
            return List.of();
        }
    }

    /** Executes only model-planned calls whose owning MCP capability was selected by the user. */
    public List<McpExecutionResult> executeCalls(
        List<McpToolCall> calls,
        List<String> selectedIds
    ) {
        if (calls == null || calls.isEmpty()) return List.of();
        LinkedHashSet<String> selected = selectedIds == null
            ? new LinkedHashSet<>()
            : selectedIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        toolClients();
        List<McpExecutionResult> results = new ArrayList<>();
        for (McpToolCall call : calls.stream().limit(6).toList()) {
            String requestedName = call == null || call.name() == null
                ? ""
                : call.name().trim().toLowerCase(Locale.ROOT);
            McpToolDefinition definition = definitionsByTool.get(requestedName);
            if (definition == null || !selected.contains(definition.ownerId())) {
                log.warn("Model requested unavailable or unauthorized MCP tool: {}", requestedName);
                results.add(new McpExecutionResult(
                    "agent",
                    "智能体规划",
                    "error",
                    "模型请求了当前会话未授权的工具，已阻止执行。"
                ));
                continue;
            }
            McpToolOption option = BY_ID.get(definition.ownerId());
            long startedAt = System.nanoTime();
            try {
                String output = invoke(definition.name(), call.arguments());
                log.debug(
                    "Model-planned MCP tool {} succeeded in {} ms",
                    definition.name(),
                    (System.nanoTime() - startedAt) / 1_000_000
                );
                results.add(new McpExecutionResult(
                    option.id(),
                    option.name(),
                    "success",
                    output
                ));
            } catch (RuntimeException ex) {
                log.warn(
                    "Model-planned MCP tool {} failed after {} ms: {}",
                    definition.name(),
                    (System.nanoTime() - startedAt) / 1_000_000,
                    rootCauseMessage(ex),
                    ex
                );
                results.add(new McpExecutionResult(
                    option.id(),
                    option.name(),
                    "error",
                    option.name() + "暂时不可用，已安全降级，请稍后重试。"
                ));
            }
        }
        return List.copyOf(results);
    }

    public List<McpExecutionResult> executeSelected(
        String question,
        List<String> selectedIds
    ) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return List.of();
        }
        List<McpExecutionResult> results = new ArrayList<>();
        for (String rawId : new LinkedHashSet<>(selectedIds)) {
            String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
            McpToolOption option = BY_ID.get(id);
            if (option == null) {
                throw new IllegalArgumentException("未知 MCP 工具：" + rawId);
            }
            Map<String, Object> arguments = inferredArguments(id, question);
            if (arguments.isEmpty()) {
                results.add(new McpExecutionResult(
                    id,
                    option.name(),
                    "ready",
                    "已授权给当前会话，本轮问题未触发调用。"
                ));
                continue;
            }
            long startedAt = System.nanoTime();
            try {
                String output = invoke(option.toolName(), arguments);
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.debug("MCP tool {} succeeded in {} ms", option.toolName(), elapsedMs);
                results.add(new McpExecutionResult(
                    id,
                    option.name(),
                    "success",
                    output
                ));
            } catch (RuntimeException ex) {
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.warn(
                    "MCP tool {} failed after {} ms: {}",
                    option.toolName(),
                    elapsedMs,
                    rootCauseMessage(ex),
                    ex
                );
                results.add(new McpExecutionResult(
                    id,
                    option.name(),
                    "error",
                    option.name() + "暂时不可用，已安全降级，请稍后重试。"
                ));
            }
        }
        return results;
    }

    public String queryWeather(String city) {
        return invoke(
            BY_ID.get("weather").toolName(),
            Map.of("city", city == null || city.isBlank() ? "常州" : city.trim())
        );
    }

    public String status() {
        try {
            toolClients();
            return configuredStatus();
        } catch (RuntimeException ex) {
            return "error: " + rootCauseMessage(ex);
        }
    }

    public String configuredStatus() {
        return "enabled=true, clients=" + clients.size()
            + ", initialized=" + initialized
            + (initialized ? ", tools=" + discoveredToolNames : "");
    }

    private Map<String, Object> inferredArguments(String id, String question) {
        String text = question == null ? "" : question.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "weather" -> containsAny(lower, "天气", "气温", "温度", "下雨", "weather")
                ? inferWeatherArguments(text)
                : Map.of();
            case "time" -> containsAny(lower, "几点", "时间", "日期", "时区", "time", "date")
                ? Map.of("timezone", extractTimezone(text))
                : Map.of();
            case "calculator" -> {
                Matcher matcher = EXPRESSION.matcher(text);
                yield matcher.find()
                    ? Map.of("expression", matcher.group().trim())
                    : Map.of();
            }
            default -> Map.of();
        };
    }

    private Map<String, Object> inferWeatherArguments(String question) {
        if (isJiangsuAllCitiesRequest(question)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("cities", JIANGSU_CITIES);
            arguments.put("region", "江苏");
            return Map.copyOf(arguments);
        }
        List<String> mentionedCities = extractMentionedCities(question);
        if (mentionedCities.size() > 1) {
            return Map.of("cities", mentionedCities);
        }
        if (mentionedCities.size() == 1) {
            return Map.of("city", mentionedCities.get(0));
        }
        return Map.of("city", extractCity(question));
    }

    private boolean isJiangsuAllCitiesRequest(String question) {
        return question.contains("江苏") && containsAny(
            question,
            "所有城市", "全部城市", "各城市", "各市", "各地", "全省",
            "地级市", "十三个城市", "13个城市"
        );
    }

    private List<String> extractMentionedCities(String question) {
        return KNOWN_CITIES.stream()
            .filter(question::contains)
            .distinct()
            .sorted(Comparator.comparingInt(question::indexOf))
            .toList();
    }

    private String invoke(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = toolClients().get(toolName.toLowerCase(Locale.ROOT));
        if (client == null) {
            throw new IllegalStateException("MCP 工具未发现：" + toolName);
        }
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        } catch (RuntimeException ex) {
            invalidateDiscovery();
            throw ex;
        }
        String output = extractText(result == null ? List.of() : result.content());
        if (result == null) {
            throw new IllegalStateException("MCP 工具未返回结果：" + toolName);
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(
                "MCP 工具执行错误：" + toolName
                    + (output.isBlank() ? "" : "（" + output + "）")
            );
        }
        if (output.isBlank()) {
            throw new IllegalStateException("MCP 工具返回空结果：" + toolName);
        }
        return output;
    }

    private Map<String, McpSyncClient> toolClients() {
        if (initialized) {
            return clientsByTool;
        }
        synchronized (initializationMonitor) {
            if (initialized) {
                return clientsByTool;
            }
            try {
                if (clients.isEmpty()) {
                    log.warn("MCP is enabled but no stdio client was auto-configured");
                    clientsByTool = Map.of();
                    discoveredToolNames = List.of();
                    initialized = true;
                    return clientsByTool;
                }
                Map<String, McpSyncClient> discovered = new LinkedHashMap<>();
                Map<String, McpToolDefinition> definitions = new LinkedHashMap<>();
                for (McpSyncClient client : clients) {
                    if (!client.isInitialized()) {
                        client.initialize();
                    }
                    McpSchema.ListToolsResult listed = client.listTools();
                    if (listed == null || listed.tools() == null) {
                        continue;
                    }
                    listed.tools().forEach(tool -> {
                        String normalizedName = tool.name().toLowerCase(Locale.ROOT);
                        String ownerId = ownerForTool(normalizedName);
                        if (ownerId == null) {
                            log.info("Ignoring MCP tool without a cockpit capability mapping: {}", tool.name());
                            return;
                        }
                        discovered.put(normalizedName, client);
                        definitions.put(normalizedName, new McpToolDefinition(
                            ownerId,
                            tool.name(),
                            tool.description() == null ? "" : tool.description(),
                            schemaMap(tool.inputSchema())
                        ));
                    });
                }
                if (discovered.isEmpty()) {
                    throw new IllegalStateException("MCP 服务未暴露任何工具");
                }
                clientsByTool = Map.copyOf(discovered);
                definitionsByTool = Map.copyOf(definitions);
                discoveredToolNames = List.copyOf(discovered.keySet());
                initialized = true;
                log.info("Discovered MCP tools: {}", discoveredToolNames);
                return clientsByTool;
            } catch (RuntimeException ex) {
                clientsByTool = Map.of();
                definitionsByTool = Map.of();
                discoveredToolNames = List.of();
                initialized = false;
                log.warn("MCP initialization failed and will be retried", ex);
                throw ex;
            }
        }
    }

    private String extractText(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
            .map(item -> item instanceof McpSchema.TextContent text
                ? text.text()
                : String.valueOf(item))
            .filter(text -> text != null && !text.isBlank())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private void invalidateDiscovery() {
        synchronized (initializationMonitor) {
            initialized = false;
            clientsByTool = Map.of();
            definitionsByTool = Map.of();
            discoveredToolNames = List.of();
        }
    }

    private Map<String, Object> schemaMap(McpSchema.JsonSchema schema) {
        if (schema == null) return Map.of("type", "object");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema.type() == null ? "object" : schema.type());
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            result.put("properties", schema.properties());
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }
        if (schema.additionalProperties() != null) {
            result.put("additionalProperties", schema.additionalProperties());
        }
        return Map.copyOf(result);
    }

    private static String ownerForTool(String toolName) {
        return switch (toolName.toLowerCase(Locale.ROOT)) {
            case "queryweather" -> "weather";
            case "getcurrenttime" -> "time";
            case "calculate" -> "calculator";
            case "maps_district", "maps_weather", "maps_geo", "maps_text_search" -> "amap";
            default -> null;
        };
    }

    private String extractCity(String question) {
        String normalized = question;
        for (String noise : List.of(
            "帮我查一下", "帮我查询", "我想知道", "麻烦查询", "告诉我",
            "请问", "帮我查", "查询一下", "查一下", "想知道", "查询", "看看",
            "今天", "今日", "明天", "后天", "现在", "当前", "实时", "此刻",
            "本地", "当地", "这里", "那边", "所有", "全部", "各个", "城市",
            "全省", "的"
        )) {
            normalized = normalized.replace(noise, "");
        }
        Matcher matcher = Pattern.compile(
            "([\\p{IsHan}A-Za-z]{2,30})(?:市)?(?:的)?(?:天气|气温|温度)"
        ).matcher(normalized);
        return matcher.find() ? matcher.group(1) : "常州";
    }

    private String extractTimezone(String question) {
        Map<String, String> known = Map.of(
            "上海", "Asia/Shanghai",
            "北京", "Asia/Shanghai",
            "东京", "Asia/Tokyo",
            "纽约", "America/New_York",
            "伦敦", "Europe/London",
            "巴黎", "Europe/Paris"
        );
        return known.entrySet().stream()
            .filter(entry -> question.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("Asia/Shanghai");
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
            ? current.getClass().getSimpleName()
            : message;
    }

    private static Map<String, McpToolOption> catalogById() {
        Map<String, McpToolOption> result = new LinkedHashMap<>();
        CATALOG.forEach(option -> result.put(option.id(), option));
        return Map.copyOf(result);
    }
}
