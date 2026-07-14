package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;
import java.util.Map;

public interface VectorIndexService {
    boolean enabled();
    void upsert(RetrievedKnowledgeChunk chunk);
    void delete(long chunkId);
    List<RetrievedKnowledgeChunk> search(String query, List<Long> knowledgeBaseIds, int topK);
    String status();
}
