package com.example.aiagent.service;

import com.example.aiagent.model.*;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private static final Pattern SPLIT = Pattern.compile("[\\s\\p{Punct}]+");
    private static final int CHUNK_SIZE = 800;
    private static final int MAX_TOP_K = 20;
    private final EnterpriseRepository repository;
    private final ObjectMapper objectMapper;
    private final Tika tika = new Tika();
    private volatile VectorIndexService vectorIndexService;

    public KnowledgeBaseService(EnterpriseRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public long createKnowledgeBase(KnowledgeBaseRequest request) {
        return repository.saveKnowledgeBase(request).id();
    }

    public KnowledgeBaseResponse create(KnowledgeBaseRequest request) { return repository.saveKnowledgeBase(request); }
    public List<KnowledgeBaseResponse> list() { return repository.listKnowledgeBases(); }
    public void deleteKnowledgeBase(long id) {
        if (vectorIndexService != null && vectorIndexService.enabled()) {
            repository.findAllChunks().stream().filter(chunk -> chunk.knowledgeBaseId() == id)
                .forEach(chunk -> vectorIndexService.delete(chunk.id()));
        }
        repository.deleteKnowledgeBase(id);
    }

    public KnowledgeDocumentResponse importDocument(long knowledgeBaseId, String title, String content, Map<String, String> metadata) {
        repository.findKnowledgeBase(knowledgeBaseId)
            .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId));
        String safeTitle = title == null || title.isBlank() ? "Untitled document" : title.trim();
        String normalized = normalizeContent(content);
        Map<String, String> safeMeta = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        safeMeta.putIfAbsent("source", safeTitle);
        KnowledgeDocumentResponse document = repository.saveDocument(knowledgeBaseId, safeTitle, normalized, safeMeta, chunk(normalized));
        syncDocumentVectors(document.id());
        return document;
    }

    public KnowledgeDocumentResponse importFile(long knowledgeBaseId, String filename, InputStream inputStream, Map<String, String> metadata) {
        String safeFilename = filename == null || filename.isBlank() ? "Uploaded document" : filename.trim();
        if (inputStream == null) throw new IllegalArgumentException("Uploaded document is empty: " + safeFilename);
        try {
            String content = tika.parseToString(inputStream);
            return importDocument(knowledgeBaseId, safeFilename, content, metadata);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Document parsing failed: " + safeFilename + ", " + ex.getMessage(), ex);
        }
    }

    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) { return repository.listDocuments(knowledgeBaseId); }
    public void updateMetadata(long id, Map<String, String> metadata) {
        ensureDocumentExists(id);
        repository.updateDocumentMetadata(id, metadata);
        syncDocumentVectors(id);
    }
    public void deleteDocument(long id) {
        ensureDocumentExists(id);
        deleteDocumentVectors(id);
        repository.deleteDocument(id);
    }

    public List<RetrievedKnowledgeChunk> search(String query, List<Long> knowledgeBaseIds, Map<String, String> metadataFilter, int topK) {
        Set<String> queryTokens = tokenize(query);
        List<Long> kbIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        Map<String, String> filter = metadataFilter == null ? Map.of() : metadataFilter;
        if (vectorIndexService != null && vectorIndexService.enabled() && query != null && !query.isBlank()) {
            try {
                List<RetrievedKnowledgeChunk> vectorHits = vectorIndexService.search(query, kbIds, topK).stream()
                    .filter(c -> metadataMatches(c.metadata(), filter))
                    .limit(Math.min(MAX_TOP_K, Math.max(1, topK)))
                    .toList();
                if (!vectorHits.isEmpty()) return vectorHits;
            } catch (RuntimeException ignored) {
                // The relational repository remains a safe fallback during a vector database outage.
            }
        }
        return repository.findAllChunks().stream()
            .filter(c -> kbIds.isEmpty() || kbIds.contains(c.knowledgeBaseId()))
            .filter(c -> metadataMatches(c.metadata(), filter))
            .map(c -> new RetrievedKnowledgeChunk(c.id(), c.documentId(), c.knowledgeBaseId(), c.title(), c.content(), score(query, queryTokens, c), c.metadata()))
            .filter(c -> c.score() > 0.0 || !filter.isEmpty())
            .sorted(Comparator.comparingDouble(RetrievedKnowledgeChunk::score).reversed())
            .limit(Math.min(MAX_TOP_K, Math.max(1, topK)))
            .toList();
    }

    public Map<String, String> parseMetadata(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception ex) {
            Map<String, String> fallback = new LinkedHashMap<>();
            for (String part : raw.split("[,;\\n]")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank()) fallback.put(kv[0].trim(), kv[1].trim());
            }
            return fallback;
        }
    }

    private List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        String normalized = normalizeContent(content);
        for (int i = 0; i < normalized.length(); i += CHUNK_SIZE) {
            chunks.add(normalized.substring(i, Math.min(normalized.length(), i + CHUNK_SIZE)));
        }
        return chunks;
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private void ensureDocumentExists(long id) {
        if (repository.findDocument(id).isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + id);
        }
    }

    private boolean metadataMatches(Map<String, String> metadata, Map<String, String> filter) {
        if (filter.isEmpty()) return true;
        Map<String, String> meta = metadata == null ? Map.of() : metadata;
        for (var entry : filter.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            if (!entry.getValue().equalsIgnoreCase(meta.getOrDefault(entry.getKey(), ""))) return false;
        }
        return true;
    }

    private double score(String query, Set<String> queryTokens, RetrievedKnowledgeChunk chunk) {
        String text = (chunk.title() + "\n" + chunk.content() + "\n" + chunk.metadata()).toLowerCase(Locale.ROOT);
        String lowerQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        double score = text.contains(lowerQuery) && !lowerQuery.isBlank() ? 8.0 : 0.0;
        for (String token : queryTokens) {
            if (text.contains(token)) score += token.length() >= 4 ? 2.0 : 1.0;
        }
        return score;
    }

    private Set<String> tokenize(String input) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (String token : SPLIT.split(lower)) {
            if (token.length() >= 2) tokens.add(token);
        }
        for (int i = 0; i < lower.length() - 1; i++) {
            char a = lower.charAt(i), b = lower.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) tokens.add(lower.substring(i, i + 2));
        }
        return tokens;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    @Autowired(required = false)
    void setVectorIndexService(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    private void syncDocumentVectors(long documentId) {
        if (vectorIndexService == null || !vectorIndexService.enabled()) return;
        repository.findAllChunks().stream().filter(chunk -> chunk.documentId() == documentId)
            .forEach(vectorIndexService::upsert);
    }

    private void deleteDocumentVectors(long documentId) {
        if (vectorIndexService == null || !vectorIndexService.enabled()) return;
        repository.findAllChunks().stream().filter(chunk -> chunk.documentId() == documentId)
            .forEach(chunk -> vectorIndexService.delete(chunk.id()));
    }
}
