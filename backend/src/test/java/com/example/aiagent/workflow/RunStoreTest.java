package com.example.aiagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RunStoreTest {
    private RunStore store() throws Exception {
        Path root=Path.of("../.runtime/store-test-"+UUID.randomUUID());
        RunStore store=new RunStore(new ObjectMapper(),root.toString());store.load();return store;
    }
    @Test void completedStreamsReplayOnlyEventsAfterCursor() throws Exception {
        var store=store();var run=store.create(new RunRequest("hello","offline","support",3,"none",null,null));
        for(int i=0;i<6;i++)store.emit(run.id,"node.completed","n","root","s"+i,"",Map.of("output",Map.of("round",i)));
        run.status="COMPLETED";store.closeStreams(run);
        var events=store.subscribe(run.id,3).collectList().block(Duration.ofSeconds(2));
        assertThat(events).extracting(e->e.id()).containsExactly("4","5","6");
        assertThat(store.subscribe(run.id,6).collectList().block(Duration.ofSeconds(2))).isEmpty();
    }
    @Test void subscriptionHasNoGapAndDisconnectDoesNotCancelGraph() throws Exception {
        var store=store();var run=store.create(new RunRequest("hello",null,null,null,null,null,null));
        store.emit(run.id,"node.started","n","root","s","",Map.of());
        List<Integer> seen=new ArrayList<>();
        var subscription=store.subscribe(run.id,0).subscribe(e->seen.add(((Number)e.data().get("seq")).intValue()));
        store.emit(run.id,"node.completed","n","root","s","",Map.of());
        assertThat(seen).containsExactly(1,2);subscription.dispose();
        assertThat(run.subscribers).isEmpty();assertThat(run.cancelled).isFalse();
        store.emit(run.id,"run.completed","finish","root","f","",Map.of());
        run.status="COMPLETED";
        assertThat(store.subscribe(run.id,2).collectList().block(Duration.ofSeconds(2))).hasSize(1);
    }
    @Test void publicEventsOmitInternalStateImagesAndApiKeys() {
        var clean=RunStore.publicMap(Map.of("__runId","private","image","bytes","api-key","TOKEN", "input",Map.of("image","bytes","ok",true)));
        assertThat(clean).doesNotContainKeys("__runId","image","api-key");
        assertThat(clean.get("input")).isEqualTo(Map.of("ok",true));
    }
}
