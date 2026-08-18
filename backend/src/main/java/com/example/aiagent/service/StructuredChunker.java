package com.example.aiagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits documents on structural boundaries before falling back to character
 * windows. Headings are carried into their child chunks so isolated retrieval
 * results retain enough document context to be meaningful.
 */
final class StructuredChunker {
    static final int DEFAULT_CHUNK_SIZE = 900;
    static final int DEFAULT_OVERLAP = 120;
    private static final Pattern HEADING = Pattern.compile(
        "^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千0-9]+[章节篇部].*|[0-9]+(?:\\.[0-9]+){0,4}[、.．]?\\s+.+)$"
    );
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;.!])\\s*");

    private final int chunkSize;
    private final int overlap;

    StructuredChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    StructuredChunker(int chunkSize, int overlap) {
        this.chunkSize = Math.max(240, chunkSize);
        this.overlap = Math.min(Math.max(0, overlap), this.chunkSize / 3);
    }

    List<String> split(String raw) {
        String normalized = normalize(raw);
        if (normalized.isBlank()) return List.of();

        List<String> units = structuralUnits(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            for (String segment : splitOversized(unit)) {
                if (!current.isEmpty() && current.length() + 2 + segment.length() > chunkSize) {
                    String completed = current.toString().trim();
                    if (!completed.isBlank()) chunks.add(completed);
                    current.setLength(0);
                    String tail = overlapTail(completed);
                    if (!tail.isBlank()) current.append(tail).append("\n\n");
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(segment.trim());
            }
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());

        Set<String> seen = new LinkedHashSet<>();
        List<String> deduplicated = new ArrayList<>();
        for (String chunk : chunks) {
            String fingerprint = fingerprint(chunk);
            if (!fingerprint.isBlank() && seen.add(fingerprint)) deduplicated.add(chunk);
        }
        return List.copyOf(deduplicated);
    }

    private List<String> structuralUnits(String content) {
        List<String> result = new ArrayList<>();
        String heading = "";
        StringBuilder paragraph = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                flushParagraph(result, paragraph, heading);
                continue;
            }
            if (HEADING.matcher(trimmed).matches()) {
                flushParagraph(result, paragraph, heading);
                heading = trimmed.replaceFirst("^#{1,6}\\s+", "");
                continue;
            }
            if (!paragraph.isEmpty()) paragraph.append('\n');
            paragraph.append(trimmed);
        }
        flushParagraph(result, paragraph, heading);
        if (result.isEmpty()) result.add(content);
        return result;
    }

    private void flushParagraph(List<String> result, StringBuilder paragraph, String heading) {
        if (paragraph.isEmpty()) return;
        String body = paragraph.toString().trim();
        paragraph.setLength(0);
        if (body.isBlank()) return;
        result.add(heading.isBlank() || body.startsWith(heading)
            ? body
            : "章节：" + heading + "\n" + body);
    }

    private List<String> splitOversized(String unit) {
        if (unit.length() <= chunkSize) return List.of(unit);
        String context = "";
        String body = unit;
        int firstLineEnd = unit.indexOf('\n');
        if (unit.startsWith("章节：") && firstLineEnd > 0) {
            context = unit.substring(0, firstLineEnd).trim();
            body = unit.substring(firstLineEnd + 1).trim();
        }
        int bodyLimit = Math.max(120, chunkSize - (context.isBlank() ? 0 : context.length() + 1));
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(body)) {
            if (sentence.isBlank()) continue;
            if (sentence.length() > bodyLimit) {
                if (!current.isEmpty()) {
                    result.add(withContext(context, current.toString()));
                    current.setLength(0);
                }
                for (int start = 0; start < sentence.length(); start += bodyLimit) {
                    result.add(withContext(
                        context,
                        sentence.substring(start, Math.min(sentence.length(), start + bodyLimit))
                    ));
                }
                continue;
            }
            if (!current.isEmpty() && current.length() + sentence.length() > bodyLimit) {
                result.add(withContext(context, current.toString()));
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) result.add(withContext(context, current.toString()));
        return result;
    }

    private String withContext(String context, String body) {
        String trimmed = body == null ? "" : body.trim();
        return context.isBlank() || trimmed.startsWith(context)
            ? trimmed
            : context + "\n" + trimmed;
    }

    private String overlapTail(String value) {
        if (overlap == 0 || value.isBlank()) return "";
        int start = Math.max(0, value.length() - overlap);
        int boundary = Math.max(value.lastIndexOf('。', value.length() - 1), value.lastIndexOf('\n', value.length() - 1));
        if (boundary >= start && boundary + 1 < value.length()) start = boundary + 1;
        return value.substring(start).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String fingerprint(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }
}
