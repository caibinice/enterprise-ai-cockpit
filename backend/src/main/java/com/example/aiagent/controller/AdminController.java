package com.example.aiagent.controller;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.example.aiagent.service.*;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
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
        return fromBlocking(() -> {
            VectorIndexService vector = vectorIndexProvider.getIfAvailable();
            McpWeatherService mcp = mcpWeatherProvider.getIfAvailable();
            return new HealthResponse("ok", modelGateway.enabled() ? "spring-ai" : "mock", repository.getClass().getSimpleName(),
                vector == null ? "disabled" : vector.status(), mcp == null ? "disabled" : mcp.configuredStatus(),
                repository.countKnowledgeBases(), repository.countDocuments(), repository.countChunks(), repository.countReports());
        });
    }

    @GetMapping("/admin/knowledge-bases") public Mono<List<KnowledgeBaseResponse>> knowledgeBases() { return fromBlocking(knowledgeBaseService::list); }
    @PostMapping("/admin/knowledge-bases") public Mono<KnowledgeBaseResponse> createKnowledgeBase(@Valid @RequestBody KnowledgeBaseRequest request) { return fromBlocking(() -> knowledgeBaseService.create(request)); }
    @DeleteMapping("/admin/knowledge-bases/{id}") public Mono<Void> deleteKnowledgeBase(@PathVariable long id) { return runBlocking(() -> knowledgeBaseService.deleteKnowledgeBase(id)); }

    @GetMapping("/admin/documents") public Mono<List<KnowledgeDocumentResponse>> documents(@RequestParam(required = false) Long knowledgeBaseId) { return fromBlocking(() -> knowledgeBaseService.listDocuments(knowledgeBaseId)); }

    @PostMapping(path = "/admin/documents/batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<KnowledgeDocumentResponse>> upload(@RequestParam long knowledgeBaseId, @RequestParam(required = false) String metadata, @RequestPart("files") Flux<FilePart> files) {
        Map<String, String> parsed = knowledgeBaseService.parseMetadata(metadata);
        return files.concatMap(file -> importFile(file, knowledgeBaseId, parsed)).collectList();
    }

    @PostMapping("/admin/documents/text")
    public Mono<KnowledgeDocumentResponse> importText(@RequestParam long knowledgeBaseId, @RequestBody Map<String, Object> body) {
        return fromBlocking(() -> {
            Map<String, String> metadata = stringMap(body.get("metadata"));
            return knowledgeBaseService.importDocument(knowledgeBaseId, String.valueOf(body.getOrDefault("title", "Untitled document")), String.valueOf(body.getOrDefault("content", "")), metadata);
        });
    }

    @PatchMapping("/admin/documents/{id}/metadata") public Mono<Void> updateMetadata(@PathVariable long id, @RequestBody Map<String, String> metadata) { return runBlocking(() -> knowledgeBaseService.updateMetadata(id, metadata)); }
    @DeleteMapping("/admin/documents/{id}") public Mono<Void> deleteDocument(@PathVariable long id) { return runBlocking(() -> knowledgeBaseService.deleteDocument(id)); }

    @GetMapping("/admin/data-sources") public Mono<List<DataSourceResponse>> dataSources() { return fromBlocking(dataSourceService::list); }
    @PostMapping("/admin/data-sources") public Mono<DataSourceResponse> createDataSource(@Valid @RequestBody DataSourceRequest request) { return fromBlocking(() -> dataSourceService.create(request)); }
    @PostMapping("/admin/data-sources/{id}/test") public Mono<Map<String, Object>> testDataSource(@PathVariable long id) { return fromBlocking(() -> dataSourceService.test(id)); }
    @DeleteMapping("/admin/data-sources/{id}") public Mono<Void> deleteDataSource(@PathVariable long id) { return runBlocking(() -> dataSourceService.delete(id)); }

    @GetMapping("/admin/report-templates") public Mono<List<ReportTemplateResponse>> reportTemplates() { return fromBlocking(reportService::listTemplates); }
    @PostMapping("/admin/report-templates") public Mono<ReportTemplateResponse> createReportTemplate(@Valid @RequestBody ReportTemplateRequest request) { return fromBlocking(() -> reportService.createTemplate(request)); }
    @PostMapping("/admin/report-templates/{id}/run-now") public Mono<ReportRunResponse> runNow(@PathVariable long id) { return fromBlocking(() -> reportService.runTemplate(id)); }
    @DeleteMapping("/admin/report-templates/{id}") public Mono<Void> deleteTemplate(@PathVariable long id) { return runBlocking(() -> reportService.deleteTemplate(id)); }

    @GetMapping("/admin/report-runs") public Mono<List<ReportRunResponse>> reportRuns() { return fromBlocking(reportService::listRuns); }
    @GetMapping("/reports/{id}") public Mono<ReportRunResponse> report(@PathVariable long id) { return fromBlocking(() -> reportService.getRun(id)); }

    private Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("metadata must be a JSON object");
        Map<String, String> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item == null ? "" : String.valueOf(item));
        });
        return result;
    }

    private Mono<KnowledgeDocumentResponse> importFile(FilePart file, long knowledgeBaseId, Map<String, String> metadata) {
        return Mono.usingWhen(
            fromBlocking(() -> Files.createTempFile("enterprise-ai-cockpit-", ".upload")),
            tempFile -> file.transferTo(tempFile).then(fromBlocking(() -> {
                try (InputStream input = Files.newInputStream(tempFile)) {
                    return knowledgeBaseService.importFile(knowledgeBaseId, file.filename(), input, metadata);
                }
            })),
            this::deleteTempFile,
            (tempFile, error) -> deleteTempFile(tempFile),
            this::deleteTempFile
        );
    }

    private Mono<Void> deleteTempFile(Path tempFile) {
        return runBlocking(() -> {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                log.warn("Unable to delete temporary upload {}: {}", tempFile, ex.getMessage());
            }
        });
    }

    private <T> Mono<T> fromBlocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> runBlocking(Runnable action) {
        return Mono.fromRunnable(action).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
