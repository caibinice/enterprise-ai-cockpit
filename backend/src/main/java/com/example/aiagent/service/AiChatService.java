package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiChatService {
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    public AiChatService(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService, ModelGateway modelGateway, ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
    }

    public List<StreamEvent> stream(ChatStreamRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank() ? UUID.randomUUID().toString() : request.conversationId();
        List<RetrievedKnowledgeChunk> references = knowledgeBaseService.search(request.message(), request.knowledgeBaseIds(), request.metadataFilter(), 6);
        String answer = modelGateway.answer(request.message(), references);
        repository.saveChatMessage(conversationId, "user", request.message());
        repository.saveChatMessage(conversationId, "assistant", answer);
        List<StreamEvent> events = new ArrayList<>();
        events.add(new StreamEvent("meta", json(MapLike.of("conversationId", conversationId, "llmEnabled", modelGateway.enabled()))));
        for (String token : splitForStream(answer)) events.add(new StreamEvent("token", token));
        events.add(new StreamEvent("references", json(references)));
        if (request.enableTools()) events.add(new StreamEvent("tool", "??????????????????????????????????????"));
        if (request.enableChart()) events.add(new StreamEvent("chart", modelGateway.chart(request.message(), references)));
        events.add(new StreamEvent("done", json(MapLike.of("conversationId", conversationId))));
        return events;
    }

    public ChatResponse chat(ChatStreamRequest request) {
        var events = stream(request);
        String conversationId = events.stream().filter(e -> e.event().equals("meta")).findFirst().map(StreamEvent::data).orElse("");
        StringBuilder answer = new StringBuilder();
        String chart = null;
        for (StreamEvent e : events) {
            if (e.event().equals("token")) answer.append(e.data());
            if (e.event().equals("chart")) chart = e.data();
        }
        List<RetrievedKnowledgeChunk> references = knowledgeBaseService.search(request.message(), request.knowledgeBaseIds(), request.metadataFilter(), 6);
        return new ChatResponse(conversationId, answer.toString(), modelGateway.enabled(), references, chart);
    }

    private List<String> splitForStream(String answer) {
        List<String> tokens = new ArrayList<>();
        if (answer == null || answer.isEmpty()) return List.of("");
        int size = 48;
        for (int i = 0; i < answer.length(); i += size) {
            tokens.add(answer.substring(i, Math.min(answer.length(), i + size)));
        }
        return tokens;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return String.valueOf(value); }
    }

    private record MapLike() {
        static java.util.Map<String, Object> of(String k1, Object v1) { return java.util.Map.of(k1, v1); }
        static java.util.Map<String, Object> of(String k1, Object v1, String k2, Object v2) { return java.util.Map.of(k1, v1, k2, v2); }
    }
}
