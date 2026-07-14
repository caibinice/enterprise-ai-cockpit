package com.example.aiagent.service;

import com.example.aiagent.model.SpeechSynthesisResponse;
import com.example.aiagent.model.SpeechTranscriptionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class SpeechService {
    private final ObjectMapper objectMapper;
    public SpeechService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public SpeechTranscriptionResponse transcribe(String contentType, byte[] audio) {
        String text = new String(audio == null ? new byte[0] : audio, StandardCharsets.UTF_8).trim();
        if (text.isBlank()) text = "Mock speech recognition result: analyze enterprise metrics and generate a chart.";
        return new SpeechTranscriptionResponse(text, "mock-openai-compatible");
    }

    public SpeechSynthesisResponse synthesize(String text) {
        byte[] pseudoWav = ("MOCK_WAV:" + (text == null ? "" : text)).getBytes(StandardCharsets.UTF_8);
        return new SpeechSynthesisResponse("data:audio/wav;base64," + Base64.getEncoder().encodeToString(pseudoWav), "mock-openai-compatible");
    }
}
