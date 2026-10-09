package com.example.aiagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"app.repository.mode=memory", "app.vector.enabled=false", "spring.ai.mcp.client.enabled=false", "app.mcp.enabled=false", "app.llm.enabled=false"})
class WorkflowEngineTest {
    static final Path testRoot=Path.of("../.runtime/test-workspace-"+UUID.randomUUID()).toAbsolutePath().normalize();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r)throws Exception{Files.createDirectories(testRoot);r.add("app.workflow.data-dir",()->testRoot.toString());r.add("app.workflow.mcp.http-port",()->18093);}
    @Autowired WorkflowEngine engine;@Autowired RunStore store;@Autowired KnowledgeIndex kb;@Autowired EnterpriseTools tools;@Autowired ObjectMapper json;
    @SpyBean WorkflowModelGateway gateway;
    RunStore.Run execute(String text,String fault,int budget)throws Exception {RunStore.Run r=engine.start(new RunRequest(text,"offline","support",budget,fault,null,null));await(r);return r;}
    void await(RunStore.Run r)throws Exception {long deadline=System.currentTimeMillis()+30000;while(r.status.equals("RUNNING")&&System.currentTimeMillis()<deadline)Thread.sleep(50);assertThat(r.status).isNotEqualTo("RUNNING");}
    long count(RunStore.Run r,String node){return r.events.stream().filter(e->e.get("type").equals("node.completed")&&e.get("nodeId").equals(node)).count();}
    @Test void productRagUsesRealVectorEvidence()throws Exception {var r=execute("Aero耳机退货政策 7天未使用","none",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(r.answer).contains("KB001");assertThat(count(r,"retrieve")).isEqualTo(1);assertThat(r.state.get("retrieval").toString()).contains("cosine");}
    @Test void visionFixtureIsChecksumVerifiedAndFeedsRag()throws Exception {String image="data:image/png;base64,"+Base64.getEncoder().encodeToString(new org.springframework.core.io.ClassPathResource("workflow-fixtures/customer-e02.png").getInputStream().readAllBytes());var r=engine.start(new RunRequest("图片故障如何处理","offline","support",3,"none",image,"sample.png"));await(r);assertThat(r.status).isEqualTo("COMPLETED");assertThat(r.answer).contains("E02");assertThat(count(r,"vision")).isEqualTo(1);assertThat(json.writeValueAsString(store.snapshot(r))).doesNotContain("data:image/");}
    @Test void reportCallsIndependentMcpServer()throws Exception {var r=execute("查询2026-09销售报表","none",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(r.answer).contains("1286000","12.81");assertThat(r.state.get("report").toString()).contains("MCP stdio JSON-RPC");}
    @Test void nativeStyleToolLoopObservesOrderBeforeRefund()throws Exception {var r=execute("查询SO20261001并试算退款金额","none",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"tool_execute")).isEqualTo(2);assertThat(r.answer).contains("599","只读试算");}
    @Test void multiAgentInvokesThreeCompiledSpecialistGraphs()throws Exception {var r=execute("分别查退货政策、SO20261001退款金额和2026-09销售报表","none",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"specialist")).isEqualTo(3);assertThat(r.events.stream().filter(e->e.get("type").equals("agent.completed")).count()).isEqualTo(3);assertThat(r.answer).contains("rag 专家","report 专家","tool 专家");}
    @Test void retrievalFeedbackChangesQueryAndRunsAgain()throws Exception {var r=execute("Aero耳机退货政策","retrieval_gap",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"retrieve")).isEqualTo(2);assertThat(count(r,"requery")).isEqualTo(1);}
    @Test void reviewCanReturnToDraft()throws Exception {var r=execute("Aero耳机退货政策","review_rework",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"draft")).isEqualTo(2);}
    @Test void toolTimeoutIsAnObservationAndCanRecover()throws Exception {var r=execute("查SO20261001退款金额","tool_timeout",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"tool_execute")).isEqualTo(3);assertThat(r.state.get("observations").toString()).contains("ok=false");}
    @Test void exhaustedBudgetRoutesToHumanInsteadOfSpinning()throws Exception {var r=execute("查SO20261001退款金额","tool_timeout",2);assertThat(r.status).isEqualTo("WAITING_HUMAN");assertThat(count(r,"tool_execute")).isEqualTo(2);assertThat(r.state.get("handoffReason")).isEqualTo("工具循环达到预算上限");engine.cancel(r.id);}
    @Test void humanHandoffPersistsAndResumesWithoutRepeatingTools()throws Exception {var r=execute("请人工客服处理赔偿","none",3);assertThat(r.status).isEqualTo("WAITING_HUMAN");assertThat(count(r,"finish")).isZero();RunStore recovered=new RunStore(json,testRoot.toString());recovered.load();assertThat(recovered.get(r.id).status).isEqualTo("WAITING_HUMAN");engine.resume(r.id,"已核实客户问题，将在一个工作日内回复处理进展");await(r);assertThat(r.status).isEqualTo("COMPLETED");assertThat(r.answer).contains("一个工作日");assertThat(count(r,"human_reply")).isEqualTo(1);assertThat(count(r,"human_handoff")).isEqualTo(1);assertThatThrownBy(()->engine.resume(r.id,"重复提交")).isInstanceOf(IllegalArgumentException.class);}
    @Test void missingOrderIdRoutesToHuman()throws Exception {var r=execute("查询订单状态","none",3);assertThat(r.status).isEqualTo("WAITING_HUMAN");assertThat(r.state.get("handoffReason")).isEqualTo("缺少订单号");engine.cancel(r.id);}
    @Test void knowledgeScopeIsEnforcedAndIngestionSearchesNewContent()throws Exception {assertThat(kb.search("Graph 编排状态管理","engineering")).allSatisfy(d->assertThat(d.get("knowledgeBase")).isEqualTo("engineering"));var d=kb.add("测试知识 GX999","GX999 控制器通过长按蓝色按钮恢复联网","engineering");assertThat(kb.search("GX999 蓝色按钮","engineering")).extracting(x->x.get("id")).contains(d.get("id"));}
    @Test void toolParametersAndImageFormatsAreValidated(){assertThatThrownBy(()->tools.execute("delete_order",Map.of("orderId","SO20261001"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->tools.execute("lookup_order",Map.of("orderId","invalid"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->WorkflowModelGateway.validateImage("data:image/png;base64,aGVsbG8=")).isInstanceOf(IllegalArgumentException.class);}
    @Test void generalInputCompletesWithoutTools()throws Exception {var r=execute("你好","none",3);assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"tool_execute")).isZero();}
    @Test void separateRunsDoNotLeakStateOrEvidence()throws Exception {var report=execute("销售报表2026-09","none",3);var rag=execute("Aero耳机退货政策","none",3);assertThat(rag.state).doesNotContainKeys("report","agentResults","toolOutcome");assertThat(rag.answer).doesNotContain("1286000");assertThat(report.state).doesNotContainKey("evidence");}
    @Test void providerMultipleCallsAreDeferredWithoutBreakingNativeProtocol()throws Exception {
        doReturn(true).when(gateway).configured();
        doReturn(json.readTree("{\"route\":\"tool\",\"confidence\":0.99,\"period\":\"2026-09\",\"orderId\":\"SO20261001\"}"))
            .when(gateway).structured(anyMap(),eq("intent classification"),anyString(),any());
        doReturn("订单已签收，可退商品金额599元，当前仅为只读试算").when(gateway).text(anyMap(),eq("evidence-based answer"),anyString(),anyString());
        doReturn(json.readTree("{\"accepted\":true,\"feedback\":\"通过\"}"))
            .when(gateway).structured(anyMap(),eq("answer review"),anyString(),any());
        var observedBodies=new ArrayList<Map<String,Object>>();
        doAnswer(inv->{Map<String,Object> body=inv.getArgument(2);observedBodies.add(body);boolean first=observedBodies.size()==1;String name=first?"lookup_order":"calculate_refund";
            var c=Map.of("id",first?"c1":"c3","type","function","function",Map.of("name",name,"arguments","{\"orderId\":\"SO20261001\"}"));
            var duplicate=Map.of("id","c2","type","function","function",Map.of("name",name,"arguments","{\"orderId\":\"SO20261001\"}"));
            return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("content","","tool_calls",first?List.of(c,duplicate):List.of(c))))));
        }).when(gateway).request(anyMap(),eq("native tool decision"),anyMap());
        var r=engine.start(new RunRequest("查询SO20261001并试算退款金额","live","support",3,"none",null,null));await(r);
        assertThat(r.status).isEqualTo("COMPLETED");assertThat(count(r,"tool_execute")).isEqualTo(2);
        var secondMessages=(List<Map<String,Object>>)observedBodies.get(1).get("messages");
        assertThat(secondMessages.stream().filter(m->"tool".equals(m.get("role"))).map(m->m.get("tool_call_id"))).containsExactlyInAnyOrder("c1","c2");
        assertThat(secondMessages.toString()).contains("deferred");
    }
}
