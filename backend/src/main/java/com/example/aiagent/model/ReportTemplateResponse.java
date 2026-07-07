package com.example.aiagent.model;

import java.time.Instant;

public record ReportTemplateResponse(long id, String name, String scheduleType, String cron, String dataSourceKey, long knowledgeBaseId, String prompt, String dimensions, boolean enabled, Instant createdAt) {
}
