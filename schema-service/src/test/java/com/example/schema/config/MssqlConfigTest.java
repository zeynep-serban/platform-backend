package com.example.schema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * P0 (handoff §5 — Codex 019e32da): the MSSQL JDBC query timeout used to be a
 * hard-coded 60s in {@link MssqlConfig}. {@code SchemaExtractService.extractTables}
 * runs a wide {@code sys.*} JOIN that exceeds 60s against {@code workcube_mikrolink}
 * (1509 tables / 26240 columns), so the read times out and the whole snapshot
 * 500s. These tests pin the timeout as property-driven: both the
 * {@code schema.mssql.query-timeout-seconds} default and an explicit override
 * must reach {@code JdbcTemplate.setQueryTimeout} — proving a mis-named property
 * (silent fallback to a wrong value) is caught.
 */
class MssqlConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MssqlConfig.class)
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    void queryTimeout_defaultsTo60_whenPropertyAbsent() {
        runner.run(ctx -> assertThat(ctx.getBean(NamedParameterJdbcTemplate.class)
                .getJdbcTemplate().getQueryTimeout()).isEqualTo(60));
    }

    @Test
    void queryTimeout_honoursPropertyOverride() {
        runner.withPropertyValues("schema.mssql.query-timeout-seconds=240")
                .run(ctx -> assertThat(ctx.getBean(NamedParameterJdbcTemplate.class)
                        .getJdbcTemplate().getQueryTimeout()).isEqualTo(240));
    }
}
