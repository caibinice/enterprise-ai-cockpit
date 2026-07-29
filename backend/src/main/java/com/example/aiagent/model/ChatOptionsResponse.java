package com.example.aiagent.model;

import java.util.List;

public record ChatOptionsResponse(
    String defaultModel,
    List<ModelOption> models,
    boolean mcpEnabled,
    List<McpToolOption> mcpTools
) {
}
