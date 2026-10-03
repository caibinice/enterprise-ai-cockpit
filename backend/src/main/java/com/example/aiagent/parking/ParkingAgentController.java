package com.example.aiagent.parking;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/parking-agent")
public class ParkingAgentController {
    private final ParkingAgentService agent;
    public ParkingAgentController(ParkingAgentService agent) { this.agent = agent; }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() { return agent.catalog(); }

    // Existing ActionAuthWebFilter protects both POST routes with the cockpit operation token.
    @PostMapping("/knowledge/bootstrap")
    public Mono<Map<String, Object>> bootstrap() {
        return Mono.fromCallable(agent::bootstrap).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ParkingAgentRequest request) {
        return agent.stream(request).map(e -> ServerSentEvent.<String>builder()
            .event(e.event()).data(e.data()).build())
            .onErrorResume(error -> Flux.just(
                ServerSentEvent.<String>builder().event("error")
                    .data(error instanceof IllegalArgumentException ? error.getMessage() : "助手请求未完成，请稍后重试").build(),
                ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
    }
}
