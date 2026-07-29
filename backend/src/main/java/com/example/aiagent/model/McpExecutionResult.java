package com.example.aiagent.model;

public record McpExecutionResult(
    String id,
    String name,
    String status,
    String output
) {
}
