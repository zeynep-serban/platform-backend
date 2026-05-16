package com.example.schema.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
public class MssqlConfig {

    /**
     * MSSQL system-catalog reads (notably {@code SchemaExtractService.extractTables})
     * run wide JOINs over {@code sys.*}. For large schemas — {@code workcube_mikrolink}
     * has 1509 tables / 26240 columns — the previously hard-coded 60s JDBC
     * {@code queryTimeout} was too short: the read timed out, 500'd, and collapsed
     * the whole snapshot. The timeout is now property-driven
     * ({@code schema.mssql.query-timeout-seconds}, env
     * {@code SCHEMA_MSSQL_QUERY_TIMEOUT_SECONDS}, default 60) so each environment
     * can size it to its schema.
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(
            DataSource dataSource,
            @Value("${schema.mssql.query-timeout-seconds:60}") int queryTimeoutSeconds) {
        var template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(queryTimeoutSeconds);
        return template;
    }
}
