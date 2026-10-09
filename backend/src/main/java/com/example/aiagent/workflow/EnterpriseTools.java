package com.example.aiagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class EnterpriseTools {
    private final ObjectMapper json;private final String node,script;private final int port;private McpSyncClient mcp;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public EnterpriseTools(ObjectMapper json,@Value("${app.workflow.mcp.node}") String node,@Value("${app.workflow.mcp.script}") String script,@Value("${app.workflow.mcp.http-port}") int port){this.json=json;this.node=node;this.script=Path.of(script).toAbsolutePath().normalize().toString();this.port=port;}
    private synchronized McpSyncClient client() {
        if(mcp!=null)return mcp;
        var parameters=ServerParameters.builder(node).args(script).env(Map.of("TOOL_PORT",String.valueOf(port))).build();
        McpSyncClient candidate=McpClient.sync(new StdioClientTransport(parameters, io.modelcontextprotocol.json.McpJsonMapper.getDefault())).requestTimeout(Duration.ofSeconds(12)).build();
        try{candidate.initialize();mcp=candidate;return mcp;}catch(Exception e){candidate.close();throw new IllegalStateException("MCP 初始化失败 请执行 mcp-server 的 npm ci",e);}
    }
    public synchronized Map<String,Object> report(String period)throws Exception {
        var c=client();var catalog=c.listTools();if(catalog.tools().stream().noneMatch(t->t.name().equals("query_sales_report")))throw new IllegalStateException("MCP 未发现报表工具");
        var result=c.callTool(new McpSchema.CallToolRequest("query_sales_report",Map.of("period",period)));
        if(Boolean.TRUE.equals(result.isError()))throw new IllegalArgumentException("报表 MCP 返回业务错误");
        String text=result.content().stream().filter(x->x instanceof McpSchema.TextContent).map(x->((McpSchema.TextContent)x).text()).findFirst().orElseThrow();
        Map<String,Object> data=json.readValue(text,Map.class);data.put("transport","MCP stdio JSON-RPC");data.put("toolName","query_sales_report");data.put("discoveredTools",catalog.tools().stream().map(McpSchema.Tool::name).toList());return data;
    }
    public Map<String,Object> execute(String name,Map<String,Object> arguments)throws Exception {
        if(!Set.of("lookup_order","calculate_refund").contains(name))throw new IllegalArgumentException("工具不在注册目录内");
        String id=Objects.toString(arguments.get("orderId"),"");if(!id.matches("SO[0-9]{8}"))throw new IllegalArgumentException("请提供 SO 开头加8位数字的订单号");
        if(arguments.size()!=1)throw new IllegalArgumentException("工具参数只接受 orderId");
        client();String endpoint=name.equals("lookup_order")?"/orders":"/refund/quote";
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+endpoint+"?orderId="+id)).timeout(Duration.ofSeconds(5)).GET().build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(response.statusCode()!=200)throw new IllegalArgumentException("订单工具返回 HTTP "+response.statusCode()+" 请核实订单号");
        Map<String,Object> data=json.readValue(response.body(),Map.class);data.put("transport","HTTP GET");data.put("endpoint",endpoint);return data;
    }
    public List<Map<String,Object>> nativeTools(){return List.of(schema("lookup_order","只读查订单商品金额状态签收天数"),schema("calculate_refund","只读试算退款金额和是否符合退货条件 不执行退款"));}
    private Map<String,Object> schema(String name,String description){return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",Map.of("type","object","properties",Map.of("orderId",Map.of("type","string","description","订单号例如SO20261001","pattern","^SO[0-9]{8}$")),"required",List.of("orderId"),"additionalProperties",false)));}
    @PreDestroy synchronized void shutdown(){if(mcp!=null)mcp.closeGracefully();}
}
