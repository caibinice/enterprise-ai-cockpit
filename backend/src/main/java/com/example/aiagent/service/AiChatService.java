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
        ChatWork work = runChat(request);
        List<StreamEvent> events = new ArrayList<>();
        events.add(new StreamEvent("meta", json(MapLike.of("conversationId", work.conversationId(), "llmEnabled", modelGateway.enabled()))));
        for (String token : splitForStream(work.answer())) events.add(new StreamEvent("token", token));
        events.add(new StreamEvent("references", json(work.references())));
        if (request.enableTools()) events.add(new StreamEvent("tool", "Internal tools enabled: report lookup, data-source snapshot, and safe read-only short query."));
        if (request.enableChart()) events.add(new StreamEvent("chart", modelGateway.chart(request.message(), work.references())));
        events.add(new StreamEvent("done", json(MapLike.of("conversationId", work.conversationId()))));
        return events;
    }

    public ChatResponse chat(ChatStreamRequest request) {
        ChatWork work = runChat(request);
        String chart = request.enableChart() ? modelGateway.chart(request.message(), work.references()) : null;
        return new ChatResponse(work.conversationId(), work.answer(), modelGateway.enabled(), work.references(), chart);
    }

    private ChatWork runChat(ChatStreamRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank() ? UUID.randomUUID().toString() : request.conversationId();
        List<RetrievedKnowledgeChunk> references = knowledgeBaseService.search(request.message(), request.knowledgeBaseIds(), request.metadataFilter(), 6);
        String answer = modelGateway.answer(request.message(), references);
        repository.saveChatMessage(conversationId, "user", request.message());
        repository.saveChatMessage(conversationId, "assistant", answer);
        return new ChatWork(conversationId, answer, references);
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

    private record ChatWork(String conversationId, String answer, List<RetrievedKnowledgeChunk> references) {}
    private record MapLike() {
        static java.util.Map<String, Object> of(String k1, Object v1) { return java.util.Map.of(k1, v1); }
        static java.util.Map<String, Object> of(String k1, Object v1, String k2, Object v2) { return java.util.Map.of(k1, v1, k2, v2); }
    }
}
