package com.example.aiagent.controller;

import com.example.aiagent.model.SpeechSynthesisRequest;
import com.example.aiagent.model.SpeechSynthesisResponse;
import com.example.aiagent.model.SpeechTranscriptionResponse;
import com.example.aiagent.service.SpeechService;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/speech")
public class SpeechController {
    private final SpeechService speechService;
    public SpeechController(SpeechService speechService) { this.speechService = speechService; }

    @PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpeechTranscriptionResponse transcribe(@RequestPart("audio") MultipartFile audio) throws IOException {
        return speechService.transcribe(audio.getContentType(), audio.getBytes());
    }

    @PostMapping("/synthesize")
    public SpeechSynthesisResponse synthesize(@Valid @RequestBody SpeechSynthesisRequest request) {
        return speechService.synthesize(request.text());
    }
}
