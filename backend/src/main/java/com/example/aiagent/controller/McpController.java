package com.example.aiagent.controller;

import com.example.aiagent.model.McpToolOption;
import com.example.aiagent.service.McpToolService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/mcp")
public class McpController {
    private final ObjectProvider<McpToolService> serviceProvider;

    public McpController(ObjectProvider<McpToolService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        McpToolService service = serviceProvider.getIfAvailable();
        return service == null ? Map.of("enabled", false, "message", "MCP is disabled") : Map.of("enabled", true, "status", service.configuredStatus());
    }

    @GetMapping("/tools")
    public List<McpToolOption> tools() {
        McpToolService service = serviceProvider.getIfAvailable();
        return service == null ? List.of() : service.options();
    }

    @GetMapping("/weather")
    public Mono<Map<String, Object>> weather(@RequestParam(defaultValue = "常州") String city) {
        McpToolService service = serviceProvider.getIfAvailable();
        if (service == null) return Mono.just(Map.of("enabled", false, "message", "Set MCP_ENABLED=true to enable weather MCP"));
        return Mono.fromCallable(() -> Map.<String, Object>of("enabled", true, "city", city, "result", service.queryWeather(city)))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
