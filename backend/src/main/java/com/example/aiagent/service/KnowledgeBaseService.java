package com.example.aiagent.service;

import com.example.aiagent.model.KnowledgeBaseRequest;
import com.example.aiagent.model.KnowledgeBaseResponse;
import com.example.aiagent.model.KnowledgeDocumentResponse;
import com.example.aiagent.model.RetrievedKnowledgeChunk;
import com.example.aiagent.repository.EnterpriseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final Pattern SPLIT = Pattern.compile("[\\s\\p{Punct}]+");
    private static final Pattern IDENTIFIER = Pattern.compile(
        "(?i)(?:[a-z]{1,20}[-_./]?[a-z0-9]*\\d[a-z0-9_.\\-/]*|\\d{3,})"
    );
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CANDIDATES = 80;
    private static final double RRF_K = 60.0;
    private static final Set<String> CONTROL_FILTERS = Set.of("_includeInactive", "_includeExpired");
    private static final Set<String> SYSTEM_METADATA = Set.of(
        "source", "sourceType", "parser", "ingestedAt", "contentHash", "chunkStrategy"
    );

    private final EnterpriseRepository repository;
    private final ObjectMapper objectMapper;
    private final Tika tika = new Tika();
    private final StructuredChunker chunker = new StructuredChunker();
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
    public String retrievalStrategy() { return "dense + keyword -> RRF -> exact/freshness rerank -> dedupe/adjacent merge"; }

    public void deleteKnowledgeBase(long id) {
        List<Long> chunkIds = repository.findChunksByKnowledgeBaseId(id).stream()
            .map(RetrievedKnowledgeChunk::id)
            .toList();
        repository.deleteKnowledgeBase(id);
        deleteVectors(chunkIds);
    }

    public KnowledgeDocumentResponse importDocument(
        long knowledgeBaseId,
        String title,
        String content,
        Map<String, String> metadata
    ) {
        repository.findKnowledgeBase(knowledgeBaseId)
            .orElseThrow(() -> new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId));
        String safeTitle = title == null || title.isBlank() ? "Untitled document" : title.trim();
        String normalized = normalizeContent(content);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Document content is empty: " + safeTitle);
        }
        Map<String, String> safeMeta = enrichMetadata(metadata, safeTitle, normalized);
        List<String> chunks = chunker.split(normalized);
        if (chunks.isEmpty()) throw new IllegalArgumentException("Document produced no searchable chunks: " + safeTitle);
        KnowledgeDocumentResponse document = repository.saveDocument(
            knowledgeBaseId,
            safeTitle,
            normalized,
            safeMeta,
            chunks
        );
        syncDocumentVectors(document.id());
        return document;
    }

    public KnowledgeDocumentResponse importFile(
        long knowledgeBaseId,
        String filename,
        InputStream inputStream,
        Map<String, String> metadata
    ) {
        String safeFilename = filename == null || filename.isBlank() ? "Uploaded document" : filename.trim();
        if (inputStream == null) throw new IllegalArgumentException("Uploaded document is empty: " + safeFilename);
        try {
            Map<String, String> parsedMetadata = metadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(metadata);
            parsedMetadata.putIfAbsent("sourceType", tika.detect(safeFilename));
            parsedMetadata.putIfAbsent("parser", "apache-tika");
            String content = tika.parseToString(inputStream);
            return importDocument(knowledgeBaseId, safeFilename, content, parsedMetadata);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                "Document parsing failed: " + safeFilename + ", " + ex.getMessage(),
                ex
            );
        }
    }

    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) {
        return repository.listDocuments(knowledgeBaseId);
    }

    public KnowledgeDocumentResponse getDocument(long id) {
        return repository.findDocument(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
    }

    /** Metadata updates are patches so provenance fields cannot disappear accidentally. */
    public void updateMetadata(long id, Map<String, String> metadata) {
        KnowledgeDocumentResponse document = getDocument(id);
        Map<String, String> merged = new LinkedHashMap<>(document.metadata());
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null && !key.isBlank() && !SYSTEM_METADATA.contains(key)) {
                    if (value == null || value.isBlank()) merged.remove(key);
                    else merged.put(key, value.trim());
                }
            });
        }
        repository.updateDocumentMetadata(id, Map.copyOf(merged));
        syncDocumentVectors(id);
    }

    public void deleteDocument(long id) {
        ensureDocumentExists(id);
        List<Long> chunkIds = repository.findChunksByDocumentId(id).stream()
            .map(RetrievedKnowledgeChunk::id)
            .toList();
        repository.deleteDocument(id);
        deleteVectors(chunkIds);
    }

    /** Rebuilds legacy documents with the current metadata and chunking strategy. */
    public ReindexSummary reindexDocuments(Long knowledgeBaseId) {
        List<KnowledgeDocumentResponse> documents = listDocuments(knowledgeBaseId);
        int previousChunks = documents.stream().mapToInt(KnowledgeDocumentResponse::chunks).sum();
        int currentChunks = 0;
        for (KnowledgeDocumentResponse document : documents) {
            currentChunks += reindexDocument(document.id()).chunks();
        }
        return new ReindexSummary(documents.size(), previousChunks, currentChunks, "structure-aware-v2");
    }

    public KnowledgeDocumentResponse reindexDocument(long id) {
        KnowledgeDocumentResponse document = getDocument(id);
        String normalized = normalizeContent(document.content());
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Document content is empty: " + document.title());
        }
        List<Long> oldChunkIds = repository.findChunksByDocumentId(id).stream()
            .map(RetrievedKnowledgeChunk::id)
            .toList();
        Map<String, String> metadata = enrichMetadata(document.metadata(), document.title(), normalized);
        List<String> chunks = chunker.split(normalized);
        KnowledgeDocumentResponse updated = repository.replaceDocumentIndex(id, metadata, chunks);
        deleteVectors(oldChunkIds);
        syncDocumentVectors(id);
        return updated;
    }

    /**
     * Hybrid retrieval keeps dense and lexical scores separate, fuses their
     * ranks with RRF, then applies small business-aware reranking boosts.
     */
    public List<RetrievedKnowledgeChunk> search(
        String query,
        List<Long> knowledgeBaseIds,
        Map<String, String> metadataFilter,
        int topK
    ) {
        int limit = Math.min(MAX_TOP_K, Math.max(1, topK));
        int candidateLimit = Math.min(MAX_CANDIDATES, Math.max(20, limit * 5));
        String safeQuery = query == null ? "" : query.trim();
        Set<String> queryTokens = tokenize(safeQuery);
        List<String> lexicalTerms = lexicalTerms(safeQuery, queryTokens);
        List<Long> kbIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        Map<String, String> rawFilter = metadataFilter == null ? Map.of() : metadataFilter;
        Map<String, String> filter = retrievalMetadataFilter(rawFilter);
        boolean includeInactive = Boolean.parseBoolean(rawFilter.getOrDefault("_includeInactive", "false"));
        boolean includeExpired = Boolean.parseBoolean(rawFilter.getOrDefault("_includeExpired", "false"));

        List<RetrievedKnowledgeChunk> dense = new ArrayList<>();
        if (vectorIndexService != null && vectorIndexService.enabled() && !safeQuery.isBlank()) {
            try {
                dense = eligible(
                    vectorIndexService.search(safeQuery, kbIds, candidateLimit),
                    filter,
                    includeInactive,
                    includeExpired
                );
            } catch (RuntimeException ex) {
                log.warn("Dense retrieval failed; continuing with keyword candidates: {}", ex.getMessage());
            }
        }

        List<RetrievedKnowledgeChunk> lexical = eligible(
            repository.findKeywordCandidates(kbIds, lexicalTerms, candidateLimit),
            filter,
            includeInactive,
            includeExpired
        ).stream()
            .map(chunk -> withScore(chunk, lexicalScore(safeQuery, queryTokens, chunk)))
            .filter(chunk -> chunk.score() > 0.0 || safeQuery.isBlank() || !filter.isEmpty())
            .sorted(Comparator.comparingDouble(RetrievedKnowledgeChunk::score).reversed())
            .toList();

        List<RetrievedKnowledgeChunk> fused = fuseAndRerank(safeQuery, dense, lexical);
        return mergeAdjacent(deduplicate(fused), limit);
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

    private List<RetrievedKnowledgeChunk> eligible(
        List<RetrievedKnowledgeChunk> candidates,
        Map<String, String> filter,
        boolean includeInactive,
        boolean includeExpired
    ) {
        if (candidates == null) return List.of();
        return candidates.stream()
            .filter(chunk -> metadataMatches(chunk.metadata(), filter))
            .filter(chunk -> searchableNow(chunk.metadata(), includeInactive, includeExpired))
            .toList();
    }

    private List<RetrievedKnowledgeChunk> fuseAndRerank(
        String query,
        List<RetrievedKnowledgeChunk> dense,
        List<RetrievedKnowledgeChunk> lexical
    ) {
        Map<Long, RetrievedKnowledgeChunk> candidates = new LinkedHashMap<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < dense.size(); index++) {
            RetrievedKnowledgeChunk chunk = dense.get(index);
            candidates.putIfAbsent(chunk.id(), chunk);
            scores.merge(chunk.id(), 60.0 * 0.58 / (RRF_K + index + 1), Double::sum);
        }
        for (int index = 0; index < lexical.size(); index++) {
            RetrievedKnowledgeChunk chunk = lexical.get(index);
            candidates.putIfAbsent(chunk.id(), chunk);
            scores.merge(chunk.id(), 60.0 * 0.42 / (RRF_K + index + 1), Double::sum);
        }
        List<String> identifiers = identifiers(query);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<RetrievedKnowledgeChunk> ranked = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            RetrievedKnowledgeChunk chunk = entry.getValue();
            String haystack = searchableText(chunk);
            double score = scores.getOrDefault(entry.getKey(), 0.0);
            if (!lowerQuery.isBlank() && haystack.contains(lowerQuery)) score += 0.35;
            for (String identifier : identifiers) {
                if (haystack.contains(identifier)) score += 0.55;
            }
            if (!lowerQuery.isBlank() && chunk.title().toLowerCase(Locale.ROOT).contains(lowerQuery)) score += 0.18;
            score += versionBoost(chunk.metadata());
            score += freshnessBoost(chunk.metadata());
            ranked.add(withScore(chunk, score));
        }
        return ranked.stream()
            .sorted(Comparator.comparingDouble(RetrievedKnowledgeChunk::score).reversed())
            .toList();
    }

    private List<RetrievedKnowledgeChunk> deduplicate(List<RetrievedKnowledgeChunk> ranked) {
        Set<String> seen = new LinkedHashSet<>();
        List<RetrievedKnowledgeChunk> result = new ArrayList<>();
        for (RetrievedKnowledgeChunk chunk : ranked) {
            String fingerprint = fingerprint(chunk.content());
            if (!fingerprint.isBlank() && seen.add(fingerprint)) result.add(chunk);
        }
        return result;
    }

    private List<RetrievedKnowledgeChunk> mergeAdjacent(List<RetrievedKnowledgeChunk> ranked, int limit) {
        List<RetrievedKnowledgeChunk> result = new ArrayList<>();
        Set<Long> used = new LinkedHashSet<>();
        for (RetrievedKnowledgeChunk chunk : ranked) {
            if (result.size() >= limit) break;
            if (!used.add(chunk.id())) continue;
            int order = chunkOrder(chunk);
            RetrievedKnowledgeChunk adjacent = ranked.stream()
                .filter(candidate -> !used.contains(candidate.id()))
                .filter(candidate -> candidate.documentId() == chunk.documentId())
                .filter(candidate -> Math.abs(chunkOrder(candidate) - order) == 1)
                .filter(candidate -> candidate.score() >= chunk.score() * 0.45)
                .findFirst()
                .orElse(null);
            if (adjacent == null || chunk.content().length() + adjacent.content().length() > 2200) {
                result.add(chunk);
                continue;
            }
            used.add(adjacent.id());
            RetrievedKnowledgeChunk first = order <= chunkOrder(adjacent) ? chunk : adjacent;
            RetrievedKnowledgeChunk second = first == chunk ? adjacent : chunk;
            Map<String, String> metadata = new LinkedHashMap<>(chunk.metadata());
            metadata.put(
                "_chunkRange",
                Math.min(order, chunkOrder(adjacent)) + "-" + Math.max(order, chunkOrder(adjacent))
            );
            result.add(new RetrievedKnowledgeChunk(
                chunk.id(),
                chunk.documentId(),
                chunk.knowledgeBaseId(),
                chunk.title(),
                joinWithoutRepeatedOverlap(first.content(), second.content()),
                Math.max(chunk.score(), adjacent.score()) + 0.03,
                Map.copyOf(metadata)
            ));
        }
        return List.copyOf(result);
    }

    private String joinWithoutRepeatedOverlap(String first, String second) {
        int max = Math.min(240, Math.min(first.length(), second.length()));
        for (int length = max; length >= 24; length--) {
            if (first.regionMatches(first.length() - length, second, 0, length)) {
                return (first + second.substring(length)).trim();
            }
        }
        return (first.trim() + "\n\n" + second.trim()).trim();
    }

    private int chunkOrder(RetrievedKnowledgeChunk chunk) {
        try { return Integer.parseInt(chunk.metadata().getOrDefault("_chunkOrder", "-1000")); }
        catch (NumberFormatException ex) { return -1000; }
    }

    private double lexicalScore(String query, Set<String> tokens, RetrievedKnowledgeChunk chunk) {
        String text = searchableText(chunk);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        double score = !lowerQuery.isBlank() && text.contains(lowerQuery) ? 12.0 : 0.0;
        String lowerTitle = chunk.title().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            int occurrences = Math.min(4, countOccurrences(text, token));
            if (occurrences == 0) continue;
            score += (token.length() >= 4 ? 2.2 : 1.0) * occurrences;
            if (lowerTitle.contains(token)) score += 2.5;
        }
        for (String identifier : identifiers(query)) {
            if (text.contains(identifier)) score += 8.0;
        }
        return score;
    }

    private int countOccurrences(String text, String token) {
        if (token.isBlank()) return 0;
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + token.length())) {
            count++;
            if (count >= 4) break;
        }
        return count;
    }

    private String searchableText(RetrievedKnowledgeChunk chunk) {
        return (chunk.title() + "\n" + chunk.content() + "\n" + chunk.metadata()).toLowerCase(Locale.ROOT);
    }

    private Map<String, String> enrichMetadata(Map<String, String> metadata, String title, String content) {
        Map<String, String> result = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        result.putIfAbsent("source", title);
        result.putIfAbsent("sourceType", sourceType(title));
        result.putIfAbsent("parser", "direct-text");
        result.putIfAbsent("status", "active");
        result.putIfAbsent("ingestedAt", Instant.now().toString());
        result.put("contentHash", sha256(content));
        result.put("chunkStrategy", "structure-aware-v2");
        return Map.copyOf(result);
    }

    private String sourceType(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash document content", ex);
        }
    }

    private Map<String, String> retrievalMetadataFilter(Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !CONTROL_FILTERS.contains(key)) result.put(key, value);
        });
        return Map.copyOf(result);
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

    private boolean searchableNow(Map<String, String> metadata, boolean includeInactive, boolean includeExpired) {
        Map<String, String> meta = metadata == null ? Map.of() : metadata;
        if (!includeInactive) {
            String status = meta.getOrDefault("status", "active").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("", "active", "published", "current", "有效", "已发布").contains(status)) return false;
            if (!meta.getOrDefault("supersededBy", "").isBlank()) return false;
        }
        if (includeExpired) return true;
        Instant now = Instant.now();
        Instant effectiveFrom = parseTemporal(meta.get("effectiveFrom"));
        Instant effectiveTo = parseTemporal(meta.get("effectiveTo"));
        return (effectiveFrom == null || !effectiveFrom.isAfter(now))
            && (effectiveTo == null || !effectiveTo.isBefore(now));
    }

    private Instant parseTemporal(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private double freshnessBoost(Map<String, String> metadata) {
        Instant ingestedAt = parseTemporal(metadata == null ? null : metadata.get("ingestedAt"));
        if (ingestedAt == null) return 0.0;
        long days = Math.max(0, ChronoUnit.DAYS.between(ingestedAt, Instant.now()));
        return 0.08 * Math.max(0.0, 1.0 - Math.min(days, 365) / 365.0);
    }

    private double versionBoost(Map<String, String> metadata) {
        if (metadata == null) return 0.0;
        try {
            String raw = metadata.getOrDefault("version", "0").replaceAll("[^0-9.]", "");
            if (raw.isBlank()) return 0.0;
            double version = Double.parseDouble(raw);
            return Math.min(0.12, Math.max(0.0, version * 0.01));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private List<String> lexicalTerms(String query, Set<String> tokens) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (query != null && !query.isBlank() && query.length() <= 120) result.add(query.toLowerCase(Locale.ROOT));
        result.addAll(identifiers(query));
        tokens.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .limit(12)
            .forEach(result::add);
        return result.stream().limit(12).toList();
    }

    private List<String> identifiers(String input) {
        if (input == null || input.isBlank()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(input.toLowerCase(Locale.ROOT));
        while (matcher.find() && values.size() < 8) values.add(matcher.group());
        return List.copyOf(values);
    }

    private Set<String> tokenize(String input) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (String token : SPLIT.split(lower)) {
            if (token.length() >= 2) tokens.add(token);
        }
        for (int index = 0; index < lower.length() - 1; index++) {
            char first = lower.charAt(index), second = lower.charAt(index + 1);
            if (isCjk(first) && isCjk(second)) tokens.add(lower.substring(index, index + 2));
        }
        return tokens;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private String fingerprint(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    private RetrievedKnowledgeChunk withScore(RetrievedKnowledgeChunk chunk, double score) {
        return new RetrievedKnowledgeChunk(
            chunk.id(), chunk.documentId(), chunk.knowledgeBaseId(), chunk.title(),
            chunk.content(), score, chunk.metadata()
        );
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private void ensureDocumentExists(long id) {
        if (repository.findDocument(id).isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + id);
        }
    }

    @Autowired(required = false)
    void setVectorIndexService(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    private void syncDocumentVectors(long documentId) {
        if (vectorIndexService == null || !vectorIndexService.enabled()) return;
        repository.findChunksByDocumentId(documentId).forEach(vectorIndexService::upsert);
    }

    private void deleteVectors(List<Long> chunkIds) {
        if (vectorIndexService == null || !vectorIndexService.enabled()) return;
        chunkIds.forEach(vectorIndexService::delete);
    }

    public record ReindexSummary(
        int documents,
        int previousChunks,
        int currentChunks,
        String chunkStrategy
    ) {}
}
