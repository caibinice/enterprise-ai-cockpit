package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public ReportService(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    public ReportTemplateResponse createTemplate(ReportTemplateRequest request) { return repository.saveReportTemplate(request); }
    public List<ReportTemplateResponse> listTemplates() { return repository.listReportTemplates(); }
    public void deleteTemplate(long id) { repository.deleteReportTemplate(id); }

    public ReportRunResponse runTemplate(long templateId) {
        ReportTemplateResponse template = repository.findReportTemplate(templateId).orElseThrow(() -> new IllegalArgumentException("Report template not found: " + templateId));
        return runTemplate(new ReportTemplateRequest(template.name(), template.scheduleType(), template.cron(), template.dataSourceKey(), template.knowledgeBaseId(), template.prompt(), template.dimensions(), template.enabled()), template.id());
    }

    public ReportRunResponse runTemplate(ReportTemplateRequest request) { return runTemplate(request, 0); }

    private ReportRunResponse runTemplate(ReportTemplateRequest request, long templateId) {
        if (request.knowledgeBaseId() > 0) {
            repository.findKnowledgeBase(request.knowledgeBaseId())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + request.knowledgeBaseId()));
        }
        String metricsJson = "[{\"region\":\"East\",\"amount\":120},{\"region\":\"South\",\"amount\":95},{\"region\":\"North\",\"amount\":88}]";
        String summary = "# " + request.name() + "\n\nGenerated from data source `" + nz(request.dataSourceKey()) + "`. East amount is 120, South is 95, and North is 88. East is the strongest contributor.\n\nDimensions: " + nz(request.dimensions()) + "\nPrompt: " + nz(request.prompt());
        String chart = chart(request.name());
        ReportRunResponse run = repository.saveReportRun(templateId, request.name(), "SUCCESS", summary, metricsJson, chart, "Mock data-source extraction succeeded; report was ingested into the knowledge base.");
        if (request.knowledgeBaseId() > 0) {
            knowledgeBaseService.importDocument(request.knowledgeBaseId(), request.name() + "#" + run.id(), summary + "\n\nMetrics: " + metricsJson, Map.of("report", "true", "template", request.name(), "source", nz(request.dataSourceKey())));
        }
        return run;
    }

    public List<ReportRunResponse> listRuns() { return repository.listReportRuns(); }
    public ReportRunResponse getRun(long id) { return repository.findReportRun(id).orElseThrow(() -> new IllegalArgumentException("Report run not found: " + id)); }

    @Scheduled(cron = "0 0/30 * * * ?")
    public void heartbeatScheduler() {
        // Scheduler hook for MVP. Real cron registration is represented by report template config.
    }

    private String chart(String title) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "echarts");
        spec.put("title", Map.of("text", title == null ? "Enterprise Metrics" : title));
        spec.put("tooltip", Map.of());
        spec.put("xAxis", Map.of("type", "category", "data", List.of("East", "South", "North")));
        spec.put("yAxis", Map.of("type", "value"));
        spec.put("series", List.of(Map.of("name", "Revenue", "type", "bar", "data", List.of(120, 95, 88))));
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Chart specification serialization failed", ex);
        }
    }
    private String nz(String value) { return value == null ? "" : value; }
}
