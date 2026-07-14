package com.example.aiagent.model;

import jakarta.validation.constraints.NotBlank;

public record ReportTemplateRequest(@NotBlank String name, String scheduleType, String cron, String dataSourceKey, long knowledgeBaseId, String prompt, String dimensions, boolean enabled) {
}
