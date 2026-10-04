package com.example.aiagent.service;

import com.example.aiagent.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class ModelJsonDeadlineTest {
    OpenAiCompatibleModelGateway gateway(HttpServer server){
        var properties=new LlmProperties(true,"openai-compatible","http://127.0.0.1:"+server.getAddress().getPort(),"test-key-not-secret",ChatModelCatalog.FLASH);
        return new OpenAiCompatibleModelGateway(properties,new ChatModelCatalog(properties),new ObjectMapper(),WebClient.builder());
    }
    @Test void serverErrorsDoNotTriggerASecondModelRequest()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(503,0);exchange.close();});server.start();
        try{assertThat(gateway(server).jsonAnswer("system","question",ChatModelCatalog.FLASH,1000,Duration.ofSeconds(2))).isEmpty();assertThat(calls).hasValue(1);}finally{server.stop(0);}
    }
    @Test void jsonCompatibilityRetryRetainsThinkingMaxAndOneTotalDeadline()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var inputs=new java.util.ArrayList<String>();
        server.createContext("/chat/completions",exchange->{
            inputs.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int call=calls.incrementAndGet();
            String result=call==1?"{}":"{\"choices\":[{\"message\":{\"content\":\"{\\\"answer\\\":\\\"ok\\\",\\\"actions\\\":[]}\"}}]}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(call==1?400:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            assertThat(gateway(server).jsonAnswer("system","question",ChatModelCatalog.FLASH,1000,Duration.ofSeconds(2))).contains("ok");assertThat(calls).hasValue(2);
            assertThat(inputs.get(0)).contains("response_format");assertThat(inputs.get(1)).doesNotContain("response_format").contains("\"reasoning_effort\":\"max\"","\"thinking\":{\"type\":\"enabled\"}");
        }finally{server.stop(0);}
    }
    @Test void modelTimeoutEndsOnceWithinInteractiveBudget()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();try{Thread.sleep(1800);exchange.sendResponseHeaders(200,0);}catch(Exception ignored){}finally{exchange.close();}});server.start();
        try{long started=System.nanoTime();assertThat(gateway(server).jsonAnswer("s","q",ChatModelCatalog.FLASH,1000,Duration.ofMillis(1000))).isEmpty();assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));assertThat(calls).hasValue(1);}finally{server.stop(0);}
    }
}
