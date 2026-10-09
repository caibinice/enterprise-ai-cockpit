package com.example.aiagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Ordered public events. Graph execution is independent of the browser's SSE subscription. */
@Service
public class RunStore {
    public static class Run {
        public String id, mode, message, knowledgeBase, fault, answer = "", createdAt = Instant.now().toString();
        public volatile String status = "RUNNING";
        public int maxIterations, sequence;
        public boolean imageAttached;
        public volatile boolean cancelled;
        public Map<String,Object> state = new LinkedHashMap<>();
        public List<Map<String,Object>> events = new ArrayList<>();
        public transient List<FluxSink<ServerSentEvent<Map<String,Object>>>> subscribers = new ArrayList<>();
        public transient Future<?> future;
    }
    private final ConcurrentMap<String,Run> runs = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final Path directory;
    public RunStore(ObjectMapper json, @Value("${app.workflow.data-dir}") String root) {
        this.json = json;
        this.directory = Path.of(root).toAbsolutePath().normalize().resolve("runs");
    }
    @PostConstruct void load() throws Exception {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            // Bound startup memory even if old releases left more than 100 run files.
            for (Path f : files.filter(p -> p.toString().endsWith(".json"))
                .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed()).limit(100).toList()) {
                try {
                    Run r = json.readValue(f.toFile(), Run.class);
                    if ("RUNNING".equals(r.status)) r.status = "INTERRUPTED";
                    runs.put(r.id, r);
                } catch (Exception e) { System.err.println("Skipped unreadable workflow run: " + f.getFileName()); }
            }
        }
    }
    public synchronized Run create(RunRequest request) {
        if (runs.size() >= 100) {
            runs.values().stream().filter(r -> !Set.of("RUNNING", "WAITING_HUMAN").contains(r.status))
                .min(Comparator.comparing(r -> r.createdAt)).ifPresent(r -> {
                    runs.remove(r.id);
                    try { Files.deleteIfExists(directory.resolve(r.id + ".json")); } catch (Exception ignored) {}
                });
            if (runs.size() >= 100) throw new IllegalStateException("演示记录上限100，请先完成或取消待处理任务");
        }
        Run r = new Run();
        r.id = UUID.randomUUID().toString(); r.mode = request.effectiveMode(); r.message = request.message();
        r.knowledgeBase = request.effectiveKb(); r.maxIterations = request.budget(); r.fault = request.effectiveFault();
        r.imageAttached = request.image() != null && !request.image().isBlank();
        runs.put(r.id, r);
        return r;
    }
    public Run get(String id) {
        Run r = runs.get(id);
        if (r == null) throw new NoSuchElementException("运行记录不存在");
        return r;
    }
    public List<Map<String,Object>> list() {
        return runs.values().stream().sorted(Comparator.comparing((Run r) -> r.createdAt).reversed()).limit(40)
            .map(r -> Map.<String,Object>of("id", r.id, "status", r.status, "mode", r.mode, "message", r.message, "createdAt", r.createdAt)).toList();
    }
    public Map<String,Object> snapshot(Run r) {
        synchronized (r) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",r.id); m.put("status",r.status); m.put("mode",r.mode); m.put("message",r.message);
            m.put("knowledgeBase",r.knowledgeBase); m.put("maxIterations",r.maxIterations); m.put("fault",r.fault);
            m.put("imageAttached",r.imageAttached); m.put("createdAt",r.createdAt); m.put("answer",r.answer);
            m.put("state",publicData(r.state)); m.put("events",new ArrayList<>(r.events));
            return m;
        }
    }
    private ServerSentEvent<Map<String,Object>> wire(Map<String,Object> event) {
        return ServerSentEvent.<Map<String,Object>>builder(event).id(event.get("seq").toString()).event("trace").build();
    }
    public void emit(String id, String type, String node, String path, String span, String parent, Map<String,Object> data) {
        Run r = get(id);
        synchronized (r) {
            Map<String,Object> e = new LinkedHashMap<>();
            e.put("seq",++r.sequence); e.put("type",type); e.put("timestamp",Instant.now().toString());
            e.put("traceId",id); e.put("spanId",span);
            if (parent != null && !parent.isBlank()) e.put("parentSpanId",parent);
            e.put("nodeId",node); e.put("path",path); e.putAll(publicMap(data)); r.events.add(e);
            // Copy: a subscriber's cancellation callback can synchronously remove itself.
            for (var sink : new ArrayList<>(r.subscribers)) if (!sink.isCancelled()) sink.next(wire(e));
        }
    }
    public Flux<ServerSentEvent<Map<String,Object>>> subscribe(String id, int after) {
        Run r = get(id);
        return Flux.create(sink -> {
            sink.onDispose(() -> { synchronized (r) { r.subscribers.remove(sink); } });
            synchronized (r) {
                for (var e : r.events) if (((Number)e.get("seq")).intValue() > after) sink.next(wire(e));
                if (Set.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(r.status)) sink.complete();
                else if (!sink.isCancelled()) r.subscribers.add(sink);
            }
        });
    }
    public void persist(Run r) {
        synchronized (r) {
            try {
                Run saved = json.convertValue(snapshot(r),Run.class); saved.sequence = r.sequence;
                Path temp = directory.resolve(r.id + ".tmp"), target = directory.resolve(r.id + ".json");
                json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),saved);
                try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
            } catch (Exception e) { throw new IllegalStateException("保存演示检查点失败",e); }
        }
    }
    public void closeStreams(Run r) {
        synchronized (r) {
            var listeners = new ArrayList<>(r.subscribers); r.subscribers.clear();
            listeners.forEach(FluxSink::complete);
        }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> publicMap(Map<String,Object> m) { return (Map<String,Object>)publicData(m); }
    public static Object publicData(Object obj) {
        if (obj instanceof Map<?,?> m) {
            Map<String,Object> out = new LinkedHashMap<>();
            m.forEach((k,v) -> {
                String key = k.toString();
                if (!key.startsWith("__") && !key.equals("image") && !key.toLowerCase(Locale.ROOT).contains("api-key")) out.put(key,publicData(v));
            });
            return out;
        }
        if (obj instanceof Collection<?> c) return c.stream().map(RunStore::publicData).toList();
        if (obj instanceof String s && s.startsWith("data:image/")) return "[image bytes omitted]";
        return obj;
    }
}
