package com.example.aiagent.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record McpToolCall(String name, Map<String, Object> arguments) {
    public McpToolCall {
        if (arguments == null || arguments.isEmpty()) {
            arguments = Map.of();
        } else {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            arguments.forEach((key, value) -> {
                if (key != null && value != null) sanitized.put(key, value);
            });
            arguments = Map.copyOf(sanitized);
        }
    }
}
