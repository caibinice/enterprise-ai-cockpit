package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeBaseRequest(@NotBlank String name, String description, String code) {
}
