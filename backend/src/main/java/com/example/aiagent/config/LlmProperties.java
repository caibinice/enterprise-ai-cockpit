package com.example.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(boolean enabled, String provider, String baseUrl, String apiKey, String model) {
}
