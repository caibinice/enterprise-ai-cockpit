package com.example.aiagent.service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolServiceTest {
    @Test
    void discoversToolsAndCallsWeatherThroughTheSdk() {
        McpSyncClient client = workingClient();
        McpToolService service = new McpToolService(List.of(client));

        var first = service.executeSelected("今天气怎么样", List.of("weather"));
        var second = service.executeSelected("常州天气怎么样", List.of("weather"));

        assertThat(first).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("success");
            assertThat(result.output()).contains("Open-Meteo");
        });
        assertThat(second).singleElement().extracting("status").isEqualTo("success");
        ArgumentCaptor<McpSchema.CallToolRequest> request =
            ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(2)).callTool(request.capture());
        assertThat(request.getAllValues().get(0).name()).isEqualTo("queryWeather");
        assertThat(request.getAllValues().get(0).arguments()).containsEntry("city", "常州");
        verify(client, times(1)).listTools();
    }

    @Test
    void retriesDiscoveryAfterInitializationFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(false);
        when(client.initialize())
            .thenThrow(new IllegalStateException("temporary initialization failure"))
            .thenReturn(null);
        when(client.listTools()).thenReturn(toolList());
        when(client.callTool(any())).thenReturn(successfulWeather());
        McpToolService service = new McpToolService(List.of(client));

        var failed = service.executeSelected("常州天气", List.of("weather"));
        var recovered = service.executeSelected("常州天气", List.of("weather"));

        assertThat(failed).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("error");
            assertThat(result.output()).contains("暂时不可用");
        });
        assertThat(recovered).singleElement().extracting("status").isEqualTo("success");
        verify(client, times(2)).initialize();
    }

    @Test
    void treatsMcpIsErrorAsAControlledToolFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.listTools()).thenReturn(toolList());
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("{\"error\":\"upstream unavailable\"}")),
            true
        ));
        McpToolService service = new McpToolService(List.of(client));

        var result = service.executeSelected("常州天气", List.of("weather"));

        assertThat(result).singleElement().satisfies(tool -> {
            assertThat(tool.status()).isEqualTo("error");
            assertThat(tool.output()).doesNotContain("upstream unavailable");
        });
    }

    @Test
    void rediscoversToolsAfterATransportFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.listTools()).thenReturn(toolList());
        when(client.callTool(any()))
            .thenThrow(new IllegalStateException("stdio transport closed"))
            .thenReturn(successfulWeather());
        McpToolService service = new McpToolService(List.of(client));

        var failed = service.executeSelected("常州天气", List.of("weather"));
        var recovered = service.executeSelected("常州天气", List.of("weather"));

        assertThat(failed).singleElement().extracting("status").isEqualTo("error");
        assertThat(recovered).singleElement().extracting("status").isEqualTo("success");
        verify(client, times(2)).listTools();
    }

    @Test
    void doesNotMistakeTemporalWordsForACity() {
        McpSyncClient client = workingClient();
        McpToolService service = new McpToolService(List.of(client));

        service.executeSelected("今天天气怎么样", List.of("weather"));
        service.executeSelected("今天无锡天气怎么样", List.of("weather"));

        ArgumentCaptor<McpSchema.CallToolRequest> request =
            ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(2)).callTool(request.capture());
        assertThat(request.getAllValues().get(0).arguments())
            .containsEntry("city", "常州");
        assertThat(request.getAllValues().get(1).arguments())
            .containsEntry("city", "无锡");
    }

    private McpSyncClient workingClient() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.isInitialized()).thenReturn(false);
        when(client.initialize()).thenReturn(null);
        when(client.listTools()).thenReturn(toolList());
        when(client.callTool(any())).thenReturn(successfulWeather());
        return client;
    }

    private McpSchema.ListToolsResult toolList() {
        return new McpSchema.ListToolsResult(
            List.of(new McpSchema.Tool(
                "queryWeather",
                "Query current weather",
                "{\"type\":\"object\"}"
            )),
            null
        );
    }

    private McpSchema.CallToolResult successfulWeather() {
        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(
                "{\"city\":\"常州\",\"temperatureC\":33.6,\"source\":\"Open-Meteo\"}"
            )),
            false
        );
    }
}
