package com.example.aiagent.model;

import java.util.List;

public record RetrievalTestResponse(
    String query,
    String strategy,
    int total,
    List<RetrievedKnowledgeChunk> hits
) {
}
