package com.example.aiagent.parking;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

/** Every endpoint except login resolves a server-signed principal; GET is not implicitly public. */
@RestController
@RequestMapping("/api/parking")
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingOperationsController {
    private final ParkingAccessService access;private final ParkingDataService data;private final ParkingNavigationService navigation;private final ParkingWorkflowService workflow;private final ParkingVisionService vision;private final ParkingKnowledgeService knowledge;private final ParkingReportSchedule reports;
    public ParkingOperationsController(ParkingAccessService access,ParkingDataService data,ParkingNavigationService navigation,ParkingWorkflowService workflow,ParkingVisionService vision,ParkingKnowledgeService knowledge,ParkingReportSchedule reports){this.access=access;this.data=data;this.navigation=navigation;this.workflow=workflow;this.vision=vision;this.knowledge=knowledge;this.reports=reports;}
    private <T> Mono<T> task(java.util.concurrent.Callable<T> call){return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());}
    public record Login(String username,String password){}
    @PostMapping("/login") public Mono<Map<String,Object>> login(@RequestBody Login request,@RequestHeader(value="X-Real-IP",required=false) String ip){return task(()->{if(request.username()==null||request.username().length()>60||request.password()!=null&&request.password().length()>128)throw new IllegalArgumentException("登录字段过长");return access.login(request.username(),request.password(),ip);});}
    @GetMapping("/catalog") public Mono<Map<String,Object>> catalog(@RequestHeader(value="Authorization",required=false) String auth){return task(()->{var actor=access.principal(auth);return Map.of("role",actor.role(),"username",actor.username(),"tools",workflow.tools(actor),"graph",navigation.graph(),"tours",navigation.tours(),"dataset",data.manifest(),"vision",actor.staff(),"agUiVersion","1.0");});}
    @GetMapping("/snapshot") public Mono<Map<String,Object>> snapshot(@RequestHeader(value="Authorization",required=false) String auth){return task(()->data.snapshot(access.principal(auth)));}
    @GetMapping("/analytics") public Mono<Map<String,Object>> analytics(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(required=false) String zone){return task(()->data.analytics(access.principal(auth),from,to,zone));}
    @GetMapping("/stays") public Mono<List<Map<String,Object>>> stays(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int size){return task(()->data.stays(access.principal(auth),page,size));}
    @GetMapping("/route") public Mono<Map<String,Object>> route(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam(defaultValue="entrance") String from,@RequestParam String to){return task(()->{access.principal(auth);return navigation.route(from,to);});}
    @GetMapping("/recommendation") public Mono<Map<String,Object>> recommendation(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam(defaultValue="outpatient") String destination,@RequestParam(defaultValue="standard") String preference){return task(()->navigation.recommend(access.principal(auth),destination,preference));}
    @GetMapping("/workorders") public Mono<List<Map<String,Object>>> workorders(@RequestHeader(value="Authorization",required=false) String auth){return task(()->data.workorders(access.principal(auth)));}
    @GetMapping("/audit") public Mono<List<Map<String,Object>>> audit(@RequestHeader(value="Authorization",required=false) String auth){return task(()->data.audits(access.principal(auth)));}
    public record Workorder(long alertId,String note,String requestKey,boolean confirmed){}
    @PostMapping("/workorders") public Mono<Map<String,Object>> create(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Workorder body){return task(()->data.createWorkorder(access.principal(auth),body.alertId(),body.note(),body.requestKey(),body.confirmed()));}
    public record Transition(int version,String action,String note,boolean confirmed){}
    @PostMapping("/workorders/{id}/transition") public Mono<Map<String,Object>> transition(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable long id,@RequestBody Transition body){return task(()->data.transition(access.principal(auth),id,body.version(),body.action(),body.note(),body.confirmed()));}
    public record User(String username,String displayName,String role,String password){}
    @PostMapping("/users") public Mono<Map<String,Object>> user(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody User user){return task(()->{var actor=access.principal(auth);access.createUser(actor,user.username(),user.displayName(),user.role(),user.password());data.audit(actor,"user.create",user.username(),user.role());return Map.of("username",user.username(),"role",user.role());});}
    @PostMapping("/setup") public Mono<Map<String,Object>> setup(@RequestHeader(value="Authorization",required=false) String auth){return task(()->{var actor=access.principal(auth);navigation.initialize(actor);return Map.of("graphVersion",navigation.graph().version(),"knowledge",knowledge.bootstrap(),"reports",reports.refresh());});}
    @GetMapping("/report-jobs") public Mono<List<Map<String,Object>>> reportJobs(@RequestHeader(value="Authorization",required=false) String auth){return task(()->{var actor=access.principal(auth);access.require(actor,"analytics");return reports.status();});}
    @PutMapping("/graph") public Mono<ParkingNavigationService.Graph> graph(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody ParkingNavigationService.Graph graph){return task(()->navigation.save(access.principal(auth),graph));}
    public record Vision(String question,String screenshot,boolean confirmed){}
    @PostMapping("/vision") public Mono<Map<String,Object>> vision(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Vision request){return task(()->{var actor=access.principal(auth);access.require(actor,"workorder");var result=vision.answer(request.question(),request.screenshot(),request.confirmed());data.audit(actor,"vision.query","current-scene","用户确认发送当前三维场景截图；未保存图像");return result;});}
    @PostMapping(value="/ag-ui",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String,Object>>> agUi(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody ParkingWorkflowService.RunInput input){
        return task(()->access.principal(auth)).flatMapMany(actor->workflow.stream(actor,input)).map(event->ServerSentEvent.<Map<String,Object>>builder().data(event).build());
    }
    public record Feedback(String runId,String type,String target,String result){}
    @PostMapping("/feedback") public Mono<Map<String,Object>> feedback(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Feedback f){return task(()->{var actor=access.principal(auth);if(f.runId()==null||!f.runId().matches("[a-zA-Z0-9-]{1,64}")||f.type()==null||f.target()==null||!workflow.tools(actor).getOrDefault(f.type(),Set.of()).contains(f.target())||f.result()==null||f.result().length()>500)throw new IllegalArgumentException("工具反馈范围无效");if(actor.staff())data.audit(actor,"tool."+f.type(),f.target(),"run="+f.runId()+";client-result="+f.result());return Map.of("recorded",true);});}
}
