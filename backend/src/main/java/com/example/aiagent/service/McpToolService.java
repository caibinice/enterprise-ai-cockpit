package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.example.aiagent.model.McpToolOption;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
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
    private final Object initializationMonitor = new Object();
    private volatile boolean initialized;
    private volatile Map<String, McpSyncClient> clientsByTool = Map.of();
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
                for (McpSyncClient client : clients) {
                    if (!client.isInitialized()) {
                        client.initialize();
                    }
                    McpSchema.ListToolsResult listed = client.listTools();
                    if (listed == null || listed.tools() == null) {
                        continue;
                    }
                    listed.tools().forEach(tool -> discovered.put(
                        tool.name().toLowerCase(Locale.ROOT),
                        client
                    ));
                }
                if (discovered.isEmpty()) {
                    throw new IllegalStateException("MCP 服务未暴露任何工具");
                }
                clientsByTool = Map.copyOf(discovered);
                discoveredToolNames = List.copyOf(discovered.keySet());
                initialized = true;
                log.info("Discovered MCP tools: {}", discoveredToolNames);
                return clientsByTool;
            } catch (RuntimeException ex) {
                clientsByTool = Map.of();
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
            discoveredToolNames = List.of();
        }
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
        String normalized = question;
        for (String noise : List.of(
            "帮我查一下", "帮我查询", "我想知道", "麻烦查询", "告诉我",
            "请问", "帮我查", "查询一下", "查一下", "想知道", "查询", "看看",
            "今天", "今日", "明天", "后天", "现在", "当前", "实时", "此刻",
            "本地", "当地", "这里", "那边", "的"
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
