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
        if (references.isEmpty()) return "???????????????????????????????????/metadata ?????\n???" + question;
        StringBuilder sb = new StringBuilder("???????????????????\n");
        int i = 1;
        for (RetrievedKnowledgeChunk ref : references) {
            sb.append(i++).append(". ???").append(ref.title()).append("??").append(abbreviate(ref.content(), 140)).append("\n");
        }
        if (question != null && question.contains("?")) sb.append("\n????????????? ");
        return sb.toString();
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        String title = (question == null || question.isBlank()) ? "??????" : question;
        return """
            {"type":"echarts","title":{"text":"%s"},"tooltip":{},"legend":{"data":["???"]},"xAxis":{"type":"category","data":["??","??","??"]},"yAxis":{"type":"value"},"series":[{"name":"???","type":"bar","data":[120,95,88]}]}
            """.formatted(escape(title));
    }

    private String abbreviate(String text, int max) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }
    private String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
