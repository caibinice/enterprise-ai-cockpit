package com.example.aiagent.controller;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.example.aiagent.service.*;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DataSourceService dataSourceService;
    private final ReportService reportService;
    private final ModelGateway modelGateway;

    public AdminController(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService, DataSourceService dataSourceService, ReportService reportService, ModelGateway modelGateway) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.dataSourceService = dataSourceService;
        this.reportService = reportService;
        this.modelGateway = modelGateway;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", modelGateway.enabled() ? "openai-compatible" : "mock", repository.countKnowledgeBases(), repository.countDocuments(), repository.countChunks(), repository.countReports());
    }

    @GetMapping("/admin/knowledge-bases") public List<KnowledgeBaseResponse> knowledgeBases() { return knowledgeBaseService.list(); }
    @PostMapping("/admin/knowledge-bases") public KnowledgeBaseResponse createKnowledgeBase(@Valid @RequestBody KnowledgeBaseRequest request) { return knowledgeBaseService.create(request); }
    @DeleteMapping("/admin/knowledge-bases/{id}") public void deleteKnowledgeBase(@PathVariable long id) { knowledgeBaseService.deleteKnowledgeBase(id); }

    @GetMapping("/admin/documents") public List<KnowledgeDocumentResponse> documents(@RequestParam(required = false) Long knowledgeBaseId) { return knowledgeBaseService.listDocuments(knowledgeBaseId); }

    @PostMapping(path = "/admin/documents/batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<KnowledgeDocumentResponse> upload(@RequestParam long knowledgeBaseId, @RequestParam(required = false) String metadata, @RequestPart("files") MultipartFile[] files) throws IOException {
        Map<String, String> parsed = knowledgeBaseService.parseMetadata(metadata);
        return java.util.Arrays.stream(files)
            .map(file -> {
                try { return knowledgeBaseService.importFile(knowledgeBaseId, file.getOriginalFilename(), file.getInputStream(), parsed); }
                catch (IOException ex) { throw new IllegalArgumentException("Upload failed: " + file.getOriginalFilename(), ex); }
            })
            .toList();
    }

    @PostMapping("/admin/documents/text")
    public KnowledgeDocumentResponse importText(@RequestParam long knowledgeBaseId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, String> metadata = (Map<String, String>) body.getOrDefault("metadata", Map.of());
        return knowledgeBaseService.importDocument(knowledgeBaseId, String.valueOf(body.getOrDefault("title", "Untitled document")), String.valueOf(body.getOrDefault("content", "")), metadata);
    }

    @PatchMapping("/admin/documents/{id}/metadata") public void updateMetadata(@PathVariable long id, @RequestBody Map<String, String> metadata) { knowledgeBaseService.updateMetadata(id, metadata); }
    @DeleteMapping("/admin/documents/{id}") public void deleteDocument(@PathVariable long id) { knowledgeBaseService.deleteDocument(id); }

    @GetMapping("/admin/data-sources") public List<DataSourceResponse> dataSources() { return dataSourceService.list(); }
    @PostMapping("/admin/data-sources") public DataSourceResponse createDataSource(@Valid @RequestBody DataSourceRequest request) { return dataSourceService.create(request); }
    @PostMapping("/admin/data-sources/{id}/test") public Map<String, Object> testDataSource(@PathVariable long id) { return dataSourceService.test(id); }
    @DeleteMapping("/admin/data-sources/{id}") public void deleteDataSource(@PathVariable long id) { dataSourceService.delete(id); }

    @GetMapping("/admin/report-templates") public List<ReportTemplateResponse> reportTemplates() { return reportService.listTemplates(); }
    @PostMapping("/admin/report-templates") public ReportTemplateResponse createReportTemplate(@Valid @RequestBody ReportTemplateRequest request) { return reportService.createTemplate(request); }
    @PostMapping("/admin/report-templates/{id}/run-now") public ReportRunResponse runNow(@PathVariable long id) { return reportService.runTemplate(id); }
    @DeleteMapping("/admin/report-templates/{id}") public void deleteTemplate(@PathVariable long id) { reportService.deleteTemplate(id); }

    @GetMapping("/admin/report-runs") public List<ReportRunResponse> reportRuns() { return reportService.listRuns(); }
    @GetMapping("/reports/{id}") public ReportRunResponse report(@PathVariable long id) { return reportService.getRun(id); }
}
