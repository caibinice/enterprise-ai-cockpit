package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 1000) String description,
    @Size(max = 100) String code,
    @Size(max = 100) String businessType
) {
    public KnowledgeBaseRequest(String name, String description, String code) {
        this(name, description, code, "通用业务");
    }
}
