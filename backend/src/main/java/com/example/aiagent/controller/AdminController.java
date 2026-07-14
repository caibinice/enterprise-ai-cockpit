package com.example.aiagent.controller;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.example.aiagent.service.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final EnterpriseRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DataSourceService dataSourceService;
    private final ReportService reportService;
    private final ModelGateway modelGateway;
    private final ObjectProvider<VectorIndexService> vectorIndexProvider;
    private final ObjectProvider<McpWeatherService> mcpWeatherProvider;

    public AdminController(EnterpriseRepository repository, KnowledgeBaseService knowledgeBaseService, DataSourceService dataSourceService, ReportService reportService, ModelGateway modelGateway, ObjectProvider<VectorIndexService> vectorIndexProvider, ObjectProvider<McpWeatherService> mcpWeatherProvider) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.dataSourceService = dataSourceService;
        this.reportService = reportService;
        this.modelGateway = modelGateway;
        this.vectorIndexProvider = vectorIndexProvider;
        this.mcpWeatherProvider = mcpWeatherProvider;
    }

    @GetMapping("/health")
    public Mono<HealthResponse> health() {
        return Mono.fromCallable(() -> {
            VectorIndexService vector = vectorIndexProvider.getIfAvailable();
            McpWeatherService mcp = mcpWeatherProvider.getIfAvailable();
            return new HealthResponse("ok", modelGateway.enabled() ? "spring-ai" : "mock", repository.getClass().getSimpleName(),
                vector == null ? "disabled" : vector.status(), mcp == null ? "disabled" : mcp.configuredStatus(),
                repository.countKnowledgeBases(), repository.countDocuments(), repository.countChunks(), repository.countReports());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/admin/knowledge-bases") public List<KnowledgeBaseResponse> knowledgeBases() { return knowledgeBaseService.list(); }
    @PostMapping("/admin/knowledge-bases") public KnowledgeBaseResponse createKnowledgeBase(@Valid @RequestBody KnowledgeBaseRequest request) { return knowledgeBaseService.create(request); }
    @DeleteMapping("/admin/knowledge-bases/{id}") public void deleteKnowledgeBase(@PathVariable long id) { knowledgeBaseService.deleteKnowledgeBase(id); }

    @GetMapping("/admin/documents") public List<KnowledgeDocumentResponse> documents(@RequestParam(required = false) Long knowledgeBaseId) { return knowledgeBaseService.listDocuments(knowledgeBaseId); }

    @PostMapping(path = "/admin/documents/batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<KnowledgeDocumentResponse>> upload(@RequestParam long knowledgeBaseId, @RequestParam(required = false) String metadata, @RequestPart("files") Flux<FilePart> files) {
        Map<String, String> parsed = knowledgeBaseService.parseMetadata(metadata);
        return files.flatMapSequential(file -> DataBufferUtils.join(file.content())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Uploaded document is empty: " + file.filename())))
                .map(buffer -> readFile(file, buffer, knowledgeBaseId, parsed)))
            .collectList()
            .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/admin/documents/text")
    public KnowledgeDocumentResponse importText(@RequestParam long knowledgeBaseId, @RequestBody Map<String, Object> body) {
        Map<String, String> metadata = stringMap(body.get("metadata"));
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

    private Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("metadata must be a JSON object");
        Map<String, String> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item == null ? "" : String.valueOf(item));
        });
        return result;
    }

    private KnowledgeDocumentResponse readFile(FilePart file, DataBuffer buffer, long knowledgeBaseId, Map<String, String> metadata) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return knowledgeBaseService.importFile(knowledgeBaseId, file.filename(), new java.io.ByteArrayInputStream(bytes), metadata);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
