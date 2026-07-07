package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record DataSourceRequest(@NotBlank String name, @NotBlank String type, String endpoint, String queryText, Map<String, String> config) {
}
