package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;
import reactor.core.publisher.Flux;

public interface ModelGateway {
    boolean enabled();
    String answer(String question, List<RetrievedKnowledgeChunk> references);
    default Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references) {
        return Flux.just(answer(question, references));
    }
    String chart(String question, List<RetrievedKnowledgeChunk> references);
}
