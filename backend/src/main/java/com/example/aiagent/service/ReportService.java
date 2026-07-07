package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        ReportTemplateResponse template = repository.findReportTemplate(templateId).orElseThrow(() -> new IllegalArgumentException("???????: " + templateId));
        return runTemplate(new ReportTemplateRequest(template.name(), template.scheduleType(), template.cron(), template.dataSourceKey(), template.knowledgeBaseId(), template.prompt(), template.dimensions(), template.enabled()), template.id());
    }

    public ReportRunResponse runTemplate(ReportTemplateRequest request) { return runTemplate(request, 0); }

    private ReportRunResponse runTemplate(ReportTemplateRequest request, long templateId) {
        String metricsJson = "[{\"region\":\"??\",\"amount\":120},{\"region\":\"??\",\"amount\":95},{\"region\":\"??\",\"amount\":88}]";
        String summary = "# " + request.name() + "\n\n????? `" + nz(request.dataSourceKey()) + "` ?????????? 120??? 95??? 88?????????????????????\n\n?????" + nz(request.dimensions()) + "\n????" + nz(request.prompt());
        String chart = chart(request.name());
        ReportRunResponse run = repository.saveReportRun(templateId, request.name(), "SUCCESS", summary, metricsJson, chart, "mock ????????????????");
        if (request.knowledgeBaseId() > 0) {
            knowledgeBaseService.importDocument(request.knowledgeBaseId(), request.name() + "#" + run.id(), summary + "\n\n?????" + metricsJson, Map.of("report", "true", "template", request.name(), "source", nz(request.dataSourceKey())));
        }
        return run;
    }

    public List<ReportRunResponse> listRuns() { return repository.listReportRuns(); }
    public ReportRunResponse getRun(long id) { return repository.findReportRun(id).orElseThrow(() -> new IllegalArgumentException("?????: " + id)); }

    @Scheduled(cron = "0 0/30 * * * ?")
    public void heartbeatScheduler() {
        // Quartz/Spring scheduler hook for MVP. Real cron registration is represented by report template config.
    }

    private String chart(String title) {
        return """
            {"type":"echarts","title":{"text":"%s"},"tooltip":{},"xAxis":{"type":"category","data":["??","??","??"]},"yAxis":{"type":"value"},"series":[{"name":"???","type":"bar","data":[120,95,88]}]}
            """.formatted(title.replace("\"", "\\\""));
    }
    private String nz(String value) { return value == null ? "" : value; }
}
