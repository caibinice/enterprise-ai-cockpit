package com.example.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Lazy MCP client for the stdio weather example copied from the reference demo. */
@Service
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpWeatherService {
    private static final Logger log = LoggerFactory.getLogger(McpWeatherService.class);

    private final List<McpSyncClient> clients;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile List<ToolCallback> callbacks = List.of();

    public McpWeatherService(ObjectProvider<List<McpSyncClient>> clients, ObjectMapper objectMapper) {
        this.clients = clients.getIfAvailable(List::of);
        this.objectMapper = objectMapper;
    }

    public String queryWeather(String city) {
        List<ToolCallback> tools = callbacks();
        ToolCallback tool = tools.stream()
            .filter(candidate -> candidate.getToolDefinition().name().toLowerCase().contains("queryweather"))
            .findFirst()
            .orElseGet(() -> tools.size() == 1 ? tools.get(0) : null);
        if (tool == null) throw new IllegalStateException("MCP queryWeather tool was not discovered; tools="
            + tools.stream().map(candidate -> candidate.getToolDefinition().name()).toList());
        try {
            String raw = tool.call(objectMapper.writeValueAsString(Map.of("city", normalizeCity(city))));
            var result = objectMapper.readTree(raw);
            var text = result.isArray() && result.size() > 0 ? result.get(0).path("text") : null;
            return text != null && text.isTextual() ? text.asText() : raw;
        } catch (Exception ex) {
            throw new IllegalStateException("MCP weather call failed: " + ex.getMessage(), ex);
        }
    }

    public String queryIfRequested(String question) {
        if (question == null || !(question.contains("天气") || question.toLowerCase().contains("weather"))) return "";
        return "MCP queryWeather result: " + queryWeather(extractCity(question));
    }

    public String status() {
        try {
            return "enabled=true, clients=" + clients.size() + ", tools=" + callbacks().stream().map(c -> c.getToolDefinition().name()).toList();
        } catch (Exception ex) {
            return "error: " + ex.getMessage();
        }
    }

    /** Does not initialize the blocking sync client; safe to call from a health endpoint. */
    public String configuredStatus() {
        return "enabled=true, clients=" + clients.size() + ", initialized=" + initialized.get()
            + (initialized.get() ? ", tools=" + callbacks.stream().map(c -> c.getToolDefinition().name()).toList() : "");
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
                    log.info("Discovered MCP tools: {}", callbacks.stream().map(c -> c.getToolDefinition().name()).toList());
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
        for (String city : List.of("上海", "常州", "南京", "北京", "深圳", "广州")) {
            if (question.contains(city)) return city;
        }
        return "常州";
    }

    private String normalizeCity(String city) {
        return city == null || city.isBlank() ? "常州" : city.trim();
    }
}
