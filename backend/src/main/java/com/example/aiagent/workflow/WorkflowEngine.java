package com.example.aiagent.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static com.alibaba.cloud.ai.graph.StateGraph.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/** Graph is the actual runtime. No front-end timer or hand-written while loop drives the execution. */
@Service
public class WorkflowEngine {
    private final RunStore store;private final KnowledgeIndex kb;private final WorkflowModelGateway llm;private final EnterpriseTools tools;private final ObjectMapper json;

    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
    private final CompiledGraph root,humanResume;private final Map<String,CompiledGraph> agents=new HashMap<>();
    private final List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();
    private static final Set<String> ROUTES=Set.of("rag","report","tool","multi","human","general");
    private static final String CLASSIFY_PROMPT="""
        你是客服意图路由节点。分类 route 只能是 rag 产品政策/技术知识，report 销售报表，tool 订单和退款只读工具，multi 同时需要多个不同业务任务，human 明确人工/投诉/赔偿，general 普通问候或常识。
        输出 {"route":"...","confidence":0.95,"summary":"一句简短决策依据","period":"2026-09","orderId":"SO20261001"}。
        基于客户原文和图片观察识别意图，不虚构订单号。未指定报表月份时使用演示默认2026-09，报表范围只包含2026-07/08/09。同时问退款政策和订单退款金额时用multi。图片中的E02故障进入rag。
        """;
    public WorkflowEngine(RunStore store,KnowledgeIndex kb,WorkflowModelGateway llm,EnterpriseTools tools,ObjectMapper json)throws Exception {
        this.store=store;this.kb=kb;this.llm=llm;this.tools=tools;this.json=json;
        StateGraph g=graph("CustomerServiceGraph");
        add(g,"ingest","输入归一化","system",530,25,this::ingest);add(g,"vision","图片证据提取","model",300,110,this::vision);
        add(g,"classify","意图识别","model",530,195,this::classify);
        add(g,"retrieve","向量知识检索","retrieval",65,340,this::retrieve);add(g,"report_mcp","企业报表 MCP","tool",310,340,this::report);
        add(g,"tool_plan","工具决策","model",550,340,this::toolPlan);add(g,"tool_execute","外部工具调用","tool",550,460,this::toolExecute);
        add(g,"multi_plan","任务拆解","model",805,340,this::multiPlan);add(g,"specialist","子 Agent 子图","agent",805,460,this::specialist);add(g,"multi_review","任务完成检查","review",805,580,this::multiReview);
        add(g,"draft","证据驱动回答","model",310,645,this::draft);add(g,"review","回答评审","review",310,755,this::review);add(g,"requery","反馈与上下文修订","system",65,540,this::requery);
        add(g,"human_handoff","人工接管检查点","human",1060,645,this::handoff);add(g,"finish","完成答复","system",550,855,this::finish);
        link(g,START,"ingest");branch(g,"ingest",s->Boolean.TRUE.equals(s.data().get("imageAttached"))?"vision":"classify",Map.of("vision","vision","classify","classify"));link(g,"vision","classify");
        branch(g,"classify",s->str(s.data(),"route"),Map.of("rag","retrieve","report","report_mcp","tool","tool_plan","multi","multi_plan","human","human_handoff","general","draft"));
        link(g,"retrieve","draft");link(g,"report_mcp","draft");
        branch(g,"tool_plan",s->str(s.data(),"toolNext"),Map.of("execute","tool_execute","done","draft","human","human_handoff"));link(g,"tool_execute","tool_plan");
        link(g,"multi_plan","specialist");link(g,"specialist","multi_review");branch(g,"multi_review",s->str(s.data(),"multiNext"),Map.of("next","specialist","done","draft","human","human_handoff"));
        link(g,"draft","review");branch(g,"review",s->str(s.data(),"reviewNext"),Map.of("accept","finish","requery","requery","revise","draft","human","human_handoff"));link(g,"requery","retrieve");
        link(g,"human_handoff",END);link(g,"finish",END);root=g.compile(CompileConfig.builder().recursionLimit(80).releaseThread(true).build());
        for(String kind:List.of("rag","report","tool"))agents.put(kind,agentGraph(kind));
        StateGraph resume=graph("HumanResumeGraph");resume.addNode("human_reply",wrapped("human_reply",this::humanReply));resume.addNode("finish",wrapped("finish",this::finish));resume.addEdge(START,"human_reply").addEdge("human_reply","finish").addEdge("finish",END);humanResume=resume.compile();
        nodes.add(Map.of("id","human_reply","title","人工反馈恢复","kind","human","x",1060,"y",755));edges.add(Map.of("source","human_handoff","target","human_reply","label","人工提交后恢复"));edges.add(Map.of("source","human_reply","target","finish","label","resume graph"));
    }
    private StateGraph graph(String name){return new StateGraph(name,()->{
        Map<String,KeyStrategy> strategies=new HashMap<>();
        for(String key:List.of("message","mode","knowledgeBase","maxIterations","fault","image","imageAttached","normalizedText","route","classification","period","orderId","query","evidence","retrieval","retrievalAttempt","report","draft","answer","reviewRound","reviewNext","reviewResult","feedback","toolRound","toolNext","toolDecision","toolMessages","pendingTool","observations","toolOutcome","tasks","taskCursor","agentResults","multiNext","handoffReason","awaitingHuman","handoff","humanReply","__runId","__path","__parentSpan","__lastNode"))strategies.put(key,new ReplaceStrategy());
        return strategies;
    });}
    private void add(StateGraph g,String id,String title,String kind,int x,int y,Step fn)throws Exception {g.addNode(id,wrapped(id,fn));nodes.add(Map.of("id",id,"title",title,"kind",kind,"x",x,"y",y));}
    private void link(StateGraph g,String a,String b)throws Exception {g.addEdge(a,b);if(!a.equals(START)&&!b.equals(END))edges.add(Map.of("source",a,"target",b,"label",""));}
    private void branch(StateGraph g,String source,Function<OverAllState,String> choose,Map<String,String> mapping)throws Exception {g.addConditionalEdges(source,edge_async(choose::apply),mapping);mapping.forEach((label,target)->edges.add(Map.of("source",source,"target",target,"label",label)));}
    @FunctionalInterface private interface Step {Map<String,Object> apply(Map<String,Object> state)throws Exception;}
    private com.alibaba.cloud.ai.graph.action.AsyncNodeAction wrapped(String id,Step fn){return node_async(state->{
        Map<String,Object> s=new LinkedHashMap<>(state.data());String runId=str(s,"__runId"),path=str(s,"__path"),parent=str(s,"__parentSpan");RunStore.Run run=store.get(runId);
        if(run.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();
        String span=UUID.randomUUID().toString();s.put("__node",id);s.put("__span",span);
        store.emit(runId,"edge.traversed",id,path,span,parent,Map.of("source",s.getOrDefault("__lastNode",START),"target",id));
        store.emit(runId,"node.started",id,path,span,parent,Map.of("input",s));long start=System.nanoTime();
        try {if(run.mode.equals("offline"))Thread.sleep(160);Map<String,Object> delta=new LinkedHashMap<>(fn.apply(s));
            if(run.cancelled)throw new CancellationException();delta.put("__lastNode",id);
            store.emit(runId,"node.completed",id,path,span,parent,Map.of("durationMs",WorkflowModelGateway.elapsed(start),"output",delta));
            if(path.equals("root")){s.putAll(delta);s.remove("image");run.state=new LinkedHashMap<>(s);store.persist(run);}return delta;
        }catch(CancellationException e){throw e;}catch(Exception e){store.emit(runId,"node.failed",id,path,span,parent,Map.of("durationMs",WorkflowModelGateway.elapsed(start),"error",publicError(e)));throw e;}
    });}
    private CompiledGraph agentGraph(String kind)throws Exception {
        StateGraph g=graph(kind+"SpecialistGraph");
        if(kind.equals("rag")){g.addNode("retrieve",wrapped("retrieve",this::retrieve));g.addEdge(START,"retrieve").addEdge("retrieve","agent_answer");}
        if(kind.equals("report")){g.addNode("report_mcp",wrapped("report_mcp",this::report));g.addEdge(START,"report_mcp").addEdge("report_mcp","agent_answer");}
        if(kind.equals("tool")){g.addNode("tool_plan",wrapped("tool_plan",this::toolPlan));g.addNode("tool_execute",wrapped("tool_execute",this::toolExecute));g.addEdge(START,"tool_plan");g.addConditionalEdges("tool_plan",edge_async(s->str(s.data(),"toolNext")),Map.of("execute","tool_execute","done","agent_answer","human","agent_answer"));g.addEdge("tool_execute","tool_plan");}
        g.addNode("agent_answer",wrapped("agent_answer",s->{Map<String,Object> d=new LinkedHashMap<>(draft(s));d.put("specialistStatus",str(s,"toolNext").equals("human")?"needs_human":"complete");return d;}));g.addEdge("agent_answer",END);return g.compile(CompileConfig.builder().recursionLimit(30).releaseThread(true).build());
    }
    public Map<String,Object> definition(){return Map.of("name","CustomerServiceGraph","engine","Spring AI Alibaba Graph 1.1.2.4-security-fix","nodes",nodes,"edges",edges,"agentGraphs",List.of("ragSpecialistGraph","reportSpecialistGraph","toolSpecialistGraph"),"checkpoint","Application persisted state + HumanResumeGraph");}
    public RunStore.Run start(RunRequest request)throws Exception {
        if(request.effectiveMode().equals("live")&&!llm.configured())throw new IllegalArgumentException("请先配置本地 DeepSeek API key");
        if(request.image()!=null&&!request.image().isBlank())WorkflowModelGateway.validateImage(request.image());
        RunStore.Run run=store.create(request);Map<String,Object> initial=new LinkedHashMap<>();initial.put("__runId",run.id);initial.put("__path","root");initial.put("__parentSpan","");initial.put("message",run.message);initial.put("mode",run.mode);initial.put("knowledgeBase",run.knowledgeBase);initial.put("maxIterations",run.maxIterations);initial.put("fault",run.fault);initial.put("imageAttached",run.imageAttached);if(run.imageAttached)initial.put("image",request.image());
        store.emit(run.id,"run.started","ingest","root",UUID.randomUUID().toString(),"",Map.of("input",Map.of("message",run.message,"mode",run.mode,"knowledgeBase",run.knowledgeBase,"imageAttached",run.imageAttached),"graph",definition().get("name")));
        try{run.future=pool.submit(()->execute(run,root,initial));}catch(RejectedExecutionException e){run.status="FAILED";store.persist(run);throw new IllegalStateException("当前演示队列已满");}return run;
    }
    private void execute(RunStore.Run run,CompiledGraph graph,Map<String,Object> initial){
        try {Map<String,Object> result=graph.invoke(initial,RunnableConfig.builder().threadId(run.id+(graph==humanResume?"/resume":"/root")).build()).orElseThrow().data();synchronized(run){run.state=new LinkedHashMap<>(result);run.state.remove("image");
            if(run.cancelled){run.status="CANCELLED";}else if(Boolean.TRUE.equals(result.get("awaitingHuman"))){run.status="WAITING_HUMAN";run.answer="已生成交接资料，等待人工填写处理意见。";store.emit(run.id,"run.waiting","human_handoff","root",UUID.randomUUID().toString(),"",Map.of("output",Map.of("reason",result.getOrDefault("handoffReason","请求人工"),"handoff",result.getOrDefault("handoff",Map.of()))));}
            else {run.status="COMPLETED";run.answer=str(result,"answer");store.emit(run.id,"run.completed","finish","root",UUID.randomUUID().toString(),"",Map.of("output",Map.of("answer",run.answer)));}store.persist(run);}
        }catch(Throwable e){synchronized(run){run.status=run.cancelled?"CANCELLED":"FAILED";store.emit(run.id,run.cancelled?"run.cancelled":"run.failed","runtime","root",UUID.randomUUID().toString(),"",Map.of("error",publicError(e)));store.persist(run);}}
        finally{if(!run.status.equals("WAITING_HUMAN"))store.closeStreams(run);}
    }
    public void resume(String id,String reply){RunStore.Run r=store.get(id);synchronized(r){if(!r.status.equals("WAITING_HUMAN"))throw new IllegalArgumentException("该任务当前不在人工等待状态");if(reply==null||reply.isBlank()||reply.length()>3000)throw new IllegalArgumentException("请输入3000字以内人工处理意见");
        Map<String,Object> s=new LinkedHashMap<>(r.state);s.put("__runId",id);s.put("__path","root");s.put("__parentSpan","");s.put("__lastNode","human_handoff");s.put("humanReply",reply);s.put("awaitingHuman",false);r.status="RUNNING";r.cancelled=false;
        try{r.future=pool.submit(()->execute(r,humanResume,s));}catch(RejectedExecutionException e){r.status="WAITING_HUMAN";throw new IllegalStateException("当前队列已满 请稍后重试");}store.emit(id,"run.resumed","human_reply","root",UUID.randomUUID().toString(),"",Map.of("input",Map.of("humanReply",reply)));store.persist(r);}}
    public void cancel(String id){RunStore.Run r=store.get(id);synchronized(r){if(!Set.of("RUNNING","WAITING_HUMAN").contains(r.status))return;r.cancelled=true;r.status="CANCELLED";if(r.future!=null)r.future.cancel(true);store.emit(id,"run.cancelled","runtime","root",UUID.randomUUID().toString(),"",Map.of("output","已停止本次编排"));store.persist(r);store.closeStreams(r);}}
    private Map<String,Object> ingest(Map<String,Object> s){return Map.of("normalizedText",str(s,"message").trim(),"requestSummary","输入已归一化 原始图片不写入持久记录","reviewRound",0,"toolRound",0,"observations",List.of());}
    private Map<String,Object> vision(Map<String,Object> s)throws Exception {
        Map<String,Object> observed;
        if(live(s))observed=llm.vision(s,str(s,"image"));else{
            byte[] b=Base64.getDecoder().decode(str(s,"image").split(",",2)[1]);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
            Map<String,Object> f;try(var in=new org.springframework.core.io.ClassPathResource("workflow-fixtures/vision-fixture.json").getInputStream()){f=json.readValue(in,Map.class);}
            if(!hash.equals(f.get("sha256")))throw new IllegalArgumentException("离线图片模式仅使用内置样例 任意图片请选择在线模式");observed=new LinkedHashMap<>((Map<String,Object>)f.get("observation"));observed.put("provider","offline verified fixture annotation");
        }
        return Map.of("visionEvidence",observed,"normalizedText",str(s,"normalizedText")+"\n图片观察："+llm.string(observed));
    }
    private Map<String,Object> classify(Map<String,Object> s){Map<String,Object> result;
        if(live(s)){JsonNode n=llm.structured(s,"intent classification",CLASSIFY_PROMPT,Map.of("customerInput",str(s,"normalizedText")));result=json.convertValue(n,Map.class);}
        else{String q=str(s,"normalizedText");String route=q.matches(".*(人工|投诉|赔偿).* ")?"human":"general";
            if(q.contains("人工")||q.contains("投诉")||q.contains("赔偿"))route="human";
            else if(q.contains("分别")||q.contains("同时")||q.contains("综合"))route="multi";
            else if(q.contains("报表")||q.contains("营收")||q.contains("销售"))route="report";
            else if(Pattern.compile("SO[0-9]{8}").matcher(q).find()||q.contains("订单状态")||q.contains("退款金额"))route="tool";
            else if(q.contains("退货")||q.contains("政策")||q.contains("E02")||q.contains("故障")||q.contains("重置")||q.contains("Graph")||q.contains("编排")||q.contains("loop"))route="rag";
            result=new LinkedHashMap<>(Map.of("route",route,"confidence",.97,"summary","离线显式规则分类 便于稳定讲解","period",period(q),"orderId",orderId(q)));}
        String route=Objects.toString(result.get("route"),"");if(!ROUTES.contains(route))throw new IllegalArgumentException("意图路由值不在定义内");double conf=result.get("confidence") instanceof Number n?n.doubleValue():0;
        if(conf<.6)route="human";return Map.of("classification",result,"route",route,"query",str(s,"normalizedText"),"period",Objects.toString(result.get("period"),period(str(s,"normalizedText"))),"orderId",Objects.toString(result.get("orderId"),orderId(str(s,"normalizedText"))));
    }
    private Map<String,Object> retrieve(Map<String,Object> s){int attempt=num(s,"retrievalAttempt")+1;String query=str(s,"query");List<Map<String,Object>> hits=kb.search(query,str(s,"knowledgeBase"));boolean gap=str(s,"fault").equals("retrieval_gap")&&attempt==1;if(gap)hits=List.of();return Map.of("retrievalAttempt",attempt,"evidence",hits,"retrieval",Map.of("query",query,"knowledgeBase",str(s,"knowledgeBase"),"embedding","local-hashed-ngram","dimensions",2048,"metric","cosine","topK",3,"hitCount",hits.size(),"injectedGap",gap));}
    private Map<String,Object> report(Map<String,Object> s)throws Exception {String period=str(s,"period");if(!Set.of("2026-07","2026-08","2026-09").contains(period))throw new IllegalArgumentException("演示报表支持2026-07至2026-09");return Map.of("report",tools.report(period));}
    private Map<String,Object> toolPlan(Map<String,Object> s)throws Exception {
        int round=num(s,"toolRound");List<Map<String,Object>> observations=list(s,"observations");List<Map<String,Object>> calls=new ArrayList<>();Map<String,Object> delta=new LinkedHashMap<>();
        boolean enough=observations.stream().anyMatch(x->"calculate_refund".equals(x.get("tool"))&&Boolean.TRUE.equals(x.get("ok")))||(!str(s,"normalizedText").contains("退款")&&!str(s,"normalizedText").contains("退货")&&observations.stream().anyMatch(x->Boolean.TRUE.equals(x.get("ok"))));
        if(enough)return Map.of("toolNext","done","toolDecision","所需证据齐全 停止工具循环");
        if(round>=num(s,"maxIterations"))return Map.of("toolNext","human","handoffReason","工具循环达到预算上限","toolDecision","停止继续请求工具");
        if(live(s)){
            List<Map<String,Object>> messages=list(s,"toolMessages");if(messages.isEmpty())messages=new ArrayList<>(List.of(Map.of("role","system","content","你是只读客服工具决策节点。先查询订单再按用户需要试算退款。使用提供的函数工具，每轮最多一个，结果完整后直接回答完成。不要编造订单号。没有订单号时直接说明缺少订单号。工具失败后可重试一次。"),Map.of("role","user","content",str(s,"normalizedText"))));
            boolean orderObserved=observations.stream().anyMatch(x->"lookup_order".equals(x.get("tool"))&&Boolean.TRUE.equals(x.get("ok")));
            String allowedFunction=orderObserved?"calculate_refund":"lookup_order";
            var stageTools=tools.nativeTools().stream().filter(t->allowedFunction.equals(((Map<?,?>)t.get("function")).get("name"))).toList();
            var body=Map.<String,Object>of("model",llm.config().get("model"),"messages",messages,"tools",stageTools,"tool_choice","auto","parallel_tool_calls",false,"thinking",Map.of("type","disabled"),"max_tokens",600);
            JsonNode message=llm.request(s,"native tool decision",body).path("choices").path(0).path("message");JsonNode raw=message.path("tool_calls");
            if(raw.size()>8)throw new IllegalArgumentException("本轮工具请求数量超出上限");
            for(JsonNode c:raw){calls.add(Map.of("id",c.path("id").asText(),"name",c.path("function").path("name").asText(),"arguments",json.readValue(c.path("function").path("arguments").asText(),Map.class)));}
            Map<String,Object> assistant=new LinkedHashMap<>();assistant.put("role","assistant");assistant.put("content",message.path("content").asText(""));if(raw.size()>0)assistant.put("tool_calls",json.convertValue(raw,Object.class));messages=new ArrayList<>(messages);messages.add(assistant);delta.put("toolMessages",messages);
            if(calls.isEmpty()){delta.put("toolNext","human");delta.put("handoffReason","工具未取得完整结果或缺少订单信息");delta.put("toolDecision",message.path("content").asText("没有待调用工具"));return delta;}
            // Some providers emit multiple calls despite parallel_tool_calls=false.
            // Keep protocol balance: one actual action; deferred calls receive explicit observations.
            for(var c:calls)if(!allowedFunction.equals(c.get("name")))throw new IllegalArgumentException("本轮工具违反业务依赖顺序");
            for(int i=1;i<calls.size();i++)messages.add(Map.of("role","tool","tool_call_id",str(calls.get(i),"id"),"content",llm.string(Map.of("status","deferred","reason","每步只执行一个工具 请读取本轮结果后重新规划"))));
            delta.put("toolMessages",messages);
        }else{String id=str(s,"orderId");if(id.isBlank())return Map.of("toolNext","human","handoffReason","缺少订单号","toolDecision","等待人工补齐订单信息");boolean orderFound=observations.stream().anyMatch(x->"lookup_order".equals(x.get("tool"))&&Boolean.TRUE.equals(x.get("ok")));calls.add(Map.of("id","offline-call-"+(round+1),"name",orderFound?"calculate_refund":"lookup_order","arguments",Map.of("orderId",id)));}
        var call=calls.get(0);if(!Set.of("lookup_order","calculate_refund").contains(call.get("name")))throw new IllegalArgumentException("模型选择了未注册的工具");
        String fingerprint=llm.string(call.get("arguments"))+call.get("name");if(observations.stream().anyMatch(x->fingerprint.equals(x.get("fingerprint"))&&Boolean.TRUE.equals(x.get("ok"))))return Map.of("toolNext","human","handoffReason","检测到重复工具调用","toolDecision","停止重复调用");
        delta.put("pendingTool",call);delta.put("toolNext","execute");delta.put("toolDecision",live(s)?"DeepSeek 原生 function calling 选择下一步 宿主按依赖只开放当前阶段工具":"离线工具决策 下一轮将读取真实工具结果");delta.put("deferredToolCalls",Math.max(0,calls.size()-1));return delta;
    }
    private Map<String,Object> toolExecute(Map<String,Object> s)throws Exception {
        Map<String,Object> call=map(s,"pendingTool");String name=str(call,"name");Map<String,Object> args=map(call,"arguments");boolean ok;Object result;
        try{if(str(s,"fault").equals("tool_timeout")&&num(s,"toolRound")==0)throw new java.net.http.HttpTimeoutException("演示注入的一次工具超时");result=tools.execute(name,args);ok=true;}catch(Exception e){ok=false;result=Map.of("error",publicError(e),"retryable",true);}
        List<Map<String,Object>> observed=new ArrayList<>(list(s,"observations"));observed.add(Map.of("tool",name,"arguments",args,"fingerprint",llm.string(args)+name,"ok",ok,"result",result));
        Map<String,Object> out=new LinkedHashMap<>();out.put("observations",observed);out.put("toolRound",num(s,"toolRound")+1);out.put("toolOutcome",Map.of("name",name,"ok",ok,"result",result));
        if(live(s)){List<Map<String,Object>> messages=new ArrayList<>(list(s,"toolMessages"));messages.add(Map.of("role","tool","tool_call_id",str(call,"id"),"content",llm.string(Map.of("ok",ok,"result",result))));out.put("toolMessages",messages);}return out;
    }
    private Map<String,Object> multiPlan(Map<String,Object> s){List<Map<String,Object>> tasks=new ArrayList<>();
        if(live(s)){var n=llm.structured(s,"task decomposition","将请求拆成最多4个互补业务任务。输出 {\"tasks\":[{\"kind\":\"rag|report|tool\",\"question\":\"具体任务 包含订单号或月份\",\"summary\":\"任务目标\"}]}。kind只能为rag report tool。退款政策属于rag，订单实际退款金额属于tool。",Map.of("request",str(s,"normalizedText")));for(JsonNode t:n.path("tasks")){if(tasks.size()==4)break;String kind=t.path("kind").asText(),question=t.path("question").asText();if(!Set.of("rag","report","tool").contains(kind)||question.isBlank())throw new IllegalArgumentException("子任务结构未通过校验");tasks.add(Map.of("kind",kind,"question",question,"summary",t.path("summary").asText()));}}
        else{String q=str(s,"normalizedText");if(q.contains("政策")||q.contains("退货")||q.contains("故障"))tasks.add(Map.of("kind","rag","question","Aero 耳机退货政策 7天 未使用 商品金额 运费","summary","查政策证据"));if(q.contains("销售")||q.contains("报表")||q.contains("营收"))tasks.add(Map.of("kind","report","question",q,"summary","查询销售报表"));if(!str(s,"orderId").isBlank()||q.contains("订单"))tasks.add(Map.of("kind","tool","question","查询订单 "+str(s,"orderId")+" 并试算退款金额","summary","查订单并试算退款"));if(tasks.isEmpty())tasks.add(Map.of("kind","rag","question",q,"summary","查知识证据"));}
        if(tasks.isEmpty())throw new IllegalArgumentException("拆解结果没有有效任务");return Map.of("tasks",tasks,"taskCursor",0,"agentResults",List.of(),"coordination","sequential specialist subgraphs + completion loop");}
    private Map<String,Object> specialist(Map<String,Object> s)throws Exception {
        int cursor=num(s,"taskCursor");Map<String,Object> task=list(s,"tasks").get(cursor);String kind=str(task,"kind"),path="root/"+kind+"Agent["+(cursor+1)+"]";
        Map<String,Object> child=new LinkedHashMap<>();for(String key:List.of("__runId","mode","knowledgeBase","maxIterations","fault","period","orderId"))child.put(key,s.get(key));child.put("__path",path);child.put("__parentSpan",s.get("__span"));child.put("message",task.get("question"));child.put("normalizedText",task.get("question"));child.put("query",task.get("question"));child.put("route",kind);child.put("toolRound",0);child.put("reviewRound",0);child.put("observations",List.of());
        String taskQuestion=str(task,"question");if(!orderId(taskQuestion).isBlank())child.put("orderId",orderId(taskQuestion));if(Pattern.compile("2026-0[789]|[789]月|[七八九]月").matcher(taskQuestion).find())child.put("period",period(taskQuestion));
        store.emit(str(s,"__runId"),"agent.started","specialist",path,str(s,"__span"),str(s,"__parentSpan"),Map.of("input",task,"graph",kind+"SpecialistGraph"));
        Map<String,Object> result=agents.get(kind).invoke(child,RunnableConfig.builder().threadId(str(s,"__runId")+"/"+path).build()).orElseThrow().data();Map<String,Object> publicResult=new LinkedHashMap<>();for(String key:List.of("draft","evidence","report","observations","specialistStatus"))if(result.containsKey(key))publicResult.put(key,result.get(key));publicResult.put("kind",kind);publicResult.put("question",task.get("question"));
        List<Map<String,Object>> results=new ArrayList<>(list(s,"agentResults"));results.add(publicResult);store.emit(str(s,"__runId"),"agent.completed","specialist",path,str(s,"__span"),str(s,"__parentSpan"),Map.of("output",publicResult));return Map.of("agentResults",results,"taskCursor",cursor+1);
    }
    private Map<String,Object> multiReview(Map<String,Object> s){boolean needs=list(s,"agentResults").stream().anyMatch(r->"needs_human".equals(r.get("specialistStatus")));boolean done=num(s,"taskCursor")>=list(s,"tasks").size();return Map.of("multiNext",needs?"human":done?"done":"next","coordinationDecision",needs?"子Agent缺少有效工具结果":done?"全部子任务已返回 汇总证据":"仍有待完成子任务 继续下一位专家","handoffReason",needs?"子Agent需要人工补充":"");}
    private Map<String,Object> draft(Map<String,Object> s){String answer;
        if(live(s))answer=llm.text(s,"evidence-based answer","你是客服回答节点。只使用提供的知识库证据、报表和工具结果回答，明确演示数据口径；引用知识库编号如[KB001]。工具只读，不宣称已执行退款。证据缺失时说明需要补充资料。评审反馈需要据实修订。回答控制在400字内。",llm.string(Map.of("question",str(s,"normalizedText"),"evidence",s.getOrDefault("evidence",List.of()),"report",s.getOrDefault("report",Map.of()),"observations",s.getOrDefault("observations",List.of()),"agentResults",s.getOrDefault("agentResults",List.of()),"feedback",s.getOrDefault("feedback",""))));
        else{StringBuilder b=new StringBuilder();for(var d:list(s,"evidence"))b.append("[").append(d.get("id")).append("] ").append(d.get("title")).append("\n").append(d.get("content")).append("\n\n");
            Map<String,Object> r=map(s,"report");if(!r.isEmpty())b.append(r.get("period")).append(" 月企业演示报表：营收 ¥").append(r.get("revenue")).append("，订单 ").append(r.get("orders")).append("，退款 ").append(r.get("refunds")).append("，环比增长 ").append(r.get("growthPercent")).append("%。来源：query_sales_report MCP。\n\n");
            for(var o:list(s,"observations")){if(Boolean.TRUE.equals(o.get("ok"))){Map<String,Object> d=map(o,"result");if(o.get("tool").equals("lookup_order"))b.append("订单 ").append(d.get("orderId")).append("：").append(d.get("product")).append("，金额 ¥").append(d.get("amount")).append("，状态 ").append(d.get("status")).append("。\n");else b.append("退款试算：符合退货条件 ").append(d.get("eligible")).append("，可退商品金额 ¥").append(d.get("refundableAmount")).append("；").append(d.get("action")).append("。\n");}}
            for(var a:list(s,"agentResults"))b.append("【").append(a.get("kind")).append(" 专家】\n").append(a.get("draft")).append("\n\n");if(b.isEmpty())b.append(str(s,"route").equals("general")?"你好，我可以查询产品知识、订单与退款试算、销售报表，也可以转交人工。你可以选择左侧演示场景查看完整编排过程。":"当前证据尚未齐全，需要重新检索或人工补充。当前为离线讲解模式，回答由可检查的证据模板组织。");answer=b.toString().trim();}
        return Map.of("draft",answer,"answerSource",live(s)?"DeepSeek + retrieved evidence":"offline deterministic evidence renderer");}
    private Map<String,Object> review(Map<String,Object> s){int round=num(s,"reviewRound")+1;boolean accept=true;String feedback="证据及数据来源检查通过",next="accept";
        boolean noEvidence=str(s,"route").equals("rag")&&list(s,"evidence").isEmpty();boolean injected=str(s,"fault").equals("review_rework")&&round==1;
        if(noEvidence){accept=false;feedback="知识证据为空，扩充查询并重检索";next=round<num(s,"maxIterations")?"requery":"human";}
        else if(injected){accept=false;feedback="演示注入评审反馈：补充证据引用和只读工具说明";next=round<num(s,"maxIterations")?"revise":"human";}
        else if(live(s)){var n=llm.structured(s,"answer review","检查回答是否针对原问题、是否由给定证据支持、是否编造实际退款操作。只输出 {\"accepted\":true,\"feedback\":\"一行可执行反馈\"}。证据充分且回答合理则通过。",Map.of("question",str(s,"normalizedText"),"draft",str(s,"draft"),"evidence",s.getOrDefault("evidence",List.of()),"report",s.getOrDefault("report",Map.of()),"observations",s.getOrDefault("observations",List.of()),"agentResults",s.getOrDefault("agentResults",List.of())));accept=n.path("accepted").asBoolean(false);feedback=n.path("feedback").asText("请补充可验证的证据");if(!accept)next=round<num(s,"maxIterations")?"revise":"human";}
        return Map.of("reviewRound",round,"reviewNext",next,"feedback",feedback,"reviewResult",Map.of("accepted",accept,"round",round,"budget",num(s,"maxIterations")),"handoffReason",next.equals("human")?"评审循环达到预算或证据不足":"");}
    private Map<String,Object> requery(Map<String,Object> s){String query=live(s)?llm.text(s,"query rewrite","根据原问题和评审反馈重新写一个知识库检索查询。只输出检索短语，保留产品型号和故障代码。",llm.string(Map.of("question",str(s,"normalizedText"),"feedback",str(s,"feedback")))):str(s,"normalizedText")+" 产品政策 退货 退款 7天 未使用 E02 蓝牙 重置";return Map.of("query",query,"contextChange","把评审反馈纳入检索输入，不是原封不动再问一次");}
    private Map<String,Object> handoff(Map<String,Object> s){String reason=str(s,"handoffReason");if(reason.isBlank())reason="用户要求人工客服或意图置信度不足";return Map.of("awaitingHuman",true,"handoffReason",reason,"handoff",Map.of("message",str(s,"message"),"classification",s.getOrDefault("classification",Map.of()),"evidence",s.getOrDefault("evidence",List.of()),"observations",s.getOrDefault("observations",List.of()),"draft",s.getOrDefault("draft",""),"agentResults",s.getOrDefault("agentResults",List.of()),"reason",reason),"checkpointType","durable application state; separate resume graph");}
    private Map<String,Object> humanReply(Map<String,Object> s){String answer=live(s)?llm.text(s,"human feedback synthesis","按照人工处理意见生成客户答复，不增加未执行的操作；明确这是人工接管后的回复。",llm.string(Map.of("question",str(s,"message"),"humanReply",str(s,"humanReply")))):"人工客服处理意见\n"+str(s,"humanReply")+"\n\n本次回答已基于人工反馈恢复编排。";return Map.of("draft",answer,"awaitingHuman",false,"humanProcessed",true);}
    private Map<String,Object> finish(Map<String,Object> s){return Map.of("answer",str(s,"draft"),"awaitingHuman",false,"stopReason","workflow completed");}
    private boolean live(Map<String,Object> s){return str(s,"mode").equals("live");}
    static String str(Map<String,Object> s,String k){return Objects.toString(s.get(k),"");}static int num(Map<String,Object> s,String k){return s.get(k) instanceof Number n?n.intValue():0;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> s,String k){return s.get(k) instanceof List<?> l?(List<Map<String,Object>>)l:List.of();}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Map<String,Object> s,String k){return s.get(k) instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    private static String orderId(String q){var m=Pattern.compile("SO[0-9]{8}").matcher(q);return m.find()?m.group():"";}
    private static String period(String q){var m=Pattern.compile("2026-(0[789])").matcher(q);if(m.find())return m.group();if(q.contains("8月")||q.contains("八月"))return "2026-08";if(q.contains("7月")||q.contains("七月"))return "2026-07";return "2026-09";}
    private static String publicError(Throwable e){Throwable current=e;while(current.getCause()!=null&&current.getCause()!=current)current=current.getCause();String m=current.getMessage();if(m==null)return "编排执行异常："+current.getClass().getSimpleName();if(m.contains("sk-")||m.length()>300)return "外部请求异常 请检查本地配置和服务状态";return m;}
    @PreDestroy void shutdown(){pool.shutdownNow();}
}
