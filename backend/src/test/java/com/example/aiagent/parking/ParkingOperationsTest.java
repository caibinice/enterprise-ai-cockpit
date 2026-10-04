package com.example.aiagent.parking;

import com.example.aiagent.security.*;
import com.example.aiagent.service.*;
import com.example.aiagent.repository.InMemoryEnterpriseRepository;
import com.example.aiagent.config.LlmProperties;
import com.example.aiagent.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParkingOperationsTest {
    final ObjectMapper json=new ObjectMapper();final JdbcDataSource source=new JdbcDataSource();
    final ActionAuthService admin=new ActionAuthService("test-password","test-secret-with-long-entropy-for-parking",30);
    JdbcTemplate jdbc;ParkingAccessService access;ParkingDataService data;ParkingNavigationService navigation;ParkingWorkflowService workflow;
    final ParkingAccessService.Principal manager=new ParkingAccessService.Principal("admin","admin"),visitor=new ParkingAccessService.Principal("visitor","visitor"),security=new ParkingAccessService.Principal("guard","security");
    @BeforeEach void setup()throws Exception{
        source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V3__parking_operations.sql")).execute(source);
        jdbc=new JdbcTemplate(source);access=new ParkingAccessService(jdbc,admin,"test-secret-with-long-entropy-for-parking");data=new ParkingDataService(jdbc,json,access);navigation=new ParkingNavigationService(jdbc,json,data,access);
        jdbc.update("INSERT INTO parking_dataset VALUES('parking-year-v1','2025-10-03','2026-10-02','https://example.test/dataset','sha',1,?)",json.writeValueAsString(Map.of("startDate","2025-10-03","endDate","2026-10-02","feePolicy","synthetic-fee")));
        for(String z:List.of("A","B","C"))for(String at:List.of("2026-10-02 12:00:00","2026-10-02 12:15:00"))jdbc.update("INSERT INTO parking_occupancy VALUES('parking-year-v1',?,?,100,?,5,3)",at,z,z.equals("C")?85:60);
        jdbc.update("INSERT INTO parking_stays VALUES(1,'parking-year-v1','A','模拟-A-1','2026-10-02 09:00:00','2026-10-02 12:00:00','outpatient',1000,1000)");
        jdbc.update("INSERT INTO parking_alerts VALUES(1,'parking-year-v1','C','模拟告警','高','2026-10-02 12:00:00','open')");
        navigation.initialize(manager);var knowledge=new KnowledgeBaseService(new InMemoryEnterpriseRepository(json),json);var model=mock(ModelGateway.class);
        workflow=new ParkingWorkflowService(data,navigation,access,model,new ChatModelCatalog(new LlmProperties(false,"mock","","",ChatModelCatalog.FLASH)),knowledge,json);
    }
    @Test void roleTokensRejectTamperingAndDisabledUsers(){
        access.createUser(manager,"guard","保安","security","unique-guard-password");var login=access.login("guard","unique-guard-password","client1");
        String token=String.valueOf(login.get("token"));assertThat(access.principal("Bearer "+token).role()).isEqualTo("security");
        assertThatThrownBy(()->access.principal("Bearer "+token.replace(".security.",".operator."))).hasMessageContaining("401");
        jdbc.update("UPDATE parking_users SET enabled=FALSE WHERE username='guard'");assertThatThrownBy(()->access.principal("Bearer "+token)).hasMessageContaining("401");
        assertThat(access.principal("Bearer "+admin.verifyAndIssue("test-password")).role()).isEqualTo("admin");
    }
    @Test void rolePermissionsAndVisitorDataAreServerBoundaries(){
        assertThatThrownBy(()->data.period(visitor,"yearly")).hasMessageContaining("403");assertThatThrownBy(()->access.createUser(security,"op","运营","operator","operator-password-good")).hasMessageContaining("403");
        assertThat((List<?>)data.snapshot(visitor).get("events")).isEmpty();assertThat((List<?>)data.snapshot(visitor).get("alerts")).isEmpty();
        assertThat((List<?>)data.snapshot(manager).get("events")).hasSize(2);
        assertThat(workflow.tools(visitor).get("report.show")).contains("occupancy").doesNotContain("yearly","events");
    }
    @Test void paidLedgerNotOccupancyDrivesRevenue(){
        var result=data.period(manager,"yearly");assertThat((Map<String,Object>)result.get("ledger")).containsEntry("paid_cents",1000L);
        assertThat((List<?>)result.get("occupancy")).hasSize(3);assertThatThrownBy(()->data.analytics(manager,LocalDate.of(2020,1,1),LocalDate.of(2026,10,2),null)).hasMessageContaining("范围");
    }
    @Test void nightlyReportsAreDatabaseOnlyAndKnowledgeV2HasIsolatedGuides()throws Exception{
        var reports=new ParkingReportSchedule(jdbc,data,json);reports.refresh();reports.refresh();assertThat(reports.status()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT content_json FROM parking_report_cache WHERE period='yearly'",String.class)).contains("paid_cents\":1000");
        var knowledge=new KnowledgeBaseService(new InMemoryEnterpriseRepository(json),json);
        knowledge.createKnowledgeBase(new KnowledgeBaseRequest("既有手工知识","保留","smart-parking-agent-v1"));
        var docs=new ParkingKnowledgeService(knowledge,json);assertThat(docs.bootstrap()).containsEntry("documents",14).containsEntry("imported",14);
        assertThat(docs.bootstrap()).containsEntry("imported",0);assertThat(knowledge.list()).hasSize(2);
    }
    @Test void graphFindsDeterministicRoutesAndRejectsInvalidCoordinates(){
        var route=navigation.route("entrance","emergency");assertThat((List<?>)route.get("points")).hasSize(7);assertThat(((Number)route.get("meters")).longValue()).isPositive();
        var g=navigation.graph();var bad=new ArrayList<>(g.nodes());bad.add(new ParkingNavigationService.Node("bad","bad","destination",null,Double.NaN,0,true,false,false));
        assertThatThrownBy(()->ParkingNavigationService.validate(new ParkingNavigationService.Graph(1,bad,g.edges()))).hasMessageContaining("坐标");
        var closed=g.edges().stream().map(e->new ParkingNavigationService.Edge(e.from(),e.to(),true)).toList();assertThatThrownBy(()->ParkingNavigationService.route(new ParkingNavigationService.Graph(1,g.nodes(),closed),"entrance","emergency")).hasMessageContaining("没有可通行路径");
    }
    @Test void recommendationsRespectChargingAndEmergencyReserve(){
        var recommendation=navigation.recommend(visitor,"inpatient","charging");var candidates=(List<Map<String,Object>>)recommendation.get("candidates");assertThat(candidates).hasSize(1);assertThat(candidates.get(0).get("zone")).isEqualTo("B");
        var emergency=navigation.recommend(visitor,"emergency","emergency");assertThat((List<Map<String,Object>>)emergency.get("candidates")).allMatch(c->c.get("zone").equals("C"));
    }
    @Test void calibratedZonesAndBothCAreasHaveConnectedRoutes(){
        var g=navigation.graph();var b=g.nodes().stream().filter(n->n.id().equals("parking-b")).findFirst().orElseThrow();
        var c=g.nodes().stream().filter(n->n.id().equals("parking-c")).findFirst().orElseThrow();
        assertThat(b.x()).isNegative();assertThat(b.z()).isNegative();assertThat(c.x()).isPositive();assertThat(c.z()).isGreaterThan(2);
        assertThat(((Number)navigation.route("entrance","parking-c-side").get("meters")).longValue()).isPositive();
        assertThat(navigation.tours().get("operations")).contains("parking-c","parking-c-side");
    }
    @Test void workordersRequireConfirmationReviewAndOptimisticVersion(){
        assertThatThrownBy(()->data.createWorkorder(security,1,"核验","request-0010",false)).hasMessageContaining("确认");
        var created=data.createWorkorder(security,1,"人工核验","request-0010",true);long id=((Number)created.get("id")).longValue();
        assertThat(data.createWorkorder(security,1,"人工核验","request-0010",true).get("id")).isEqualTo(created.get("id"));
        assertThatThrownBy(()->data.transition(security,id,0,"approve","权限验证",true)).hasMessageContaining("403");
        data.transition(manager,id,0,"approve","运营批准",true);assertThatThrownBy(()->data.transition(security,id,0,"assign","领取",true)).hasMessageContaining("409");
        data.transition(security,id,1,"assign","领取",true);data.transition(security,id,2,"resolve","核验完成",true);data.transition(manager,id,3,"close","复核关闭",true);
        assertThat(data.workorders(manager).get(0).get("status")).isEqualTo("closed");assertThat(data.alerts().get(0).get("status")).isEqualTo("closed");assertThat(data.audits(manager)).hasSize(5);
        assertThat(data.createWorkorder(security,1,"幂等重试","request-0010",true).get("id")).isEqualTo(created.get("id"));
        assertThatThrownBy(()->data.createWorkorder(security,1,"过期草稿","request-0011",true)).hasMessageContaining("409");
    }
    @Test void agUiHasLifecycleAndControlledToolArguments(){
        var input=new ParkingWorkflowService.RunInput("thread1","run1",List.of(json.valueToTree(Map.of("id","m1","role","user","content","查看年经营报表"))),json.createObjectNode(),List.of(),List.of(),null);
        var events=workflow.stream(manager,input).collectList().block(Duration.ofSeconds(5));assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");assertThat(events.get(events.size()-1).get("type")).isEqualTo("RUN_FINISHED");
        assertThat(events).anyMatch(e->e.get("type").equals("TOOL_CALL_ARGS")&&e.get("delta").equals("{\"target\":\"yearly\"}"));
        assertThatThrownBy(()->ParkingWorkflowService.parsePlan(json.readTree("[{\"type\":\"report.show\",\"target\":\"yearly\"}]"),workflow.tools(visitor))).hasMessageContaining("权限");
    }
    @Test void allOperationsGetEndpointsRequireRoleSession(){
        var vision=mock(ParkingVisionService.class);var controller=new ParkingOperationsController(access,data,navigation,workflow,vision,mock(ParkingKnowledgeService.class),mock(ParkingReportSchedule.class));
        var client=WebTestClient.bindToController(controller).webFilter(new ActionAuthWebFilter(admin)).build();
        client.get().uri("/api/parking/snapshot").exchange().expectStatus().isUnauthorized();
        var token=access.login("visitor","","visitor1").get("token");
        client.get().uri("/api/parking/snapshot").header("Authorization","Bearer "+token).exchange().expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("database-synthetic");
        client.get().uri("/api/parking/analytics?from=2025-10-03&to=2026-10-02").header("Authorization","Bearer "+token).exchange().expectStatus().isForbidden();
    }
    @Test void visionAcceptsBoundedJpegNotRemoteUrls()throws Exception{
        assertThatThrownBy(()->ParkingVisionService.validateImage("https://localhost/private")).hasMessageContaining("截图");
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB),"jpeg",out);
        ParkingVisionService.validateImage("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()));
        assertThatThrownBy(()->ParkingVisionService.validateImage("data:image/jpeg;base64,xxx")).isInstanceOf(IllegalArgumentException.class);
    }
}
