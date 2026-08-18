package com.example.aiagent.repository;

import com.example.aiagent.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL/MariaDB-backed implementation of the enterprise repository. */
@Repository
@ConditionalOnProperty(prefix = "app.repository", name = "mode", havingValue = "mysql")
public class JdbcEnterpriseRepository implements EnterpriseRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcEnterpriseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public KnowledgeBaseResponse saveKnowledgeBase(KnowledgeBaseRequest request) {
        long id = insert(
            """
                INSERT INTO knowledge_bases(name, description, code, business_type)
                VALUES (?, ?, ?, ?)
                """,
            request.name(),
            nz(request.description()),
            blankToDefault(request.code(), "KB-" + System.currentTimeMillis()),
            blankToDefault(request.businessType(), "通用业务")
        );
        return findKnowledgeBase(id).orElseThrow();
    }

    @Override
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        return jdbcTemplate.query("""
            SELECT kb.id, kb.name, kb.description, kb.code, kb.business_type, kb.created_at,
                   COUNT(d.id) AS document_count
            FROM knowledge_bases kb
            LEFT JOIN knowledge_documents d ON d.knowledge_base_id = kb.id
            GROUP BY kb.id, kb.name, kb.description, kb.code, kb.business_type, kb.created_at
            ORDER BY kb.created_at DESC, kb.id DESC
            """, (rs, row) -> new KnowledgeBaseResponse(rs.getLong("id"), rs.getString("name"),
                rs.getString("description"), rs.getString("code"), rs.getString("business_type"),
                rs.getLong("document_count"), instant(rs, "created_at")));
    }

    @Override
    public Optional<KnowledgeBaseResponse> findKnowledgeBase(long id) {
        return jdbcTemplate.query("""
            SELECT kb.id, kb.name, kb.description, kb.code, kb.business_type, kb.created_at,
                   COUNT(d.id) AS document_count
            FROM knowledge_bases kb
            LEFT JOIN knowledge_documents d ON d.knowledge_base_id = kb.id
            WHERE kb.id = ?
            GROUP BY kb.id, kb.name, kb.description, kb.code, kb.business_type, kb.created_at
            """, (rs, row) -> new KnowledgeBaseResponse(rs.getLong("id"), rs.getString("name"),
                rs.getString("description"), rs.getString("code"), rs.getString("business_type"),
                rs.getLong("document_count"), instant(rs, "created_at")), id)
            .stream().findFirst();
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(long id) {
        jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", id);
    }

    @Override
    @Transactional
    public KnowledgeDocumentResponse saveDocument(long knowledgeBaseId, String title, String content,
                                                  Map<String, String> metadata, List<String> chunks) {
        String safeMetadata = json(metadata);
        long documentId = insert("""
            INSERT INTO knowledge_documents(knowledge_base_id, title, content, metadata, chunk_count)
            VALUES (?, ?, ?, ?, ?)
            """, knowledgeBaseId, nz(title), nz(content), safeMetadata, chunks == null ? 0 : chunks.size());
        List<String> safeChunks = chunks == null ? List.of() : chunks;
        for (int i = 0; i < safeChunks.size(); i++) {
            String chunkText = nz(safeChunks.get(i));
            insert("""
                INSERT INTO knowledge_chunks(document_id, knowledge_base_id, title, content, metadata, chunk_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """, documentId, knowledgeBaseId, nz(title), chunkText, safeMetadata, i);
        }
        return findDocument(documentId).orElseThrow();
    }

    @Override
    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return jdbcTemplate.query("SELECT id, knowledge_base_id, title, content, metadata, chunk_count, created_at FROM knowledge_documents ORDER BY created_at DESC, id DESC", this::mapDocument);
        }
        return jdbcTemplate.query("SELECT id, knowledge_base_id, title, content, metadata, chunk_count, created_at FROM knowledge_documents WHERE knowledge_base_id = ? ORDER BY created_at DESC, id DESC", this::mapDocument, knowledgeBaseId);
    }

    @Override
    public Optional<KnowledgeDocumentResponse> findDocument(long id) {
        return jdbcTemplate.query("SELECT id, knowledge_base_id, title, content, metadata, chunk_count, created_at FROM knowledge_documents WHERE id = ?", this::mapDocument, id)
            .stream().findFirst();
    }

    @Override
    @Transactional
    public void updateDocumentMetadata(long id, Map<String, String> metadata) {
        String serialized = json(metadata);
        jdbcTemplate.update("UPDATE knowledge_documents SET metadata = ? WHERE id = ?", serialized, id);
        jdbcTemplate.update("UPDATE knowledge_chunks SET metadata = ? WHERE document_id = ?", serialized, id);
    }

    @Override
    @Transactional
    public void deleteDocument(long id) {
        jdbcTemplate.update("DELETE FROM knowledge_documents WHERE id = ?", id);
    }

    @Override
    public List<RetrievedKnowledgeChunk> findAllChunks() {
        return jdbcTemplate.query("SELECT id, document_id, knowledge_base_id, title, content, metadata, chunk_order FROM knowledge_chunks ORDER BY id", this::mapChunk);
    }

    @Override
    @Transactional
    public KnowledgeDocumentResponse replaceDocumentIndex(
        long id,
        Map<String, String> metadata,
        List<String> chunks
    ) {
        KnowledgeDocumentResponse document = findDocument(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        List<String> safeChunks = chunks == null ? List.of() : chunks;
        String serialized = json(metadata);
        jdbcTemplate.update(
            "UPDATE knowledge_documents SET metadata = ?, chunk_count = ? WHERE id = ?",
            serialized,
            safeChunks.size(),
            id
        );
        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE document_id = ?", id);
        for (int order = 0; order < safeChunks.size(); order++) {
            insert("""
                INSERT INTO knowledge_chunks(document_id, knowledge_base_id, title, content, metadata, chunk_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, document.knowledgeBaseId(), document.title(), safeChunks.get(order), serialized, order);
        }
        return findDocument(id).orElseThrow();
    }

    @Override
    public List<RetrievedKnowledgeChunk> findChunksByDocumentId(long documentId) {
        return jdbcTemplate.query("SELECT id, document_id, knowledge_base_id, title, content, metadata, chunk_order FROM knowledge_chunks WHERE document_id = ? ORDER BY chunk_order, id", this::mapChunk, documentId);
    }

    @Override
    public List<RetrievedKnowledgeChunk> findChunksByKnowledgeBaseId(long knowledgeBaseId) {
        return jdbcTemplate.query("SELECT id, document_id, knowledge_base_id, title, content, metadata, chunk_order FROM knowledge_chunks WHERE knowledge_base_id = ? ORDER BY document_id, chunk_order, id", this::mapChunk, knowledgeBaseId);
    }

    @Override
    public List<RetrievedKnowledgeChunk> findKeywordCandidates(
        List<Long> knowledgeBaseIds,
        List<String> terms,
        int limit
    ) {
        List<Long> kbIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        List<String> safeTerms = terms == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(terms)).stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .limit(12)
            .toList();
        StringBuilder sql = new StringBuilder(
            "SELECT id, document_id, knowledge_base_id, title, content, metadata, chunk_order FROM knowledge_chunks WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        if (!kbIds.isEmpty()) {
            sql.append(" AND knowledge_base_id IN (")
                .append("?, ".repeat(Math.max(0, kbIds.size() - 1)))
                .append("?)");
            args.addAll(kbIds);
        }
        if (!safeTerms.isEmpty()) {
            sql.append(" AND (");
            for (int index = 0; index < safeTerms.size(); index++) {
                if (index > 0) sql.append(" OR ");
                sql.append("(LOWER(title) LIKE ? ESCAPE '!' OR LOWER(content) LIKE ? ESCAPE '!' OR LOWER(metadata) LIKE ? ESCAPE '!')");
                String pattern = "%" + escapeLike(safeTerms.get(index)) + "%";
                args.add(pattern);
                args.add(pattern);
                args.add(pattern);
            }
            sql.append(')');
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(Math.min(80, Math.max(1, limit)));
        return jdbcTemplate.query(sql.toString(), this::mapChunk, args.toArray());
    }

    @Override
    @Transactional
    public DataSourceResponse saveDataSource(DataSourceRequest request) {
        long id = insert("INSERT INTO data_sources(name, type, endpoint, query_text, config) VALUES (?, ?, ?, ?, ?)",
            request.name(), request.type(), nz(request.endpoint()), nz(request.queryText()), json(request.config()));
        return findDataSource(id).orElseThrow();
    }

    @Override
    public List<DataSourceResponse> listDataSources() {
        return jdbcTemplate.query("SELECT id, name, type, endpoint, query_text, config, created_at FROM data_sources ORDER BY created_at DESC, id DESC", this::mapDataSource);
    }

    @Override
    public Optional<DataSourceResponse> findDataSource(long id) {
        return jdbcTemplate.query("SELECT id, name, type, endpoint, query_text, config, created_at FROM data_sources WHERE id = ?", this::mapDataSource, id).stream().findFirst();
    }

    @Override public void deleteDataSource(long id) { jdbcTemplate.update("DELETE FROM data_sources WHERE id = ?", id); }

    @Override
    @Transactional
    public ReportTemplateResponse saveReportTemplate(ReportTemplateRequest request) {
        long id = insert("""
            INSERT INTO report_templates(name, schedule_type, cron, data_source_key, knowledge_base_id, prompt, dimensions, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, request.name(), blankToDefault(request.scheduleType(), "MANUAL"), nz(request.cron()), nz(request.dataSourceKey()),
            request.knowledgeBaseId(), nz(request.prompt()), nz(request.dimensions()), request.enabled());
        return findReportTemplate(id).orElseThrow();
    }

    @Override
    public List<ReportTemplateResponse> listReportTemplates() {
        return jdbcTemplate.query("SELECT id, name, schedule_type, cron, data_source_key, knowledge_base_id, prompt, dimensions, enabled, created_at FROM report_templates ORDER BY created_at DESC, id DESC", this::mapReportTemplate);
    }

    @Override
    public Optional<ReportTemplateResponse> findReportTemplate(long id) {
        return jdbcTemplate.query("SELECT id, name, schedule_type, cron, data_source_key, knowledge_base_id, prompt, dimensions, enabled, created_at FROM report_templates WHERE id = ?", this::mapReportTemplate, id).stream().findFirst();
    }

    @Override public void deleteReportTemplate(long id) { jdbcTemplate.update("DELETE FROM report_templates WHERE id = ?", id); }

    @Override
    @Transactional
    public ReportRunResponse saveReportRun(long templateId, String name, String status, String summary, String metricsJson, String chartSpec, String logs) {
        long id = insert("INSERT INTO report_runs(template_id, name, status, summary, metrics_json, chart_spec, logs) VALUES (?, ?, ?, ?, ?, ?, ?)",
            templateId, nz(name), nz(status), nz(summary), nz(metricsJson), nz(chartSpec), nz(logs));
        return findReportRun(id).orElseThrow();
    }

    @Override
    public List<ReportRunResponse> listReportRuns() {
        return jdbcTemplate.query("SELECT id, template_id, name, status, summary, metrics_json, chart_spec, logs, created_at FROM report_runs ORDER BY created_at DESC, id DESC", this::mapReportRun);
    }

    @Override
    public Optional<ReportRunResponse> findReportRun(long id) {
        return jdbcTemplate.query("SELECT id, template_id, name, status, summary, metrics_json, chart_spec, logs, created_at FROM report_runs WHERE id = ?", this::mapReportRun, id).stream().findFirst();
    }

    @Override
    public void saveChatMessage(String conversationId, String role, String content) {
        jdbcTemplate.update("INSERT INTO chat_messages(conversation_id, role, content) VALUES (?, ?, ?)", nz(conversationId), nz(role), nz(content));
    }

    @Override
    public List<ConversationMessage> findChatMessages(String conversationId, int limit) {
        int safeLimit = Math.min(20, Math.max(0, limit));
        if (safeLimit == 0 || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<ConversationMessage> newestFirst = jdbcTemplate.query(
            """
                SELECT role, content, created_at
                FROM chat_messages
                WHERE conversation_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
            (rs, row) -> new ConversationMessage(
                rs.getString("role"),
                rs.getString("content"),
                instant(rs, "created_at")
            ),
            conversationId,
            safeLimit
        );
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Override public long countKnowledgeBases() { return count("knowledge_bases"); }
    @Override public long countDocuments() { return count("knowledge_documents"); }
    @Override public long countChunks() { return count("knowledge_chunks"); }
    @Override public long countReports() { return count("report_runs"); }

    private KnowledgeDocumentResponse mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeDocumentResponse(rs.getLong("id"), rs.getLong("knowledge_base_id"), rs.getString("title"),
            rs.getString("content"), parseMetadata(rs.getString("metadata")), rs.getInt("chunk_count"), instant(rs, "created_at"));
    }

    private RetrievedKnowledgeChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        Map<String, String> metadata = new java.util.LinkedHashMap<>(parseMetadata(rs.getString("metadata")));
        metadata.put("_chunkOrder", String.valueOf(rs.getInt("chunk_order")));
        return new RetrievedKnowledgeChunk(rs.getLong("id"), rs.getLong("document_id"), rs.getLong("knowledge_base_id"),
            rs.getString("title"), rs.getString("content"), 0.0, Map.copyOf(metadata));
    }

    private DataSourceResponse mapDataSource(ResultSet rs, int rowNum) throws SQLException {
        return new DataSourceResponse(rs.getLong("id"), rs.getString("name"), rs.getString("type"), rs.getString("endpoint"),
            rs.getString("query_text"), parseMetadata(rs.getString("config")), instant(rs, "created_at"));
    }

    private ReportTemplateResponse mapReportTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new ReportTemplateResponse(rs.getLong("id"), rs.getString("name"), rs.getString("schedule_type"), rs.getString("cron"),
            rs.getString("data_source_key"), rs.getLong("knowledge_base_id"), rs.getString("prompt"), rs.getString("dimensions"), rs.getBoolean("enabled"), instant(rs, "created_at"));
    }

    private ReportRunResponse mapReportRun(ResultSet rs, int rowNum) throws SQLException {
        return new ReportRunResponse(rs.getLong("id"), rs.getLong("template_id"), rs.getString("name"), rs.getString("status"),
            rs.getString("summary"), rs.getString("metrics_json"), rs.getString("chart_spec"), rs.getString("logs"), instant(rs, "created_at"));
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated id");
        return key.longValue();
    }

    private long count(String table) { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private Map<String, String> parseMetadata(String raw) {
        try { return raw == null ? Map.of() : objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {}); }
        catch (Exception ex) { return Map.of(); }
    }

    private String json(Map<String, String> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("JSON serialization failed", ex); }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static String nz(String value) { return value == null ? "" : value; }
    private static String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
