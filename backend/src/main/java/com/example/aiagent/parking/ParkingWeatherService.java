package com.example.aiagent.parking;

import com.example.aiagent.service.McpToolService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Reuses the cockpit MCP process. Today is always Asia/Shanghai; never use KB weather. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingWeatherService {
    private static final Logger log=LoggerFactory.getLogger(ParkingWeatherService.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final Supplier<String> query;private final ObjectMapper json;private final Clock clock;
    private final Semaphore inFlight=new Semaphore(1);
    private volatile JsonNode cached;private volatile Instant savedAt=Instant.EPOCH;
    @Autowired
    public ParkingWeatherService(ObjectProvider<McpToolService> provider,ObjectMapper json){
        this(()->{var service=provider.getIfAvailable();if(service==null)throw new IllegalStateException("weather MCP not configured");return service.queryWeather("常州");},json,Clock.system(ZONE));
    }
    ParkingWeatherService(Supplier<String> query,ObjectMapper json,Clock clock){this.query=query;this.json=json;this.clock=clock;}
    public static boolean isQuery(String question){return ParkingHospitalService.contains(question.toLowerCase(),"天气","气温","下雨","下雪","带伞","晴天","冷不冷","热不热","weather","temperature");}
    public String answer(String question){
        JsonNode value=cached;Instant now=clock.instant();boolean fallback=false;
        if(value==null||Duration.between(savedAt,now).toSeconds()>120){
            if(!inFlight.tryAcquire())return recent(value,now)?format(value,true,question):unavailable();
            try{
                String output=Mono.fromCallable(query::get).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(18)).block(Duration.ofSeconds(19));
                JsonNode candidate=json.readTree(output);validate(candidate,now);
                cached=value=candidate;savedAt=clock.instant();
            }catch(Exception error){
                log.warn("Parking Changzhou weather query failed: {}",error.getClass().getSimpleName());
                if(!recent(value,now))return unavailable();fallback=true;
            }finally{inFlight.release();}
        }
        if(!recent(value,now))return unavailable();
        return format(value,fallback||value.path("stale").asBoolean(false),question);
    }
    private void validate(JsonNode value,Instant now){
        if(value==null||!"常州".equals(value.path("city").asText())||!"Open-Meteo".equals(value.path("source").asText())||!"Asia/Shanghai".equals(value.path("timezone").asText())||!recent(value,now))throw new IllegalArgumentException("weather identity/time invalid");
        bounded(value,"temperatureC",-80,60,true);bounded(value,"apparentTemperatureC",-100,80,false);bounded(value,"humidityPercent",0,100,false);bounded(value,"windSpeedKmh",0,300,false);
        if(value.path("condition").asText().isBlank()||value.path("condition").asText().length()>30)throw new IllegalArgumentException("weather condition invalid");
    }
    static void bounded(JsonNode value,String field,double low,double high,boolean required){
        JsonNode n=value.path(field);if(!required&&(n.isMissingNode()||n.isNull()))return;
        if(!n.isNumber()||!Double.isFinite(n.asDouble())||n.asDouble()<low||n.asDouble()>high)throw new IllegalArgumentException("weather number invalid");
    }
    private boolean recent(JsonNode value,Instant now){
        try{
            if(value==null)return false;LocalDateTime observed=LocalDateTime.parse(value.path("observedAt").asText());
            long age=Duration.between(observed.atZone(ZONE).toInstant(),now).toMinutes();
            return observed.toLocalDate().equals(LocalDate.now(clock.withZone(ZONE)))&&age>=-15&&age<=45;
        }catch(Exception ignored){return false;}
    }
    private String format(JsonNode value,boolean stale,String question){
        String observed=LocalDateTime.parse(value.path("observedAt").asText()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String text="医院天气按常州查询。"+observed+"（北京时间）"+value.path("condition").asText()+"，气温 "+value.path("temperatureC").asText()+"℃";
        if(value.path("apparentTemperatureC").isNumber())text+="，体感 "+value.path("apparentTemperatureC").asText()+"℃";
        if(value.path("humidityPercent").isNumber())text+="，湿度 "+value.path("humidityPercent").asText()+"%";
        if(value.path("windSpeedKmh").isNumber())text+="，风速 "+value.path("windSpeedKmh").asText()+" km/h";
        text+="。\n来源：Open-Meteo，经座舱 MCP 查询（天气模型数据，非院内传感器）。";
        if(stale)text+="当前使用最近一次结果，更新时间如上，并非刚刚采样。";
        if(ParkingHospitalService.contains(question,"明天","后天","下周"))text+="当前接入的是今日当前天气，未来日期预报尚未接入。";
        if(ParkingHospitalService.contains(value.path("condition").asText(),"雨","雪","雷暴"))text+="\n到院可备雨具，步行时注意地面湿滑。";
        return text;
    }
    private String unavailable(){return "今天的医院天气按常州查询，但本次天气服务未返回有效的今日结果。请稍后再次询问“常州今天天气”；我没有使用知识库中的历史天气或编造温度。";}
}
