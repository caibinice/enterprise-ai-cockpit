package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;

public interface ModelGateway {
    boolean enabled();
    String answer(String question, List<RetrievedKnowledgeChunk> references);
    String chart(String question, List<RetrievedKnowledgeChunk> references);
}
