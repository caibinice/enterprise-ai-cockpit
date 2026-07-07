package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record ChatStreamRequest(String conversationId, @NotBlank String message, List<Long> knowledgeBaseIds, Map<String, String> metadataFilter, boolean enableTools, boolean enableChart) {
}
