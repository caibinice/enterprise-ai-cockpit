package com.example.aiagent.workflow;

import jakarta.validation.Valid;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;

/** Mounted inside the existing WebFlux API; mutation requests use ActionAuthWebFilter. */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    private final WorkflowEngine engine;
    private final RunStore store;
    private final WorkflowModelGateway llm;
    private final KnowledgeIndex kb;
    public WorkflowController(WorkflowEngine engine,RunStore store,WorkflowModelGateway llm,KnowledgeIndex kb) {
        this.engine=engine; this.store=store; this.llm=llm; this.kb=kb;
    }
    private <T> Mono<T> blocking(Callable<T> action) { return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()); }
    @GetMapping("/health") public Map<String,Object> health() {
        return Map.of("status","UP","engine","Spring AI Alibaba Graph","model",llm.config(),"knowledgeDocuments",kb.documents().size(),
            "dataSource","isolated enterprise fixtures; not production ERP","storage","shared workflow checkpoints","transport","WebFlux SSE");
    }
    @GetMapping("/graph") public Map<String,Object> graph() { return engine.definition(); }
    @PostMapping("/runs") public Mono<Map<String,Object>> run(@Valid @RequestBody RunRequest request) {
        return blocking(() -> { var r=engine.start(request); return Map.of("id",r.id,"status",r.status); });
    }
    @GetMapping("/runs") public List<Map<String,Object>> history() { return store.list(); }
    @GetMapping("/runs/{id}") public Map<String,Object> get(@PathVariable String id) { return store.snapshot(store.get(id)); }
    @GetMapping(value="/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String,Object>>> events(@PathVariable String id,@RequestParam(defaultValue="0") int after,
        @RequestHeader(value="Last-Event-ID",required=false) String last) {
        int cursor=Math.max(0,after);
        try { if(last!=null) cursor=Math.max(cursor,Integer.parseInt(last)); } catch(NumberFormatException ignored) {}
        // No event ID on heartbeats: reconnect must advance only on persisted trace events.
        return store.subscribe(id,cursor).publish(shared -> Flux.merge(shared,
            Flux.interval(Duration.ofSeconds(15)).map(t -> ServerSentEvent.<Map<String,Object>>builder().comment("keepalive").build())
                .takeUntilOther(shared.ignoreElements()))).take(Duration.ofMinutes(5));
    }
    @ModelAttribute public void streamingHeaders(org.springframework.web.server.ServerWebExchange exchange) {
        if (exchange.getRequest().getPath().value().endsWith("/events")) {
            exchange.getResponse().getHeaders().set("X-Accel-Buffering","no");
            exchange.getResponse().getHeaders().setCacheControl("no-cache");
        }
    }
    @PostMapping("/runs/{id}/human") public Mono<Map<String,Object>> human(@PathVariable String id,@RequestBody Map<String,String> body) {
        return blocking(() -> { engine.resume(id,body.get("reply")); return Map.of("status","RUNNING"); });
    }
    @PostMapping("/runs/{id}/cancel") public Mono<Map<String,Object>> cancel(@PathVariable String id) {
        return blocking(() -> { engine.cancel(id); return Map.of("status",store.get(id).status); });
    }
    @GetMapping("/runs/{id}/export") public ResponseEntity<Map<String,Object>> export(@PathVariable String id) {
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=trace-"+id+".json").body(store.snapshot(store.get(id)));
    }
    @GetMapping("/knowledge") public List<Map<String,Object>> knowledge() { return kb.documents(); }
    @PostMapping("/knowledge") public Mono<Map<String,Object>> add(@RequestBody Map<String,String> body) {
        return blocking(() -> kb.add(body.get("title"),body.get("content"),body.getOrDefault("knowledgeBase","support")));
    }
    @GetMapping("/demo-image") public Mono<ResponseEntity<byte[]>> image() {
        return blocking(() -> { try(var in=new ClassPathResource("workflow-fixtures/customer-e02.png").getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(in.readAllBytes());
        } });
    }
    @ExceptionHandler({IllegalArgumentException.class,NoSuchElementException.class}) public ResponseEntity<Map<String,String>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> unavailable(Exception e) {
        return ResponseEntity.status(503).body(Map.of("error",e.getMessage()));
    }
}
