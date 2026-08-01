package com.example.aiagent.service;

import com.example.aiagent.model.McpExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolResultChartService {
    private final ObjectMapper objectMapper;

    ToolResultChartService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    boolean shouldGenerate(String question, boolean explicitlyEnabled) {
        String text = question == null ? "" : question.toLowerCase();
        if (containsAny(
            text,
            "不需要图表", "无需图表", "不要图表", "不展示图表", "不用画图",
            "no chart", "without chart", "do not chart"
        )) return false;
        if (explicitlyEnabled) return true;
        return containsAny(
            text,
            "图表", "柱状图", "折线图", "饼图", "对比图", "趋势图", "可视化",
            "chart", "graph", "visualize"
        );
    }

    boolean hasWeatherResult(List<McpExecutionResult> toolResults) {
        return toolResults != null && toolResults.stream()
            .anyMatch(result -> "weather".equals(result.id()));
    }

    String weatherChart(List<McpExecutionResult> toolResults) {
        if (toolResults == null) return null;
        for (McpExecutionResult result : toolResults) {
            if (!"weather".equals(result.id()) || !"success".equals(result.status())) continue;
            try {
                JsonNode root = objectMapper.readTree(result.output());
                List<WeatherPoint> points = weatherPoints(root);
                if (!points.isEmpty()) return serializeChart(root, points);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    String weatherAnswer(List<McpExecutionResult> toolResults) {
        if (toolResults == null) return null;
        for (McpExecutionResult result : toolResults) {
            if (!"weather".equals(result.id()) || !"success".equals(result.status())) continue;
            try {
                JsonNode root = objectMapper.readTree(result.output());
                List<WeatherPoint> points = weatherPoints(root);
                if (points.isEmpty()) return null;
                String region = root.path("region").asText("").trim();
                String observedAt = root.path("observedAt").asText("").trim();
                WeatherPoint highest = points.stream()
                    .max(java.util.Comparator.comparingDouble(WeatherPoint::temperatureC))
                    .orElseThrow();
                WeatherPoint lowest = points.stream()
                    .min(java.util.Comparator.comparingDouble(WeatherPoint::temperatureC))
                    .orElseThrow();
                StringBuilder answer = new StringBuilder();
                answer.append("已获取")
                    .append(region.isBlank() ? "" : region)
                    .append(points.size())
                    .append("个城市的实时天气（Open-Meteo");
                if (!observedAt.isBlank()) answer.append("，观测时间 ").append(observedAt);
                answer.append("）。\n\n| 城市 | 天气 | 气温 | 湿度 |\n")
                    .append("|---|---:|---:|---:|\n");
                points.forEach(point -> answer
                    .append("| ").append(point.city())
                    .append(" | ").append(point.condition().isBlank() ? "—" : point.condition())
                    .append(" | ").append(formatTemperature(point.temperatureC())).append("°C")
                    .append(" | ").append(point.humidityPercent() == null ? "—" : point.humidityPercent() + "%")
                    .append(" |\n"));
                answer.append("\n最高为 **")
                    .append(highest.city()).append(' ')
                    .append(formatTemperature(highest.temperatureC())).append("°C**，最低为 **")
                    .append(lowest.city()).append(' ')
                    .append(formatTemperature(lowest.temperatureC())).append("°C**，温差约 **")
                    .append(formatTemperature(highest.temperatureC() - lowest.temperatureC()))
                    .append("°C**。右侧柱状图使用同一份实时数据生成。");
                return answer.toString();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private List<WeatherPoint> weatherPoints(JsonNode root) {
        List<WeatherPoint> points = new ArrayList<>();
        JsonNode cities = root.path("cities");
        if (cities.isArray()) {
            cities.forEach(city -> addWeatherPoint(points, city));
        } else {
            addWeatherPoint(points, root);
        }
        return points;
    }

    private void addWeatherPoint(List<WeatherPoint> points, JsonNode node) {
        JsonNode city = node.path("city");
        JsonNode temperature = node.path("temperatureC");
        if (!city.isTextual() || city.asText().isBlank() || !temperature.isNumber()) return;
        double value = temperature.asDouble();
        if (!Double.isFinite(value)) return;
        points.add(new WeatherPoint(
            city.asText(),
            value,
            node.path("condition").asText(""),
            node.path("humidityPercent").isNumber()
                ? node.path("humidityPercent").asInt()
                : null,
            node.path("stale").asBoolean(false)
        ));
    }

    private String serializeChart(JsonNode root, List<WeatherPoint> points) throws Exception {
        String region = root.path("region").asText("").trim();
        String scope = region.isBlank() ? "城市" : region;
        String observedAt = root.path("observedAt").asText("").trim();
        if (observedAt.isBlank() && root.path("cities").isArray()) {
            observedAt = root.path("cities").path(0).path("observedAt").asText("").trim();
        }
        boolean hasStaleData = points.stream().anyMatch(WeatherPoint::stale);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "echarts");
        spec.put("title", Map.of(
            "text", scope + "各城市实时气温对比",
            "subtext", subtitle(observedAt, hasStaleData),
            "left", "center",
            "top", 8,
            "textStyle", Map.of("fontSize", 15, "fontWeight", 650, "color", "#273248"),
            "subtextStyle", Map.of("fontSize", 9, "color", "#8b95a8")
        ));
        spec.put("tooltip", Map.of(
            "trigger", "axis",
            "axisPointer", Map.of("type", "shadow")
        ));
        spec.put("grid", Map.of(
            "left", 42,
            "right", 18,
            "top", 78,
            "bottom", points.size() > 8 ? 58 : 42
        ));
        spec.put("xAxis", Map.of(
            "type", "category",
            "data", points.stream().map(WeatherPoint::city).toList(),
            "axisTick", Map.of("alignWithLabel", true),
            "axisLabel", Map.of(
                "interval", 0,
                "rotate", points.size() > 8 ? 28 : 0,
                "color", "#667085",
                "fontSize", 9
            )
        ));
        spec.put("yAxis", Map.of(
            "type", "value",
            "name", "气温 (°C)",
            "scale", true,
            "nameTextStyle", Map.of("color", "#7b8599", "fontSize", 9),
            "axisLabel", Map.of("formatter", "{value}°", "color", "#7b8599"),
            "splitLine", Map.of("lineStyle", Map.of("color", "rgba(97, 112, 145, 0.12)"))
        ));

        List<Map<String, Object>> data = points.stream().map(point -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", point.temperatureC());
            item.put("name", point.city());
            item.put("condition", point.condition());
            if (point.humidityPercent() != null) item.put("humidityPercent", point.humidityPercent());
            return item;
        }).toList();
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", "实时气温");
        series.put("type", "bar");
        series.put("data", data);
        series.put("barMaxWidth", 28);
        series.put("label", Map.of(
            "show", true,
            "position", "top",
            "formatter", "{c}°",
            "color", "#46536b",
            "fontSize", 9
        ));
        series.put("itemStyle", Map.of(
            "borderRadius", List.of(6, 6, 2, 2),
            "color", Map.of(
                "type", "linear",
                "x", 0,
                "y", 0,
                "x2", 0,
                "y2", 1,
                "colorStops", List.of(
                    Map.of("offset", 0, "color", "#5368f2"),
                    Map.of("offset", 1, "color", "#7fa8ff")
                )
            )
        ));
        series.put("markLine", Map.of(
            "silent", true,
            "symbol", List.of("none", "none"),
            "label", Map.of("formatter", "平均 {c}°", "fontSize", 9, "color", "#7b68d9"),
            "lineStyle", Map.of("type", "dashed", "color", "#8a7be2"),
            "data", List.of(Map.of("type", "average", "name", "平均气温"))
        ));
        spec.put("series", List.of(series));
        spec.put("animationDuration", 650);
        return objectMapper.writeValueAsString(spec);
    }

    private String subtitle(String observedAt, boolean stale) {
        StringBuilder text = new StringBuilder("数据来源：Open-Meteo");
        if (!observedAt.isBlank()) text.append(" · 观测时间 ").append(observedAt);
        if (stale) text.append(" · 含最近缓存数据");
        return text.toString();
    }

    private String formatTemperature(double value) {
        return java.math.BigDecimal.valueOf(value)
            .stripTrailingZeros()
            .toPlainString();
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }

    private record WeatherPoint(
        String city,
        double temperatureC,
        String condition,
        Integer humidityPercent,
        boolean stale
    ) {}
}
