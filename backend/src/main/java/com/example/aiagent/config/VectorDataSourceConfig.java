package com.example.aiagent.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds the vector database connection separately from the MySQL application database. */
@Configuration
@ConditionalOnProperty(prefix = "app.vector", name = "enabled", havingValue = "true")
public class VectorDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.vector")
    DataSourceProperties vectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "vectorDataSource")
    @ConfigurationProperties("app.vector.hikari")
    DataSource vectorDataSource() {
        return vectorDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean(name = "vectorJdbcTemplate")
    JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

}
