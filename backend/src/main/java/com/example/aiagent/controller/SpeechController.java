package com.example.aiagent.controller;

import com.example.aiagent.model.SpeechSynthesisRequest;
import com.example.aiagent.model.SpeechSynthesisResponse;
import com.example.aiagent.model.SpeechTranscriptionResponse;
import com.example.aiagent.service.SpeechService;
import jakarta.validation.Valid;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/speech")
public class SpeechController {
    private final SpeechService speechService;
    public SpeechController(SpeechService speechService) { this.speechService = speechService; }

    @PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SpeechTranscriptionResponse> transcribe(@RequestPart("audio") FilePart audio) {
        return DataBufferUtils.join(audio.content())
            .map(buffer -> readAudio(audio, buffer))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/synthesize")
    public SpeechSynthesisResponse synthesize(@Valid @RequestBody SpeechSynthesisRequest request) {
        return speechService.synthesize(request.text());
    }

    private SpeechTranscriptionResponse readAudio(FilePart audio, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            String contentType = audio.headers().getContentType() == null ? "application/octet-stream" : audio.headers().getContentType().toString();
            return speechService.transcribe(contentType, bytes);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
