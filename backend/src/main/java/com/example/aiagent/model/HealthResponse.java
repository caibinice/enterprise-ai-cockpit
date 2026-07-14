package com.example.aiagent.model;

public record HealthResponse(String status, String mode, String repository, String vectorStore, String mcp,
                             long knowledgeBases, long documents, long chunks, long reports) {
}
