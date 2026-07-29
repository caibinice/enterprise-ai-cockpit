package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;
import reactor.core.publisher.Flux;

public interface ModelGateway {
    boolean enabled();
    default String provider() {
        return enabled() ? "configured" : "local-rag";
    }
    String answer(String question, List<RetrievedKnowledgeChunk> references, String model);
    default String answer(String question, List<RetrievedKnowledgeChunk> references) {
        return answer(question, references, null);
    }
    default Flux<String> streamAnswer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        return Flux.just(answer(question, references, model));
    }
    default Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references) {
        return streamAnswer(question, references, null);
    }
    String chart(String question, List<RetrievedKnowledgeChunk> references);
}
