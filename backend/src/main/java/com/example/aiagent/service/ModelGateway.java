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
    /**
     * Requests a JSON object for agent planning or final response rendering.
     * Providers that support JSON mode should override this method. The default
     * keeps local/test gateways source-compatible and relies on the caller's
     * strict parser and fallback policy.
     */
    default String jsonAnswer(
        String systemPrompt,
        String userPrompt,
        String model,
        int maxTokens
    ) {
        return answer(systemPrompt + "\n\n" + userPrompt, List.of(), model);
    }
    String chart(String question, List<RetrievedKnowledgeChunk> references);
}
