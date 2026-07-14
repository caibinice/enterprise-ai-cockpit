package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Spring AI ChatClient implementation. Its stream() path is a real Reactor stream. */
@Service
@ConditionalOnProperty(prefix = "app.llm", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "spring-ai", matchIfMissing = true)
public class SpringAiModelGateway implements ModelGateway {
    private final ChatClient chatClient;
    private final com.example.aiagent.config.LlmProperties properties;
    private final MockModelGateway fallback;

    public SpringAiModelGateway(
        ChatClient.Builder chatClientBuilder,
        com.example.aiagent.config.LlmProperties properties,
        com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.fallback = new MockModelGateway(objectMapper);
    }

    @Override
    public boolean enabled() {
        return properties.enabled()
            && properties.apiKey() != null
            && !properties.apiKey().isBlank()
            && !"demo-key".equals(properties.apiKey());
    }

    @Override
    public String answer(String question, List<RetrievedKnowledgeChunk> references) {
        if (!enabled()) return fallback.answer(question, references);
        try {
            String content = request(question, references).call().content();
            return content == null || content.isBlank() ? fallback.answer(question, references) : content;
        } catch (Exception ex) {
            return "LLM call failed; falling back to local RAG summary. Error: " + ex.getMessage() + "\n\n" + fallback.answer(question, references);
        }
    }

    @Override
    public Flux<String> streamAnswer(String question, List<RetrievedKnowledgeChunk> references) {
        if (!enabled()) return fallback.streamAnswer(question, references);
        return Flux.defer(() -> request(question, references).stream().content())
            .filter(text -> text != null && !text.isEmpty())
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(ex -> Flux.just("LLM stream failed; using local RAG fallback. Error: " + ex.getMessage() + "\n\n" + fallback.answer(question, references)));
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        return fallback.chart(question, references);
    }

    private ChatClient.ChatClientRequestSpec request(String question, List<RetrievedKnowledgeChunk> references) {
        List<Message> messages = List.of(
            new SystemMessage("You are an enterprise AI cockpit assistant. Prefer retrieved knowledge base evidence. If evidence is insufficient, say so clearly. Answer in the user's requested language."),
            new UserMessage("Knowledge base evidence:\n" + buildContext(references) + "\nUser question:\n" + question)
        );
        return chatClient.prompt().messages(messages);
    }

    private String buildContext(List<RetrievedKnowledgeChunk> references) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RetrievedKnowledgeChunk ref = references.get(i);
            context.append("[Reference ").append(i + 1).append(" | ").append(ref.title()).append("]\n")
                .append(ref.content()).append("\nmetadata=").append(ref.metadata()).append("\n\n");
        }
        return context.toString();
    }
}
