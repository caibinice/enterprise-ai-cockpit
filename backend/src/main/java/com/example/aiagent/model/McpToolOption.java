package com.example.aiagent.model;

public record McpToolOption(
    String id,
    String name,
    String description,
    String toolName,
    boolean available
) {
}
