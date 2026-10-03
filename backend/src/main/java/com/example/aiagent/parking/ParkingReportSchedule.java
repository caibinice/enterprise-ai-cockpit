package com.example.aiagent.parking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** One existing cockpit process, cheap DB aggregates at off-peak time, no LLM calls. */
@Service
@ConditionalOnProperty(prefix="app.parking",name="enabled",havingValue="true")
public class ParkingReportSchedule {
    private static final Logger log=LoggerFactory.getLogger(ParkingReportSchedule.class);
    private final JdbcTemplate jdbc;private final ParkingDataService data;private final ObjectMapper json;
    public ParkingReportSchedule(JdbcTemplate jdbc,ParkingDataService data,ObjectMapper json){this.jdbc=jdbc;this.data=data;this.json=json;}
    @Scheduled(cron="0 15 2 * * *",zone="Asia/Shanghai")
    public void nightly(){try{refresh();}catch(Exception e){log.error("Parking report aggregation failed",e);}}
    public synchronized Map<String,Object> refresh()throws Exception{
        List<String> periods=List.of("daily","weekly","monthly","yearly");
        for(String period:periods){String content=json.writeValueAsString(data.period(new ParkingAccessService.Principal("report-scheduler","operator"),period));
            int updated=jdbc.update("UPDATE parking_report_cache SET content_json=?,generated_at=CURRENT_TIMESTAMP WHERE period=?",content,period);
            if(updated==0)jdbc.update("INSERT INTO parking_report_cache(period,content_json,generated_at) VALUES(?,?,CURRENT_TIMESTAMP)",period,content);
        }
        return Map.of("periods",periods,"dataThrough",data.latestDate().toString(),"schedule","02:15 Asia/Shanghai");
    }
    public List<Map<String,Object>> status(){return jdbc.query("SELECT period,generated_at FROM parking_report_cache ORDER BY period",(rs,n)->Map.of("period",rs.getString(1),"generatedAt",rs.getTimestamp(2).toLocalDateTime().toString()));}
}
