package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
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
    private final ChatModelCatalog modelCatalog;
    private final MockModelGateway fallback;

    public SpringAiModelGateway(
        ChatClient.Builder chatClientBuilder,
        com.example.aiagent.config.LlmProperties properties,
        ChatModelCatalog modelCatalog,
        com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.modelCatalog = modelCatalog;
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
    public String provider() {
        return enabled() ? "spring-ai" : "local-rag";
    }

    @Override
    public String answer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        String selectedModel = modelCatalog.resolve(model);
        if (!enabled()) return fallback.answer(question, references, selectedModel);
        try {
            String content = request(question, references, selectedModel).call().content();
            return content == null || content.isBlank()
                ? fallback.answer(question, references, selectedModel)
                : content;
        } catch (Exception ex) {
            return "模型服务暂时不可用，已切换为本地 RAG 摘要。\n\n"
                + fallback.answer(question, references, selectedModel);
        }
    }

    @Override
    public Flux<String> streamAnswer(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        String selectedModel = modelCatalog.resolve(model);
        if (!enabled()) return fallback.streamAnswer(question, references, selectedModel);
        return Flux.defer(() -> request(question, references, selectedModel).stream().content())
            .filter(text -> text != null && !text.isEmpty())
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(ex -> Flux.just(
                "模型流式服务暂时不可用，已切换为本地 RAG 摘要。\n\n"
                    + fallback.answer(question, references, selectedModel)
            ));
    }

    @Override
    public String chart(String question, List<RetrievedKnowledgeChunk> references) {
        return fallback.chart(question, references);
    }

    private ChatClient.ChatClientRequestSpec request(
        String question,
        List<RetrievedKnowledgeChunk> references,
        String model
    ) {
        List<Message> messages = List.of(
            new SystemMessage(systemPrompt()),
            new UserMessage("知识库证据：\n" + buildContext(references) + "\n用户问题：\n" + question)
        );
        int maxTokens = ChatModelCatalog.PRO.equals(model) ? 4096 : 2048;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.2)
            .maxTokens(maxTokens)
            .build();
        return chatClient.prompt().messages(messages).options(options);
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

    private String systemPrompt() {
        return ModelPromptPolicy.SYSTEM_PROMPT;
    }
}
