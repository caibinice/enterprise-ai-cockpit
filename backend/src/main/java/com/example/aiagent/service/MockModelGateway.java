package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MockModelGateway implements ModelGateway {
    private final ObjectMapper objectMapper;

    public MockModelGateway(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public boolean enabled() { return false; }

    @Override
    public String answer(String question, List<RetrievedKnowledgeChunk> references) {
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
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        String title = (question == null || question.isBlank()) ? "Enterprise Metrics" : question;
        return """
            {"type":"echarts","title":{"text":"%s"},"tooltip":{},"legend":{"data":["Revenue"]},"xAxis":{"type":"category","data":["East","South","North"]},"yAxis":{"type":"value"},"series":[{"name":"Revenue","type":"bar","data":[120,95,88]}]}
            """.formatted(escape(title));
    }

    private String abbreviate(String text, int max) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }
    private String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
