package com.example.aiagent.repository;

import com.example.aiagent.model.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public interface EnterpriseRepository {
    KnowledgeBaseResponse saveKnowledgeBase(KnowledgeBaseRequest request);
    List<KnowledgeBaseResponse> listKnowledgeBases();
    Optional<KnowledgeBaseResponse> findKnowledgeBase(long id);
    void deleteKnowledgeBase(long id);

    KnowledgeDocumentResponse saveDocument(long knowledgeBaseId, String title, String content, Map<String, String> metadata, List<String> chunks);
    List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId);
    Optional<KnowledgeDocumentResponse> findDocument(long id);
    void updateDocumentMetadata(long id, Map<String, String> metadata);
    KnowledgeDocumentResponse replaceDocumentIndex(long id, Map<String, String> metadata, List<String> chunks);
    void deleteDocument(long id);
    List<RetrievedKnowledgeChunk> findAllChunks();

    default List<RetrievedKnowledgeChunk> findKeywordCandidates(
        List<Long> knowledgeBaseIds,
        List<String> terms,
        int limit
    ) {
        List<Long> kbIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        List<String> safeTerms = terms == null ? List.of() : terms.stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .distinct()
            .limit(12)
            .toList();
        return findAllChunks().stream()
            .filter(chunk -> kbIds.isEmpty() || kbIds.contains(chunk.knowledgeBaseId()))
            .filter(chunk -> safeTerms.isEmpty() || safeTerms.stream().anyMatch(term ->
                (chunk.title() + "\n" + chunk.content() + "\n" + chunk.metadata())
                    .toLowerCase(Locale.ROOT)
                    .contains(term)))
            .limit(Math.max(1, limit))
            .toList();
    }

    default List<RetrievedKnowledgeChunk> findChunksByDocumentId(long documentId) {
        return findAllChunks().stream().filter(chunk -> chunk.documentId() == documentId).toList();
    }

    default List<RetrievedKnowledgeChunk> findChunksByKnowledgeBaseId(long knowledgeBaseId) {
        return findAllChunks().stream().filter(chunk -> chunk.knowledgeBaseId() == knowledgeBaseId).toList();
    }

    DataSourceResponse saveDataSource(DataSourceRequest request);
    List<DataSourceResponse> listDataSources();
    Optional<DataSourceResponse> findDataSource(long id);
    void deleteDataSource(long id);

    ReportTemplateResponse saveReportTemplate(ReportTemplateRequest request);
    List<ReportTemplateResponse> listReportTemplates();
    Optional<ReportTemplateResponse> findReportTemplate(long id);
    void deleteReportTemplate(long id);
    ReportRunResponse saveReportRun(long templateId, String name, String status, String summary, String metricsJson, String chartSpec, String logs);
    List<ReportRunResponse> listReportRuns();
    Optional<ReportRunResponse> findReportRun(long id);

    void saveChatMessage(String conversationId, String role, String content);
    List<ConversationMessage> findChatMessages(String conversationId, int limit);
    long countKnowledgeBases();
    long countDocuments();
    long countChunks();
    long countReports();
}
