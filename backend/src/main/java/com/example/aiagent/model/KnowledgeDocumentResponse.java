package com.example.aiagent.model;

import java.time.Instant;
import java.util.Map;

public record KnowledgeDocumentResponse(long id, long knowledgeBaseId, String title, String content, Map<String, String> metadata, int chunks, Instant createdAt) {
}
