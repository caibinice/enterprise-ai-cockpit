package com.example.aiagent.service;

import com.example.aiagent.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Produces the fixed 1536-dimensional vector used by the pgvector table.
 * Local mode is deterministic and dependency-free, while an OpenAI-compatible
 * embedding endpoint can be enabled when a real embedding model is available.
 */
@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbeddingService(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public int dimensions() {
        return properties.dimensions();
    }

    public float[] embed(String text) {
        if ("openai-compatible".equalsIgnoreCase(properties.mode()) && StringUtils.hasText(properties.baseUrl()) && StringUtils.hasText(properties.apiKey())) {
            try {
                return remoteEmbedding(text);
            } catch (Exception ex) {
                log.warn("Remote embedding failed; falling back to deterministic local embedding: {}", ex.getMessage());
            }
        }
        return localEmbedding(text);
    }

    private float[] remoteEmbedding(String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", StringUtils.hasText(properties.model()) ? properties.model() : "text-embedding-v3");
        body.put("input", text == null ? "" : text);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(normalizeBaseUrl(properties.baseUrl()) + "/embeddings"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
        JsonNode values = objectMapper.readTree(response.body()).path("data").path(0).path("embedding");
        if (!values.isArray() || values.size() != properties.dimensions()) {
            throw new IllegalStateException("Embedding dimension mismatch: expected " + properties.dimensions() + ", got " + values.size());
        }
        float[] result = new float[properties.dimensions()];
        for (int i = 0; i < result.length; i++) result[i] = (float) values.get(i).asDouble();
        return normalize(result);
    }

    private float[] localEmbedding(String text) {
        float[] vector = new float[properties.dimensions()];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        List<String> features = features(normalized);
        if (features.isEmpty()) features = List.of("<empty>");
        for (String feature : features) addFeature(vector, feature);
        return normalize(vector);
    }

    private List<String> features(String text) {
        List<String> result = new ArrayList<>();
        if (text.isBlank()) return result;
        String[] words = text.split("[^\\p{L}\\p{N}]+", -1);
        for (String word : words) {
            if (!word.isBlank()) result.add("w:" + word);
        }
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            result.add("c:" + codePoints[i]);
            if (i + 1 < codePoints.length) result.add("b:" + codePoints[i] + ":" + codePoints[i + 1]);
        }
        return result;
    }

    private void addFeature(float[] vector, String feature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(feature.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 4; i++) {
                int raw = ((digest[i * 4] & 0xff) << 24) | ((digest[i * 4 + 1] & 0xff) << 16)
                    | ((digest[i * 4 + 2] & 0xff) << 8) | (digest[i * 4 + 3] & 0xff);
                int index = Math.floorMod(raw, vector.length);
                vector[index] += (raw & 1) == 0 ? 1.0f : -1.0f;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create local embedding", ex);
        }
    }

    private float[] normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return vector;
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
        return vector;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StringUtils.hasText(baseUrl) ? baseUrl : "http://127.0.0.1:8000/v1";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
