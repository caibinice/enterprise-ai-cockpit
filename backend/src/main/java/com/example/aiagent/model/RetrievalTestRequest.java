package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record RetrievalTestRequest(
    @NotBlank @Size(max = 12000) String query,
    List<Long> knowledgeBaseIds,
    Map<String, String> metadataFilter,
    Integer topK
) {
}
