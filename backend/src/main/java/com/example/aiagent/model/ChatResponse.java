package com.example.aiagent.model;

import java.util.List;

public record ChatResponse(String conversationId, String answer, boolean llmEnabled, List<RetrievedKnowledgeChunk> references, String chartSpec) {
}
