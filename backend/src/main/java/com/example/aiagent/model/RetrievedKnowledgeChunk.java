package com.example.aiagent.model;

import java.util.Map;

public record RetrievedKnowledgeChunk(long id, long documentId, long knowledgeBaseId, String title, String content, double score, Map<String, String> metadata) {
}
