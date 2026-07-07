package com.example.aiagent.model;

import java.time.Instant;

public record KnowledgeBaseResponse(long id, String name, String description, String code, long documentCount, Instant createdAt) {
}
