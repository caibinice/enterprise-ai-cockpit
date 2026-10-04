package com.example.aiagent.parking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Stable POI ids and a versioned, validated graph. Distances are labelled model estimates. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingNavigationService {
    public record Node(String id,String label,String kind,String zone,double x,double z,boolean accessible,boolean charging,boolean closed){}
    public record Edge(String from,String to,boolean closed){}
    public record Graph(int version,List<Node> nodes,List<Edge> edges){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final ParkingDataService data;private final ParkingAccessService access;
    public ParkingNavigationService(JdbcTemplate jdbc,ObjectMapper json,ParkingDataService data,ParkingAccessService access){this.jdbc=jdbc;this.json=json;this.data=data;this.access=access;}
    public Graph graph(){
        var found=jdbc.queryForList("SELECT version,content_json FROM parking_configuration WHERE id='campus-graph'");
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"园区路径图尚未配置");
        try{Graph g=json.readValue(String.valueOf(found.get(0).get("content_json")),Graph.class);return new Graph(((Number)found.get(0).get("version")).intValue(),g.nodes(),g.edges());}catch(Exception e){throw new IllegalStateException("Invalid stored parking graph",e);}
    }
    public void initialize(ParkingAccessService.Principal actor){
        access.require(actor,"admin");if(jdbc.queryForObject("SELECT COUNT(*) FROM parking_configuration WHERE id='campus-graph'",Integer.class)>0)return;
        Graph graph=defaults();try{jdbc.update("INSERT INTO parking_configuration(id,version,content_json,updated_by,updated_at) VALUES('campus-graph',?,?,?,CURRENT_TIMESTAMP)",graph.version(),json.writeValueAsString(graph),actor.username());}catch(Exception e){throw new IllegalStateException("Parking graph initialization failed",e);}
    }
    public Graph save(ParkingAccessService.Principal actor,Graph proposed){
        access.require(actor,"admin");validate(proposed);try{
            Graph next=new Graph(proposed.version()+1,proposed.nodes(),proposed.edges());
            int changed=jdbc.update("UPDATE parking_configuration SET version=?,content_json=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id='campus-graph' AND version=?",next.version(),json.writeValueAsString(next),actor.username(),proposed.version());
            if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"路径图已被更新，请刷新");data.audit(actor,"graph.update","campus-graph","version="+next.version());return next;
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalArgumentException("路径图保存失败",e);}
    }
    static void validate(Graph graph){
        if(graph==null||graph.nodes()==null||graph.edges()==null||graph.nodes().size()<3||graph.nodes().size()>40||graph.edges().size()>80)throw new IllegalArgumentException("路径图规模无效");
        Set<String> ids=new HashSet<>();for(Node n:graph.nodes())if(n==null||n.id()==null||!n.id().matches("[a-z][a-z0-9-]{1,39}")||!ids.add(n.id())||n.label()==null||n.label().isBlank()||n.label().length()>80||n.kind()==null||!Set.of("destination","parking","junction","entrance","service").contains(n.kind())||n.zone()!=null&&!ParkingDataService.NAMES.containsKey(n.zone())||!Double.isFinite(n.x())||!Double.isFinite(n.z())||Math.abs(n.x())>40||Math.abs(n.z())>40)throw new IllegalArgumentException("POI标识或坐标无效");
        for(Edge e:graph.edges())if(e==null||!ids.contains(e.from())||!ids.contains(e.to())||e.from().equals(e.to()))throw new IllegalArgumentException("道路端点无效");
        if(!ids.contains("entrance"))throw new IllegalArgumentException("路径图需要保留入口节点");
    }
    public Map<String,Object> route(String from,String to){return route(graph(),from,to);}
    static Map<String,Object> route(Graph graph,String from,String to){
        Map<String,Node> nodes=new HashMap<>();graph.nodes().forEach(n->nodes.put(n.id(),n));
        if(!nodes.containsKey(from)||!nodes.containsKey(to)||nodes.get(from).closed()||nodes.get(to).closed())throw new IllegalArgumentException("目的地不存在或暂时关闭");
        Map<String,Double> distance=new HashMap<>();Map<String,String> previous=new HashMap<>();Set<String> done=new HashSet<>();distance.put(from,0.0);
        while(done.size()<nodes.size()){
            String u=distance.keySet().stream().filter(n->!done.contains(n)).min(Comparator.comparingDouble(distance::get)).orElse(null);
            if(u==null||u.equals(to))break;done.add(u);
            for(Edge e:graph.edges()){
                String v=e.from().equals(u)?e.to():e.to().equals(u)?e.from():null;
                if(v==null||e.closed()||nodes.get(v).closed()||done.contains(v))continue;
                Node a=nodes.get(u),b=nodes.get(v);double d=distance.get(u)+Math.hypot(a.x()-b.x(),a.z()-b.z())*20;
                if(d<distance.getOrDefault(v,Double.POSITIVE_INFINITY)){distance.put(v,d);previous.put(v,u);}
            }
        }
        if(!distance.containsKey(to))throw new ResponseStatusException(HttpStatus.CONFLICT,"当前道路图没有可通行路径");
        LinkedList<Node> path=new LinkedList<>();for(String u=to;u!=null;u=previous.get(u))path.addFirst(nodes.get(u));
        return Map.of("from",from,"to",to,"label",nodes.get(to).label(),"meters",Math.round(distance.get(to)),"distanceSource","model-estimate-20m-per-unit","version",graph.version(),"points",path.stream().map(n->List.of(n.x(),n.z())).toList(),"steps",path.stream().map(Node::label).toList());
    }
    public Map<String,Object> recommend(ParkingAccessService.Principal actor,String destination,String preference){
        if(!Set.of("standard","accessible","charging","emergency").contains(preference))throw new IllegalArgumentException("泊位偏好无效");
        Graph g=graph();Node target=g.nodes().stream().filter(n->n.id().equals(destination)&&!n.closed()).findFirst().orElseThrow(()->new IllegalArgumentException("请选择图中的目的地"));
        var snap=data.snapshot(actor);var zones=(List<Map<String,Object>>)snap.get("zones");List<Map<String,Object>> candidates=new ArrayList<>();
        for(Node p:g.nodes()){
            if(!p.kind().equals("parking")||p.closed()||p.zone()==null||preference.equals("accessible")&&!p.accessible()||preference.equals("charging")&&!p.charging()||preference.equals("emergency")&&!p.zone().equals("C"))continue;
            var zone=zones.stream().filter(z->p.zone().equals(z.get("id"))).findFirst().orElseThrow();int free=((Number)zone.get("capacity")).intValue()-((Number)zone.get("occupied")).intValue();
            int reserve=p.zone().equals("C")&&!preference.equals("emergency")?10:0;if(free<=reserve)continue;
            try{var walk=route(g,p.id(),target.id());double meters=((Number)walk.get("meters")).doubleValue();double score=meters+150.0/(free-reserve)+(p.zone().equals("C")&&!preference.equals("emergency")?150:0);
                candidates.add(Map.of("id",p.id(),"zone",p.zone(),"label",p.label(),"free",free,"reserved",reserve,"meters",Math.round(meters),"score",Math.round(score),"reason",preference.equals("emergency")?"急诊需求优先C区；核对可用空位和可达道路":"综合步行距离、可用空位、急诊模拟预留及偏好条件","route",walk));
            }catch(ResponseStatusException ignored){ /* A disconnected car park is not a recommendation. */ }
        }
        candidates.sort(Comparator.comparingLong(c->((Number)c.get("score")).longValue()));
        return Map.of("source","database-synthetic","destination",destination,"preference",preference,"sampledAt",snap.get("sampledAt"),"candidates",candidates,"policy","演示规则：C区为普通需求预留10个泊位；特殊泊位为分区能力，未接入单泊位传感器。");
    }
    public Map<String,List<String>> tours(){return Map.of("visitor",List.of("entrance","outpatient","parking-a","inpatient","exit"),"operations",List.of("entrance","parking-a","charging","parking-b","emergency","parking-c","parking-c-side"),"night",List.of("security","entrance","parking-b","parking-c","parking-c-side","exit"));}
    static Graph defaults(){
        try {
            Graph graph=new ObjectMapper().readValue(new ClassPathResource("parking/campus-layout.json").getContentAsByteArray(),Graph.class);
            validate(graph);return graph;
        } catch(Exception e){throw new IllegalStateException("Invalid bundled campus layout",e);}
    }
}
