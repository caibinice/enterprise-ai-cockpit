package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MockModelGateway implements ModelGateway {
    private final ObjectMapper objectMapper;

    public MockModelGateway(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public boolean enabled() { return false; }
    @Override public String provider() { return "local-rag"; }

    @Override
    public String answer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        if (references.isEmpty()) {
            return "No matching enterprise knowledge base evidence was found. Import documents or relax metadata filters. Question: " + question;
        }
        StringBuilder sb = new StringBuilder("Local RAG summary based on retrieved enterprise knowledge:\n");
        int i = 1;
        for (RetrievedKnowledgeChunk ref : references) {
            sb.append(i++).append(". From ").append(ref.title()).append(": ").append(abbreviate(ref.content(), 140)).append("\n");
        }
        if (question != null && question.toLowerCase().contains("chart")) sb.append("\nA chart specification is included in the chart event.");
        return sb.toString();
    }

    @Override
    public Flux<String> streamAnswer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        String answer = answer(question, references, model);
        java.util.List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < answer.length(); i += 48) chunks.add(answer.substring(i, Math.min(answer.length(), i + 48)));
        return Flux.fromIterable(chunks.isEmpty() ? List.of("") : chunks);
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        String title = (question == null || question.isBlank()) ? "Enterprise Metrics" : question;
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "echarts");
        spec.put("title", Map.of("text", title));
        spec.put("tooltip", Map.of());
        spec.put("legend", Map.of("data", List.of("Revenue")));
        spec.put("xAxis", Map.of("type", "category", "data", List.of("East", "South", "North")));
        spec.put("yAxis", Map.of("type", "value"));
        spec.put("series", List.of(Map.of("name", "Revenue", "type", "bar", "data", List.of(120, 95, 88))));
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Chart specification serialization failed", ex);
        }
    }

    private String abbreviate(String text, int max) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }
}
