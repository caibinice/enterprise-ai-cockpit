package com.example.aiagent.controller;

import com.example.aiagent.model.SpeechSynthesisRequest;
import com.example.aiagent.model.SpeechSynthesisResponse;
import com.example.aiagent.model.SpeechTranscriptionResponse;
import com.example.aiagent.service.SpeechService;
import jakarta.validation.Valid;
import java.util.concurrent.Callable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/speech")
public class SpeechController {
    private static final int MAX_AUDIO_BYTES = 25 * 1024 * 1024;
    private final SpeechService speechService;
    public SpeechController(SpeechService speechService) { this.speechService = speechService; }

    @PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SpeechTranscriptionResponse> transcribe(@RequestPart("audio") FilePart audio) {
        String contentType = audio.headers().getContentType() == null ? "application/octet-stream" : audio.headers().getContentType().toString();
        return DataBufferUtils.join(audio.content(), MAX_AUDIO_BYTES)
            .flatMap(buffer -> Mono.using(
                () -> buffer,
                value -> fromBlocking(() -> copy(value)),
                DataBufferUtils::release
            ))
            .flatMap(bytes -> fromBlocking(() -> speechService.transcribe(contentType, bytes)))
            .onErrorMap(DataBufferLimitException.class,
                error -> new IllegalArgumentException("Audio exceeds the 25 MB limit", error));
    }

    @PostMapping("/synthesize")
    public Mono<SpeechSynthesisResponse> synthesize(@Valid @RequestBody SpeechSynthesisRequest request) {
        return fromBlocking(() -> speechService.synthesize(request.text()));
    }

    private byte[] copy(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return bytes;
    }

    private <T> Mono<T> fromBlocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
