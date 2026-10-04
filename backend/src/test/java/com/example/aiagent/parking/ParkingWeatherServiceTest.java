package com.example.aiagent.parking;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ParkingWeatherServiceTest {
    final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.fixed(Instant.parse("2026-10-04T12:50:00Z"),ZoneId.of("Asia/Shanghai"));
    String payload(String observed){return "{\"city\":\"常州\",\"timezone\":\"Asia/Shanghai\",\"source\":\"Open-Meteo\",\"condition\":\"小雨\",\"temperatureC\":19.5,\"apparentTemperatureC\":18,\"humidityPercent\":80,\"windSpeedKmh\":9,\"observedAt\":\""+observed+"\"}";}
    @Test void todayWeatherUsesMcpDataAndCacheNotKnowledge(){
        var calls=new AtomicInteger();var weather=new ParkingWeatherService(()->{calls.incrementAndGet();return payload("2026-10-04T20:45");},json,clock);
        assertThat(weather.answer("今天医院天气怎么样")).contains("常州","19.5℃","2026-10-04 20:45","Open-Meteo","雨具");
        weather.answer("今天需要带伞吗");assertThat(calls).hasValue(1);
        assertThat(ParkingWeatherService.isQuery("挂号需要什么材料")).isFalse();assertThat(ParkingWeatherService.isQuery("医院下雨吗")).isTrue();
    }
    @Test void previousDayOrInvalidWeatherIsNeverPresentedAsToday(){
        var old=new ParkingWeatherService(()->payload("2026-10-03T20:45"),json,clock);
        assertThat(old.answer("天气")).contains("未返回有效的今日结果").doesNotContain("19.5℃");
        var invalid=new ParkingWeatherService(()->payload("2026-10-04T20:45").replace("19.5","999"),json,clock);
        assertThat(invalid.answer("天气")).contains("未返回有效").doesNotContain("999");
    }
    @Test void failedMcpReturnsExplicitUnavailableInsteadOfThrowingNetworkError(){
        var failed=new ParkingWeatherService(()->{throw new IllegalStateException("network error");},json,clock);
        assertThat(failed.answer("天气")).contains("天气服务未返回").doesNotContain("network error");
    }
}
