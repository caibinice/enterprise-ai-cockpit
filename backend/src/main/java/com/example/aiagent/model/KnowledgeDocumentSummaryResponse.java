package com.example.aiagent.model;

import java.time.Instant;
import java.util.Map;

public record KnowledgeDocumentSummaryResponse(
    long id,
    long knowledgeBaseId,
    String title,
    Map<String, String> metadata,
    int chunks,
    Instant createdAt
) {
    public static KnowledgeDocumentSummaryResponse from(
        KnowledgeDocumentResponse document
    ) {
        return new KnowledgeDocumentSummaryResponse(
            document.id(),
            document.knowledgeBaseId(),
            document.title(),
            document.metadata(),
            document.chunks(),
            document.createdAt()
        );
    }
}
