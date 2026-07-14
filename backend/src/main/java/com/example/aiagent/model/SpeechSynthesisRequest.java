package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;

public record SpeechSynthesisRequest(@NotBlank String text, String voice) {
}
