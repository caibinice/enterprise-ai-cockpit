package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record ChatStreamRequest(
    String conversationId,
    @NotBlank @Size(max = 12000) String message,
    String model,
    List<Long> knowledgeBaseIds,
    Map<String, String> metadataFilter,
    List<String> mcpToolIds,
    boolean enableTools,
    boolean enableChart
) {
    public ChatStreamRequest(
        String conversationId,
        String message,
        List<Long> knowledgeBaseIds,
        Map<String, String> metadataFilter,
        boolean enableTools,
        boolean enableChart
    ) {
        this(
            conversationId,
            message,
            null,
            knowledgeBaseIds,
            metadataFilter,
            List.of(),
            enableTools,
            enableChart
        );
    }
}
