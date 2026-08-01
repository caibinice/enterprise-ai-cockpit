package com.example.aiagent.model;

import java.util.Map;

public record McpToolDefinition(
    String ownerId,
    String name,
    String description,
    Map<String, Object> inputSchema
) {
}
