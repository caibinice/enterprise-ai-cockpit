package com.example.aiagent.parking;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** One canonical anonymized dataset supplies both the knowledge base and direct FAQs. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingHospitalService {
    private final JsonNode guide;
    public record Answer(String text,String title){}
    public ParkingHospitalService(ObjectMapper json) {
        try { guide=json.readTree(new ClassPathResource("parking/hospital-guide.json").getContentAsByteArray()); }
        catch(Exception error) { throw new IllegalStateException("Invalid anonymized hospital guide",error); }
    }
    public JsonNode documents(){return guide.path("documents");}
    public JsonNode department(String question){
        // Match the longest name/alias first (普外科 before 外科, 急诊挂号 before generic 挂号).
        JsonNode best=null;int length=0;
        for(var dept:guide.path("departments")){
            var names=new ArrayList<String>();names.add(dept.path("name").asText());dept.path("aliases").forEach(a->names.add(a.asText()));
            for(String name:names)if(question.contains(name)&&name.length()>length){best=dept;length=name.length();}
        }
        return best;
    }
    public Answer answer(String question){
        JsonNode dept=department(question);
        if(dept!=null){
            String name=dept.path("name").asText();
            if(contains(question,"病房","病区","床位","住院","探视","护士站")){
                for(var ward:guide.path("wards"))for(var d:ward.path("departments"))if(name.equals(d.asText()))
                    return new Answer(name+"对应"+ward.path("area").asText()+"，位于"+ward.path("floor").asText()+"，病房"+ward.path("rooms").asText()+"；"+ward.path("types").asText()+"。\n以上楼层与病房是脱敏虚构示范；实时空床、患者房间和探视批准信息未接入。可导航到住院楼入口。",title("wards"));
            }
            if(!contains(question,"停车","泊位","导航","路线","带我","怎么走","定位")||contains(question,"挂号","科室","医生","医师","排班","几楼","哪层","位置")){
                String text=name+" · "+dept.path("floor").asText()+"（示范位置）。";
                if(contains(question,"挂号","预约","排班","医生","医师","出诊","今天","明天","号源"))
                    text+="\n"+dept.path("doctor").asText()+"："+dept.path("sessions").asText()+"。先选择科室和时段，再核对就诊人并报到；当前余号、停诊与真实预约尚未接入。";
                text+="\n科室、楼层与医师代号用于脱敏演示；可将三维镜头定位到"+(name.equals("急诊科")?"急诊楼":"门诊楼")+"。";
                return new Answer(text,title(contains(question,"排班","医生","医师","出诊","号源")?"schedule":"departments"));
            }
        }
        // More specific processes precede generic words such as 挂号 or 住院.
        for(String id:List.of("cancellation","admission","wards","schedule","registration","emergency-access","outpatient","departments")){
            JsonNode doc=document(id);
            for(var keyword:doc.path("keywords"))if(question.contains(keyword.asText())){
                String text=doc.path("answer").asText();
                if(id.equals("departments")&&contains(question,"科室","有哪些"))text+="\n科室目录："+departmentNames()+"。";
                return new Answer(text,doc.path("title").asText());
            }
        }
        return null;
    }
    public String destination(String question){
        if(contains(question,"病房","病区","住院","入院","探视","出院","护士站","床位"))return "inpatient";
        JsonNode dept=department(question);
        if(dept!=null)return dept.path("name").asText().equals("急诊科")?"emergency":"outpatient";
        if(contains(question,"挂号","门诊","科室","导医","取药"))return "outpatient";
        return null;
    }
    private String departmentNames(){var names=new ArrayList<String>();guide.path("departments").forEach(d->names.add(d.path("name").asText()));return String.join("、",names);}
    private JsonNode document(String id){for(var doc:documents())if(id.equals(doc.path("id").asText()))return doc;throw new IllegalArgumentException("Unknown guide section");}
    private String title(String id){return document(id).path("title").asText();}
    static boolean contains(String text,String...words){for(String word:words)if(text.contains(word))return true;return false;}
}
