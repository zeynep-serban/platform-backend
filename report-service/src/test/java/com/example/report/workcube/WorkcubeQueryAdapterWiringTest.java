package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.report.query.SqlBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Phase 2 Program 11.2b-1 — adapter bean wiring proof (Codex iter-23
 * REVISE-1 blocker 2).
 *
 * <p>Verifies that {@link WorkcubeQueryAdapter} registers in production
 * (MSSQL beans present) and skips when {@code workcubeMssqlDataSource}
 * is absent (typical slice / PG-only test context).
 */
class WorkcubeQueryAdapterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void adapter_registers_whenWorkcubeMssqlBeansPresent() {
        runner.withUserConfiguration(MssqlPresentConfig.class)
                .run(context -> assertThat(context).hasSingleBean(WorkcubeQueryAdapter.class));
    }

    @Test
    void adapter_absent_whenWorkcubeMssqlDataSourceAbsent() {
        runner.withUserConfiguration(MssqlAbsentConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(WorkcubeQueryAdapter.class));
    }

    @Configuration
    static class MssqlPresentConfig {
        @Bean
        DataSource workcubeMssqlDataSource() {
            return mock(DataSource.class);
        }

        @Bean
        NamedParameterJdbcTemplate workcubeMssqlJdbc() {
            return mock(NamedParameterJdbcTemplate.class);
        }

        @Bean
        SqlBuilder sqlBuilder() {
            return new SqlBuilder();
        }

        @Bean
        CompositeTenantBoundaryEnforcer compositeTenantBoundaryEnforcer() {
            return new CompositeTenantBoundaryEnforcer();
        }

        @Bean
        WorkcubeQueryAdapter workcubeQueryAdapter(SqlBuilder sqlBuilder,
                                                  NamedParameterJdbcTemplate workcubeMssqlJdbc,
                                                  CompositeTenantBoundaryEnforcer compositeEnforcer) {
            return new WorkcubeQueryAdapter(sqlBuilder, workcubeMssqlJdbc, compositeEnforcer);
        }
    }

    @Configuration
    static class MssqlAbsentConfig {
        // intentionally no workcubeMssqlDataSource — adapter must skip
        @Bean
        SqlBuilder sqlBuilder() {
            return new SqlBuilder();
        }
    }
}
