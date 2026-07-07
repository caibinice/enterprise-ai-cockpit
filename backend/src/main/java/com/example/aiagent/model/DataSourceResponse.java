package com.example.aiagent.model;

import java.time.Instant;
import java.util.Map;

public record DataSourceResponse(long id, String name, String type, String endpoint, String queryText, Map<String, String> config, Instant createdAt) {
}
