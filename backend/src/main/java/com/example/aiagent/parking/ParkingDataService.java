package com.example.aiagent.parking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Queries the immutable one-year synthetic ledger; never accepts client occupancy or fee numbers. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingDataService {
    static final String DATASET="parking-year-v1";
    static final Map<String,String> NAMES=Map.of("A","门诊停车区","B","住院停车区","C","急诊停车区");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ParkingAccessService access;
    public ParkingDataService(JdbcTemplate jdbc,ObjectMapper json,ParkingAccessService access){this.jdbc=jdbc;this.json=json;this.access=access;}
    private List<Map<String,Object>> rows(String sql,Object...args){
        return jdbc.query(sql,(rs,index)->{Map<String,Object> result=new LinkedHashMap<>();ResultSetMetaData metadata=rs.getMetaData();
            for(int col=1;col<=metadata.getColumnCount();col++){Object value=rs.getObject(col);if(value instanceof Timestamp t)value=t.toLocalDateTime().toString();if(value instanceof java.sql.Date d)value=d.toLocalDate().toString();result.put(metadata.getColumnLabel(col).toLowerCase(Locale.ROOT),value);}return result;},args);
    }
    public Map<String,Object> manifest(){
        var found=rows("SELECT manifest_json FROM parking_dataset WHERE id=?",DATASET);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"一年模拟数据库尚未初始化");
        try{return json.readValue(String.valueOf(found.get(0).get("manifest_json")),Map.class);}catch(Exception e){throw new IllegalStateException("Invalid parking manifest",e);}
    }
    public LocalDate latestDate(){return LocalDate.parse(String.valueOf(manifest().get("endDate")));}
    public Map<String,Object> snapshot(ParkingAccessService.Principal actor){
        var at=jdbc.queryForObject("SELECT MAX(observed_at) FROM parking_occupancy WHERE dataset_id=?",Timestamp.class,DATASET);
        if(at==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"停车快照尚未导入");
        var zones=rows("SELECT zone_id AS id,capacity,occupied FROM parking_occupancy WHERE dataset_id=? AND observed_at=? ORDER BY zone_id",DATASET,at);
        zones.forEach(z->z.put("name",NAMES.get(String.valueOf(z.get("id")))));
        Map<String,Object> result=new LinkedHashMap<>();result.put("source","database-synthetic");result.put("dataset",DATASET);
        result.put("sampledAt",at.toLocalDateTime().toString()+"+08:00");result.put("zones",zones);
        result.put("events",actor.staff()?events(30):List.of());result.put("alerts",actor.staff()?alerts():List.of());
        result.put("role",actor.role());result.put("dateRange",List.of(manifest().get("startDate"),manifest().get("endDate")));
        return result;
    }
    public List<Map<String,Object>> events(int limit){
        int take=Math.min(100,Math.max(1,limit));
        return rows("SELECT time,vehicle_alias AS plate,zone_id AS zone,action FROM (SELECT entered_at AS time,vehicle_alias,zone_id,'入场' AS action FROM (SELECT entered_at,vehicle_alias,zone_id FROM parking_stays WHERE dataset_id=? ORDER BY entered_at DESC LIMIT ?) entries UNION ALL SELECT exited_at AS time,vehicle_alias,zone_id,'离场' AS action FROM (SELECT exited_at,vehicle_alias,zone_id FROM parking_stays WHERE dataset_id=? AND exited_at IS NOT NULL ORDER BY exited_at DESC LIMIT ?) exits) e ORDER BY time DESC LIMIT ?",DATASET,take,DATASET,take,take);
    }
    public List<Map<String,Object>> alerts(){
        return rows("SELECT id,zone_id AS zone,title,severity AS level,status,occurred_at FROM parking_alerts WHERE dataset_id=? ORDER BY occurred_at DESC LIMIT 10",DATASET);
    }
    public ParkingAgentRequest.Context agentContext(ParkingAccessService.Principal actor,boolean sceneReady,String selected,String lastResult){
        var snap=snapshot(actor);var zones=(List<Map<String,Object>>)snap.get("zones");
        List<ParkingAgentRequest.Zone> z=zones.stream().map(r->new ParkingAgentRequest.Zone(String.valueOf(r.get("id")),((Number)r.get("capacity")).intValue(),((Number)r.get("occupied")).intValue())).toList();
        List<ParkingAgentRequest.Entry> events=((List<Map<String,Object>>)snap.get("events")).stream().map(r->new ParkingAgentRequest.Entry(String.valueOf(r.get("time")),String.valueOf(r.get("plate")),String.valueOf(r.get("zone")),String.valueOf(r.get("action")))).toList();
        List<ParkingAgentRequest.Alert> alerts=((List<Map<String,Object>>)snap.get("alerts")).stream().map(r->new ParkingAgentRequest.Alert(((Number)r.get("id")).intValue(),String.valueOf(r.get("zone")),String.valueOf(r.get("title")),String.valueOf(r.get("level")),!"open".equals(r.get("status")))).toList();
        return new ParkingAgentRequest.Context("database-synthetic",Instant.now().toString(),sceneReady,z,events,alerts,selected,lastResult);
    }
    public Map<String,Object> analytics(ParkingAccessService.Principal actor,LocalDate from,LocalDate to,String zone){
        access.require(actor,"analytics");var meta=manifest();LocalDate first=LocalDate.parse(String.valueOf(meta.get("startDate"))),last=latestDate();
        if(from==null||to==null||from.isBefore(first)||to.isAfter(last)||to.isBefore(from)||ChronoUnitDays(from,to)>365)throw new IllegalArgumentException("请在模拟数据年份内选择有效范围");
        if(zone!=null&&!NAMES.containsKey(zone))throw new IllegalArgumentException("分区无效");
        String condition=zone==null?"":" AND zone_id=?";List<Object> args=new ArrayList<>(List.of(DATASET,from.atStartOfDay(),to.plusDays(1).atStartOfDay()));if(zone!=null)args.add(zone);
        var occupancy=rows("SELECT CAST(observed_at AS DATE) AS `day`,zone_id,MAX(capacity) AS capacity,ROUND(AVG(occupied),2) AS average_occupied,MAX(occupied) AS peak_occupied,SUM(arrivals) AS arrivals,SUM(departures) AS departures FROM parking_occupancy WHERE dataset_id=? AND observed_at>=? AND observed_at<?"+condition+" GROUP BY CAST(observed_at AS DATE),zone_id ORDER BY `day`,zone_id",args.toArray());
        var ledger=rows("SELECT COUNT(*) AS settled_stays,COALESCE(SUM(paid_cents),0) AS paid_cents,COALESCE(SUM(fee_cents),0) AS fee_cents FROM parking_stays WHERE dataset_id=? AND exited_at>=? AND exited_at<?"+condition,args.toArray()).get(0);
        var daily=rows("SELECT CAST(exited_at AS DATE) AS `day`,SUM(paid_cents) AS paid_cents,COUNT(*) AS settled_stays FROM parking_stays WHERE dataset_id=? AND exited_at>=? AND exited_at<?"+condition+" GROUP BY CAST(exited_at AS DATE) ORDER BY `day`",args.toArray());
        return Map.of("source","database-synthetic","from",from.toString(),"to",to.toString(),"zone",zone==null?"all":zone,"occupancy",occupancy,"ledger",ledger,"dailyLedger",daily,"feePolicy",meta.get("feePolicy"));
    }
    private long ChronoUnitDays(LocalDate a,LocalDate b){return java.time.temporal.ChronoUnit.DAYS.between(a,b);}
    public Map<String,Object> period(ParkingAccessService.Principal actor,String period){
        LocalDate last=latestDate(),first=LocalDate.parse(String.valueOf(manifest().get("startDate")));
        LocalDate start=switch(period){case "daily"->last;case "weekly"->last.minusDays(6);case "monthly"->last.withDayOfMonth(1);case "yearly"->first;default->throw new IllegalArgumentException("经营报表周期无效");};
        return analytics(actor,start.isBefore(first)?first:start,last,null);
    }
    public List<Map<String,Object>> stays(ParkingAccessService.Principal actor,int page,int size){
        access.require(actor,"analytics");if(page<1||page>10000||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        return rows("SELECT id,zone_id,vehicle_alias,entered_at,exited_at,purpose,fee_cents,paid_cents FROM parking_stays WHERE dataset_id=? ORDER BY entered_at DESC,id DESC LIMIT ? OFFSET ?",DATASET,size,(page-1)*size);
    }
    public List<Map<String,Object>> workorders(ParkingAccessService.Principal actor){access.require(actor,"workorder");return rows("SELECT id,alert_id,title,note,status,assigned_to,created_by,created_at,updated_at,version FROM parking_workorders ORDER BY updated_at DESC LIMIT 100");}
    @Transactional
    public Map<String,Object> createWorkorder(ParkingAccessService.Principal actor,long alertId,String note,String requestKey,boolean confirmed){
        access.require(actor,"workorder");if(!confirmed||note==null||note.isBlank()||note.length()>1500||requestKey==null||!requestKey.matches("[a-zA-Z0-9-]{10,64}"))throw new IllegalArgumentException("提交工单需要明确确认及有效内容");
        var previous=rows("SELECT id,status FROM parking_workorders WHERE request_key=?",requestKey);if(!previous.isEmpty())return previous.get(0);
        var alerts=rows("SELECT id,title,status FROM parking_alerts WHERE id=? AND dataset_id=? FOR UPDATE",alertId,DATASET);if(alerts.isEmpty())throw new IllegalArgumentException("告警不存在");
        if(!"open".equals(alerts.get(0).get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"告警已关闭，请刷新后重新选择待核验告警");
        var existing=rows("SELECT id,status FROM parking_workorders WHERE alert_id=? AND status<>'closed' ORDER BY id DESC LIMIT 1",alertId);if(!existing.isEmpty())return existing.get(0);
        jdbc.update("INSERT INTO parking_workorders(alert_id,request_key,title,note,status,assigned_to,created_by,created_at,updated_at,version) VALUES(?,?,?,?,'pending_review','',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",alertId,requestKey,alerts.get(0).get("title"),note,actor.username());
        var created=rows("SELECT id,status FROM parking_workorders WHERE request_key=?",requestKey).get(0);audit(actor,"workorder.create",String.valueOf(created.get("id")),"人工确认提交待审核工单");return created;
    }
    @Transactional
    public Map<String,Object> transition(ParkingAccessService.Principal actor,long id,int version,String action,String note,boolean confirmed){
        access.require(actor,"workorder");if(!confirmed||note==null||note.isBlank()||note.length()>1500||action==null)throw new IllegalArgumentException("工单流转需要明确确认及有效备注");
        var records=rows("SELECT id,status,assigned_to FROM parking_workorders WHERE id=? FOR UPDATE",id);if(records.isEmpty())throw new IllegalArgumentException("工单不存在");
        String current=String.valueOf(records.get(0).get("status")),next;
        switch(action){
            case "approve"->{access.require(actor,"approve");if(!current.equals("pending_review"))throw new IllegalArgumentException("仅待审核工单可批准");next="approved";}
            case "assign"->{if(!current.equals("approved"))throw new IllegalArgumentException("仅已批准工单可领取");next="assigned";}
            case "resolve"->{if(!current.equals("assigned")||!actor.operator()&&!actor.username().equals(records.get(0).get("assigned_to")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅领取人或运营可完成工单");next="resolved";}
            case "close"->{access.require(actor,"approve");if(!current.equals("resolved"))throw new IllegalArgumentException("完成核验后才可关闭");next="closed";}
            default->throw new IllegalArgumentException("工单动作无效");
        }
        int changed=jdbc.update("UPDATE parking_workorders SET status=?,note=?,assigned_to=?,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND version=?",next,note,"assign".equals(action)?actor.username():records.get(0).get("assigned_to"),id,version);
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"工单已更新，请刷新后重试");
        if(next.equals("closed"))jdbc.update("UPDATE parking_alerts SET status='closed' WHERE id=(SELECT alert_id FROM parking_workorders WHERE id=?)",id);
        audit(actor,"workorder."+action,String.valueOf(id),note);return Map.of("id",id,"status",next,"version",version+1);
    }
    public void audit(ParkingAccessService.Principal actor,String action,String target,String detail){
        jdbc.update("INSERT INTO parking_audit(actor,role,action,target,detail,occurred_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",actor.username(),actor.role(),action.substring(0,Math.min(80,action.length())),target.substring(0,Math.min(100,target.length())),detail.substring(0,Math.min(1500,detail.length())));
    }
    public List<Map<String,Object>> audits(ParkingAccessService.Principal actor){access.require(actor,"audit");return actor.operator()?rows("SELECT actor,role,action,target,detail,occurred_at FROM parking_audit ORDER BY id DESC LIMIT 100"):rows("SELECT actor,role,action,target,detail,occurred_at FROM parking_audit WHERE actor=? ORDER BY id DESC LIMIT 100",actor.username());}
}
