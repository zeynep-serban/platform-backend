package com.example.schema.yearly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 2 Program 2b — YearlySchemaDiscoveryService tests.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): regex normalization
 * + deterministic order + filters.
 */
class YearlySchemaDiscoveryServiceTest {

    @Test
    void discover_validYearlyPattern_yieldsParsedDiscoveredSchemas() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'", String.class))
                .thenReturn(List.of(
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_35",
                        "workcube_mikrolink_2024_1"));

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(jdbc);
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(3);
        // Deterministic ordering: year asc, then companyId asc
        assertThat(result).extracting("name").containsExactly(
                "workcube_mikrolink_2024_1",
                "workcube_mikrolink_2025_1",
                "workcube_mikrolink_2026_35");
    }

    @Test
    void discover_malformedSchemaNames_skippedSilently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'", String.class))
                .thenReturn(List.of(
                        "workcube_mikrolink",                        // canonical, no year
                        "workcube_mikrolink_1",                       // tenant only, no year
                        "workcube_mikrolink_2025_1",                  // valid
                        "workcube_mikrolink_yyyy_1",                  // non-numeric year
                        "workcube_mikrolink_2025",                    // missing companyId
                        "workcube_mikrolink_2025_abc",                // non-numeric companyId
                        "workcube_mikrolinkV2_2025_1"));              // wrong prefix

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(jdbc);
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("workcube_mikrolink_2025_1");
        assertThat(result.get(0).year()).isEqualTo(2025);
        assertThat(result.get(0).companyId()).isEqualTo("1");
    }

    @Test
    void discover_yearFilter_appliedAsWhitelist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'", String.class))
                .thenReturn(List.of(
                        "workcube_mikrolink_2024_1",
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_1"));

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(jdbc);
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(2025, 2026), List.of());

        assertThat(result).hasSize(2);
        assertThat(result).extracting("year").containsExactly(2025, 2026);
    }

    @Test
    void discover_companyFilter_appliedAsWhitelist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'", String.class))
                .thenReturn(List.of(
                        "workcube_mikrolink_2026_1",
                        "workcube_mikrolink_2026_35",
                        "workcube_mikrolink_2026_99"));

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(jdbc);
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of("1", "35"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting("companyId").containsExactly("1", "35");
    }

    @Test
    void discover_emptyDb_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'", String.class))
                .thenReturn(List.of());

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(jdbc);
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).isEmpty();
    }
}
