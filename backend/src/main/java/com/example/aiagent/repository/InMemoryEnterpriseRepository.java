package com.example.aiagent.repository;

import com.example.aiagent.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "app.repository", name = "mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryEnterpriseRepository implements EnterpriseRepository {
    private final AtomicLong ids = new AtomicLong(0);
    private final Map<Long, KnowledgeBaseResponse> knowledgeBases = new LinkedHashMap<>();
    private final Map<Long, KnowledgeDocumentResponse> documents = new LinkedHashMap<>();
    private final Map<Long, List<RetrievedKnowledgeChunk>> documentChunks = new LinkedHashMap<>();
    private final Map<Long, DataSourceResponse> dataSources = new LinkedHashMap<>();
    private final Map<Long, ReportTemplateResponse> reportTemplates = new LinkedHashMap<>();
    private final Map<Long, ReportRunResponse> reportRuns = new LinkedHashMap<>();
    private final List<Map<String, String>> chatMessages = new ArrayList<>();

    public InMemoryEnterpriseRepository(ObjectMapper objectMapper) {
    }

    @Override
    public synchronized KnowledgeBaseResponse saveKnowledgeBase(KnowledgeBaseRequest request) {
        long id = ids.incrementAndGet();
        KnowledgeBaseResponse response = new KnowledgeBaseResponse(id, request.name(), nz(request.description()), blankToDefault(request.code(), "KB-" + id), 0, Instant.now());
        knowledgeBases.put(id, response);
        return response;
    }

    @Override
    public synchronized List<KnowledgeBaseResponse> listKnowledgeBases() {
        return knowledgeBases.values().stream()
            .map(kb -> new KnowledgeBaseResponse(kb.id(), kb.name(), kb.description(), kb.code(), documents.values().stream().filter(d -> d.knowledgeBaseId() == kb.id()).count(), kb.createdAt()))
            .sorted(Comparator.comparing(KnowledgeBaseResponse::createdAt).reversed())
            .toList();
    }

    @Override
    public synchronized Optional<KnowledgeBaseResponse> findKnowledgeBase(long id) {
        return Optional.ofNullable(knowledgeBases.get(id));
    }

    @Override
    public synchronized void deleteKnowledgeBase(long id) {
        knowledgeBases.remove(id);
        documents.values().removeIf(doc -> doc.knowledgeBaseId() == id);
        documentChunks.entrySet().removeIf(e -> documents.get(e.getKey()) == null);
    }

    @Override
    public synchronized KnowledgeDocumentResponse saveDocument(long knowledgeBaseId, String title, String content, Map<String, String> metadata, List<String> chunks) {
        long documentId = ids.incrementAndGet();
        Map<String, String> safeMeta = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        KnowledgeDocumentResponse doc = new KnowledgeDocumentResponse(documentId, knowledgeBaseId, title, content, safeMeta, chunks.size(), Instant.now());
        documents.put(documentId, doc);
        List<RetrievedKnowledgeChunk> refs = new ArrayList<>();
        for (String chunk : chunks) {
            refs.add(new RetrievedKnowledgeChunk(ids.incrementAndGet(), documentId, knowledgeBaseId, title, chunk, 0.0, safeMeta));
        }
        documentChunks.put(documentId, refs);
        return doc;
    }

    @Override
    public synchronized List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) {
        return documents.values().stream()
            .filter(d -> knowledgeBaseId == null || d.knowledgeBaseId() == knowledgeBaseId)
            .sorted(Comparator.comparing(KnowledgeDocumentResponse::createdAt).reversed())
            .toList();
    }

    @Override
    public synchronized Optional<KnowledgeDocumentResponse> findDocument(long id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public synchronized void updateDocumentMetadata(long id, Map<String, String> metadata) {
        KnowledgeDocumentResponse old = documents.get(id);
        if (old == null) return;
        Map<String, String> safe = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        KnowledgeDocumentResponse updated = new KnowledgeDocumentResponse(old.id(), old.knowledgeBaseId(), old.title(), old.content(), safe, old.chunks(), old.createdAt());
        documents.put(id, updated);
        List<RetrievedKnowledgeChunk> chunks = documentChunks.getOrDefault(id, List.of()).stream()
            .map(c -> new RetrievedKnowledgeChunk(c.id(), c.documentId(), c.knowledgeBaseId(), c.title(), c.content(), c.score(), safe))
            .toList();
        documentChunks.put(id, chunks);
    }

    @Override
    public synchronized void deleteDocument(long id) {
        documents.remove(id);
        documentChunks.remove(id);
    }

    @Override
    public synchronized List<RetrievedKnowledgeChunk> findAllChunks() {
        return documentChunks.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public synchronized DataSourceResponse saveDataSource(DataSourceRequest request) {
        long id = ids.incrementAndGet();
        DataSourceResponse response = new DataSourceResponse(id, request.name(), request.type(), nz(request.endpoint()), nz(request.queryText()), request.config() == null ? Map.of() : new LinkedHashMap<>(request.config()), Instant.now());
        dataSources.put(id, response);
        return response;
    }

    @Override
    public synchronized List<DataSourceResponse> listDataSources() { return new ArrayList<>(dataSources.values()); }
    @Override
    public synchronized Optional<DataSourceResponse> findDataSource(long id) { return Optional.ofNullable(dataSources.get(id)); }
    @Override
    public synchronized void deleteDataSource(long id) { dataSources.remove(id); }

    @Override
    public synchronized ReportTemplateResponse saveReportTemplate(ReportTemplateRequest request) {
        long id = ids.incrementAndGet();
        ReportTemplateResponse response = new ReportTemplateResponse(id, request.name(), blankToDefault(request.scheduleType(), "MANUAL"), nz(request.cron()), nz(request.dataSourceKey()), request.knowledgeBaseId(), nz(request.prompt()), nz(request.dimensions()), request.enabled(), Instant.now());
        reportTemplates.put(id, response);
        return response;
    }

    @Override
    public synchronized List<ReportTemplateResponse> listReportTemplates() { return new ArrayList<>(reportTemplates.values()); }
    @Override
    public synchronized Optional<ReportTemplateResponse> findReportTemplate(long id) { return Optional.ofNullable(reportTemplates.get(id)); }
    @Override
    public synchronized void deleteReportTemplate(long id) { reportTemplates.remove(id); }

    @Override
    public synchronized ReportRunResponse saveReportRun(long templateId, String name, String status, String summary, String metricsJson, String chartSpec, String logs) {
        long id = ids.incrementAndGet();
        ReportRunResponse response = new ReportRunResponse(id, templateId, name, status, summary, metricsJson, chartSpec, logs, Instant.now());
        reportRuns.put(id, response);
        return response;
    }

    @Override
    public synchronized List<ReportRunResponse> listReportRuns() {
        return reportRuns.values().stream().sorted(Comparator.comparing(ReportRunResponse::createdAt).reversed()).toList();
    }
    @Override
    public synchronized Optional<ReportRunResponse> findReportRun(long id) { return Optional.ofNullable(reportRuns.get(id)); }

    @Override
    public synchronized void saveChatMessage(String conversationId, String role, String content) {
        Map<String, String> row = new HashMap<>();
        row.put("conversationId", conversationId);
        row.put("role", role);
        row.put("content", content);
        chatMessages.add(row);
    }

    @Override public synchronized long countKnowledgeBases() { return knowledgeBases.size(); }
    @Override public synchronized long countDocuments() { return documents.size(); }
    @Override public synchronized long countChunks() { return documentChunks.values().stream().mapToLong(List::size).sum(); }
    @Override public synchronized long countReports() { return reportRuns.size(); }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToDefault(String s, String defaultValue) { return s == null || s.isBlank() ? defaultValue : s; }
}
