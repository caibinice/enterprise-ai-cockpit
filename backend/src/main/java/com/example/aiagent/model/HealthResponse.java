package com.example.aiagent.model;

public record HealthResponse(String status, String mode, long knowledgeBases, long documents, long chunks, long reports) {
}
