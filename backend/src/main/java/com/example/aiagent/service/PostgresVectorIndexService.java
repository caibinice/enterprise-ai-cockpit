package com.example.aiagent.service;

import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.vector", name = "enabled", havingValue = "true")
public class PostgresVectorIndexService implements VectorIndexService {
    private static final Logger log = LoggerFactory.getLogger(PostgresVectorIndexService.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public PostgresVectorIndexService(
        @org.springframework.beans.factory.annotation.Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
        EmbeddingService embeddingService,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean enabled() { return true; }

    @Override
    public void upsert(RetrievedKnowledgeChunk chunk) {
        String sql = """
            INSERT INTO enterprise_ai_vectors
                (chunk_id, document_id, knowledge_base_id, title, content, metadata, embedding, updated_at)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), CURRENT_TIMESTAMP)
            ON CONFLICT (chunk_id) DO UPDATE SET
                document_id = EXCLUDED.document_id,
                knowledge_base_id = EXCLUDED.knowledge_base_id,
                title = EXCLUDED.title,
                content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding,
                updated_at = CURRENT_TIMESTAMP
            """;
        try {
            jdbcTemplate.update(sql, chunk.id(), chunk.documentId(), chunk.knowledgeBaseId(), chunk.title(),
                chunk.content(), json(chunk.metadata()), vectorLiteral(embeddingService.embed(chunk.content())));
        } catch (RuntimeException ex) {
            log.warn("Vector upsert failed for chunk {}; MySQL data remains available for keyword fallback: {}", chunk.id(), ex.getMessage());
        }
    }

    @Override
    public void delete(long chunkId) {
        try {
            jdbcTemplate.update("DELETE FROM enterprise_ai_vectors WHERE chunk_id = ?", chunkId);
        } catch (RuntimeException ex) {
            log.warn("Vector delete failed for chunk {}: {}", chunkId, ex.getMessage());
        }
    }

    @Override
    public List<RetrievedKnowledgeChunk> search(String query, List<Long> knowledgeBaseIds, int topK) {
        int limit = Math.min(20, Math.max(1, topK));
        float[] embedding = embeddingService.embed(query);
        StringBuilder sql = new StringBuilder("""
            SELECT chunk_id, document_id, knowledge_base_id, title, content, metadata,
                   1 - (embedding <=> CAST(? AS vector)) AS score
            FROM enterprise_ai_vectors
            """);
        List<Object> args = new ArrayList<>();
        args.add(vectorLiteral(embedding));
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            sql.append(" WHERE knowledge_base_id IN (");
            sql.append("?, ".repeat(Math.max(0, knowledgeBaseIds.size() - 1))).append("?)");
            args.addAll(knowledgeBaseIds);
        }
        sql.append(" ORDER BY embedding <=> CAST(? AS vector) LIMIT ?");
        args.add(vectorLiteral(embedding));
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapChunk, args.toArray());
    }

    @Override
    public String status() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT current_database() AS database_name, current_user AS database_user,
                       (SELECT extversion FROM pg_extension WHERE extname = 'vector') AS vector_version,
                       (SELECT COUNT(*) FROM enterprise_ai_vectors) AS vector_rows
                """);
            return "connected: PostgreSQL " + row.get("database_name") + ", user=" + row.get("database_user")
                + ", pgvector=" + row.get("vector_version") + ", rows=" + row.get("vector_rows");
        } catch (RuntimeException ex) {
            return "error: " + ex.getMessage();
        }
    }

    private RetrievedKnowledgeChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedKnowledgeChunk(
            rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getLong("knowledge_base_id"),
            rs.getString("title"), rs.getString("content"), rs.getDouble("score"), parseMetadata(rs.getString("metadata")));
    }

    private Map<String, String> parseMetadata(String raw) {
        try {
            return raw == null ? Map.of() : objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String json(Map<String, String> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("Metadata serialization failed", ex); }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) result.append(',');
            result.append(Float.toString(vector[i]));
        }
        return result.append(']').toString();
    }
}
