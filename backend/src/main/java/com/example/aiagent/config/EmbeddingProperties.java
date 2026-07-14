package com.example.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
    String mode,
    int dimensions,
    String baseUrl,
    String apiKey,
    String model
) {
    public EmbeddingProperties {
        mode = mode == null || mode.isBlank() ? "local" : mode;
        dimensions = dimensions <= 0 ? 1536 : dimensions;
    }
}
