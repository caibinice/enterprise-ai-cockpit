package com.example.aiagent.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicitly owns the primary MySQL datasource so the vector datasource can never be injected accidentally. */
@Configuration
public class ApplicationDataSourceConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties applicationDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    DataSource dataSource() {
        return applicationDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
