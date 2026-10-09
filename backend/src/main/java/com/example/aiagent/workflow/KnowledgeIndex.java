package com.example.aiagent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Real cosine vector search with transparent local hashed n-gram vectors, not a semantic cloud embedding. */
@Service
public class KnowledgeIndex {
    private static final int DIMENSIONS=2048;
    private final ObjectMapper json;private final Path store;
    private List<Map<String,Object>> documents=new ArrayList<>();private Map<String,double[]> vectors=new HashMap<>();
    public KnowledgeIndex(ObjectMapper json,@Value("${app.workflow.data-dir}") String root){this.json=json;Path p=Path.of(root).toAbsolutePath().normalize();store=p.resolve("knowledge-index.json");}
    @PostConstruct synchronized void load() throws Exception {try(var in=Files.exists(store)?Files.newInputStream(store):new org.springframework.core.io.ClassPathResource("workflow-fixtures/knowledge.json").getInputStream()){documents=json.readValue(in,new TypeReference<>(){});}reindex();}
    private void reindex(){vectors.clear();for(var d:documents)vectors.put(d.get("id").toString(),embed(d.get("title")+" "+d.get("tags")+" "+d.get("content")));}
    static double[] embed(String text){double[] v=new double[DIMENSIONS];String s=text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]","");
        for(int n=1;n<=3;n++)for(int i=0;i+n<=s.length();i++){String term=s.substring(i,i+n);int h=term.hashCode();v[Math.floorMod(h,DIMENSIONS)]+=n==1?.3:1.0;}
        double norm=Math.sqrt(Arrays.stream(v).map(x->x*x).sum());if(norm>0)for(int i=0;i<v.length;i++)v[i]/=norm;return v;}
    public synchronized List<Map<String,Object>> search(String query,String kb){double[] q=embed(query);return documents.stream().filter(d->"all".equals(kb)||kb.equals(d.get("knowledgeBase"))).map(d->{double[] v=vectors.get(d.get("id"));double score=0;for(int i=0;i<q.length;i++)score+=q[i]*v[i];Map<String,Object> hit=new LinkedHashMap<>(d);hit.put("score",Math.round(score*10000)/10000.0);return hit;}).filter(d->((Number)d.get("score")).doubleValue()>=.12).sorted(Comparator.comparingDouble(d->-((Number)d.get("score")).doubleValue())).limit(3).toList();}
    public synchronized List<Map<String,Object>> documents(){return new ArrayList<>(documents);}
    public synchronized Map<String,Object> add(String title,String content,String kb)throws Exception {
        if(title==null||title.isBlank()||title.length()>100||content==null||content.isBlank()||content.length()>20000||!Set.of("support","engineering").contains(kb))throw new IllegalArgumentException("请提供标题、20000字以内正文和有效知识库");
        if(documents.size()>=100)throw new IllegalArgumentException("演示知识库上限100条");
        Map<String,Object> d=Map.of("id","KB-"+UUID.randomUUID().toString().substring(0,8),"title",title,"content",content,"knowledgeBase",kb,"tags",List.of(),"version",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());documents.add(d);reindex();Files.createDirectories(store.getParent());json.writerWithDefaultPrettyPrinter().writeValue(store.toFile(),documents);return d;
    }
}
