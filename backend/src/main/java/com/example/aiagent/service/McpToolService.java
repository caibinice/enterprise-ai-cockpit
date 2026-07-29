package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.McpToolOption;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpToolService {
    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private static final Pattern EXPRESSION = Pattern.compile("[-+*/().\\d\\s]{3,}");
    private static final List<McpToolOption> CATALOG = List.of(
        new McpToolOption(
            "weather",
            "实时天气",
            "通过 Open-Meteo 查询城市当前天气、体感温度、湿度与风速。",
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
        )
    );
    private static final Map<String, McpToolOption> BY_ID = catalogById();

    private final List<McpSyncClient> clients;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile List<ToolCallback> callbacks = List.of();

    public McpToolService(
        ObjectProvider<List<McpSyncClient>> clients,
        ObjectMapper objectMapper
    ) {
        this.clients = clients.getIfAvailable(List::of);
        this.objectMapper = objectMapper;
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

    public List<McpExecutionResult> executeSelected(
        String question,
        List<String> selectedIds
    ) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return List.of();
        }
        List<McpExecutionResult> results = new ArrayList<>();
        for (String rawId : new java.util.LinkedHashSet<>(selectedIds)) {
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
            try {
                results.add(new McpExecutionResult(
                    id,
                    option.name(),
                    "success",
                    invoke(option.toolName(), arguments)
                ));
            } catch (RuntimeException ex) {
                log.warn("MCP tool {} failed: {}", option.toolName(), ex.getMessage());
                results.add(new McpExecutionResult(
                    id,
                    option.name(),
                    "error",
                    "工具调用失败，请稍后重试。"
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
            return "enabled=true, clients=" + clients.size() + ", tools="
                + callbacks().stream()
                    .map(candidate -> candidate.getToolDefinition().name())
                    .toList();
        } catch (Exception ex) {
            return "error: " + ex.getMessage();
        }
    }

    public String configuredStatus() {
        return "enabled=true, clients=" + clients.size()
            + ", initialized=" + initialized.get()
            + (initialized.get()
                ? ", tools=" + callbacks.stream()
                    .map(candidate -> candidate.getToolDefinition().name())
                    .toList()
                : "");
    }

    private Map<String, Object> inferredArguments(String id, String question) {
        String text = question == null ? "" : question.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "weather" -> containsAny(lower, "天气", "气温", "温度", "下雨", "weather")
                ? Map.of("city", extractCity(text))
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

    private String invoke(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = callbacks().stream()
            .filter(candidate -> {
                String discovered = candidate.getToolDefinition().name();
                return discovered.equalsIgnoreCase(toolName)
                    || discovered.toLowerCase(Locale.ROOT).endsWith(
                        "_" + toolName.toLowerCase(Locale.ROOT)
                    );
            })
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "MCP 工具未发现：" + toolName
            ));
        try {
            String raw = callback.call(objectMapper.writeValueAsString(arguments));
            return extractText(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "MCP 工具调用失败：" + toolName,
                ex
            );
        }
    }

    private String extractText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.isTextual()) {
                return root.asText();
            }
            if (root.isArray() && !root.isEmpty()) {
                JsonNode first = root.get(0);
                return first.path("text").isTextual()
                    ? first.path("text").asText()
                    : first.toString();
            }
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                JsonNode first = content.get(0);
                return first.path("text").isTextual()
                    ? first.path("text").asText()
                    : first.toString();
            }
        } catch (Exception ignored) {
            return raw;
        }
        return raw;
    }

    private List<ToolCallback> callbacks() {
        if (initialized.compareAndSet(false, true)) {
            try {
                if (clients.isEmpty()) {
                    log.warn("MCP is enabled but no stdio client was auto-configured");
                    callbacks = List.of();
                } else {
                    clients.forEach(McpSyncClient::initialize);
                    callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(clients);
                    log.info(
                        "Discovered MCP tools: {}",
                        callbacks.stream()
                            .map(candidate -> candidate.getToolDefinition().name())
                            .toList()
                    );
                }
            } catch (RuntimeException ex) {
                log.warn("MCP initialization failed", ex);
                callbacks = List.of();
                throw ex;
            }
        }
        return callbacks;
    }

    private String extractCity(String question) {
        for (String city : List.of(
            "上海", "常州", "南京", "北京", "深圳", "广州", "杭州",
            "苏州", "成都", "重庆", "东京", "纽约", "伦敦", "巴黎"
        )) {
            if (question.contains(city)) {
                return city;
            }
        }
        Matcher matcher = Pattern.compile(
            "([\\p{IsHan}A-Za-z]{2,30})(?:市)?(?:的)?(?:天气|气温|温度)"
        ).matcher(question);
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

    private static Map<String, McpToolOption> catalogById() {
        Map<String, McpToolOption> result = new LinkedHashMap<>();
        CATALOG.forEach(option -> result.put(option.id(), option));
        return Map.copyOf(result);
    }
}
