package com.example.aiagent.controller;

import com.example.aiagent.model.ChatResponse;
import com.example.aiagent.model.ChatStreamRequest;
import com.example.aiagent.model.StreamEvent;
import com.example.aiagent.service.AiChatService;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final AiChatService chatService;
    public ChatController(AiChatService chatService) { this.chatService = chatService; }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatStreamRequest request) { return chatService.chat(request); }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread worker = new Thread(() -> {
            try {
                for (StreamEvent event : chatService.stream(request)) {
                    emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
                }
                emitter.complete();
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            } catch (Exception ex) {
                try { emitter.send(SseEmitter.event().name("error").data(ex.getMessage())); } catch (IOException ignored) { }
                emitter.complete();
            }
        }, "chat-sse-stream");
        worker.start();
        return emitter;
    }
}
