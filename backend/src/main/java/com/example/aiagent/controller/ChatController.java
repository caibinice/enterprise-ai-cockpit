package com.example.aiagent.controller;

import com.example.aiagent.model.ChatResponse;
import com.example.aiagent.model.ChatStreamRequest;
import com.example.aiagent.model.StreamEvent;
import com.example.aiagent.service.AiChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.codec.ServerSentEvent;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final AiChatService chatService;

    public ChatController(AiChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatStreamRequest request) {
        return Mono.fromCallable(() -> chatService.chat(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatStreamRequest request) {
        return chatService.streamReactive(request)
            .map(this::toSse)
            .onErrorResume(error -> Flux.just(
                ServerSentEvent.<String>builder().event("error").data(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()).build(),
                ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            ));
    }

    private ServerSentEvent<String> toSse(StreamEvent event) {
        return ServerSentEvent.<String>builder().event(event.event()).data(event.data()).build();
    }
}
