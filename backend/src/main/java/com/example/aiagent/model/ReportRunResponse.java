package com.example.aiagent.model;

import java.time.Instant;

public record ReportRunResponse(long id, long templateId, String name, String status, String summary, String metricsJson, String chartSpec, String logs, Instant createdAt) {
}
