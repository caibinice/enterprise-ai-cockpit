package com.example.aiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:cockpit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.ai.vectorstore.pgvector.enabled=false",
    "spring.ai.mcp.client.enabled=false",
    "app.mcp.enabled=true"
})
class ApplicationContextTest {
    @Test
    void contextLoads() {
    }
}
