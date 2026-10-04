package com.example.aiagent.parking;

import com.example.aiagent.model.KnowledgeBaseRequest;
import com.example.aiagent.model.KnowledgeBaseResponse;
import com.example.aiagent.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** V2 is isolated: never overwrites existing v1 or manually edited enterprise documents. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingKnowledgeService {
    public static final String CODE="smart-parking-agent-v2";
    private final KnowledgeBaseService knowledge;private final ObjectMapper json;
    public ParkingKnowledgeService(KnowledgeBaseService knowledge,ObjectMapper json){this.knowledge=knowledge;this.json=json;}
    public synchronized Map<String,Object> bootstrap()throws Exception{
        long id=knowledge.list().stream().filter(k->CODE.equals(k.code())).map(KnowledgeBaseResponse::id).findFirst().orElseGet(()->knowledge.createKnowledgeBase(new KnowledgeBaseRequest("停车业务与智能体知识库（二期）","合成年度账本、角色权限、路径、工单、经营报表及语音视觉；不含真实医院政策。",CODE,"智慧停车")));
        Set<String> titles=new HashSet<>();knowledge.listDocuments(id).forEach(d->titles.add(d.title()));int imported=0;
        try(var input=new ClassPathResource("parking/knowledge-v2.json").getInputStream()){
            for(var document:json.readTree(input)){String title=document.path("title").asText();if(titles.add(title)){knowledge.importDocument(id,title,document.path("content").asText(),Map.of("domain","smart-parking","source",CODE,"sourceType","business-guide","status","active","version","2"));imported++;}}
        }
        for(var document:new ParkingHospitalService(json).documents()){
            String title=document.path("title").asText();
            if(titles.add(title)){knowledge.importDocument(id,title,document.path("content").asText(),Map.of("domain","smart-parking","topic","hospital","source",CODE,"sourceType","anonymized-demo","status","active","version","2026-10-04"));imported++;}
        }
        return Map.of("knowledgeBaseId",id,"imported",imported,"documents",knowledge.listDocuments(id).size());
    }
}
