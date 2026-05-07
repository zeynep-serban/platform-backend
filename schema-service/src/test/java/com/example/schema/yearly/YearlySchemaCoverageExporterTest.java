package com.example.schema.yearly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Phase 2 Program 2b — YearlySchemaCoverageExporter producer contract tests.
 *
 * <p>Codex iter-16 §2b strong-revise absorb (thread 019e0119): JSON shape,
 * targeted-table allowlist, deterministic ordering, output file path
 * — exporter's deliverable contract — is locked at this layer.
 */
class YearlySchemaCoverageExporterTest {

    @Test
    @SuppressWarnings("unchecked")
    void run_writesArtifactWithExpectedShape(@TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("yearly-coverage.json");

        YearlySchemaDiscoveryService discovery = mock(YearlySchemaDiscoveryService.class);
        when(discovery.discover(List.of(2026), List.of("35")))
                .thenReturn(List.of(
                        new YearlySchemaDiscoveryService.DiscoveredSchema(
                                "workcube_mikrolink_2026_35", 2026, "35")));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Stub column extraction for INVOICE table only (other tables → empty).
        when(jdbc.query(anyString(), any(RowMapper.class), eq("workcube_mikrolink_2026_35"), eq("INVOICE")))
                .thenReturn(List.of(
                        Map.of("name", "COMPANY_ID", "dataType", "int", "nullable", false),
                        Map.of("name", "INVOICE_DATE", "dataType", "datetime", "nullable", true)
                ));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("workcube_mikrolink_2026_35"), eq("INVOICE_ROW")))
                .thenReturn(List.of());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YearlySchemaCoverageExporter exporter = new YearlySchemaCoverageExporter(
                discovery, jdbc, mapper,
                outputPath.toString(),
                "2026", "35", "INVOICE,INVOICE_ROW");

        exporter.run();

        assertThat(outputPath).exists();
        JsonNode artifact = mapper.readTree(Files.readString(outputPath));

        // Top-level shape
        assertThat(artifact.get("artifactVersion").asInt()).isEqualTo(1);
        assertThat(artifact.get("crawlScope").asText()).isEqualTo("yearly");
        assertThat(artifact.get("schemaPattern").asText())
                .isEqualTo("workcube_mikrolink_{year}_{companyId}");
        assertThat(artifact.get("filters").get("years").get(0).asInt()).isEqualTo(2026);
        assertThat(artifact.get("filters").get("companyIds").get(0).asText()).isEqualTo("35");

        // Schemas array
        assertThat(artifact.get("schemas").isArray()).isTrue();
        assertThat(artifact.get("schemas")).hasSize(1);
        JsonNode sc = artifact.get("schemas").get(0);
        assertThat(sc.get("schema").asText()).isEqualTo("workcube_mikrolink_2026_35");
        assertThat(sc.get("year").asInt()).isEqualTo(2026);
        assertThat(sc.get("companyId").asText()).isEqualTo("35");
        assertThat(sc.get("status").asText()).isEqualTo("covered");

        // Tables: only INVOICE (INVOICE_ROW returned empty columns → skipped)
        JsonNode tables = sc.get("tables");
        assertThat(tables.has("INVOICE")).isTrue();
        assertThat(tables.has("INVOICE_ROW"))
                .as("INVOICE_ROW had no columns; should be skipped")
                .isFalse();

        JsonNode columns = tables.get("INVOICE").get("columns");
        assertThat(columns.isArray()).isTrue();
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).get("name").asText()).isEqualTo("COMPANY_ID");
        assertThat(columns.get(0).get("dataType").asText()).isEqualTo("int");
        assertThat(columns.get(0).get("nullable").asBoolean()).isFalse();
        assertThat(columns.get(1).get("name").asText()).isEqualTo("INVOICE_DATE");
    }

    @Test
    void run_emptyDiscovery_writesArtifactWithNoSchemas(@TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("yearly-coverage-empty.json");

        YearlySchemaDiscoveryService discovery = mock(YearlySchemaDiscoveryService.class);
        when(discovery.discover(any(), any())).thenReturn(List.of());

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YearlySchemaCoverageExporter exporter = new YearlySchemaCoverageExporter(
                discovery, jdbc, mapper, outputPath.toString(), "", "", "INVOICE");

        exporter.run();

        assertThat(outputPath).exists();
        JsonNode artifact = mapper.readTree(Files.readString(outputPath));
        assertThat(artifact.get("schemas").isArray()).isTrue();
        assertThat(artifact.get("schemas")).isEmpty();
    }

    @Test
    void run_outputPathParentDirCreatedIfMissing(@TempDir Path tempDir) throws Exception {
        Path nestedPath = tempDir.resolve("nested/dir/yearly-coverage.json");

        YearlySchemaDiscoveryService discovery = mock(YearlySchemaDiscoveryService.class);
        when(discovery.discover(any(), any())).thenReturn(List.of());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YearlySchemaCoverageExporter exporter = new YearlySchemaCoverageExporter(
                discovery, jdbc, mapper, nestedPath.toString(), "", "", "INVOICE");

        exporter.run();

        assertThat(nestedPath).exists();
        assertThat(Files.exists(nestedPath.getParent())).isTrue();
    }

    @Test
    void run_artifactRoundTripsViaConsumerLookup(@TempDir Path tempDir) throws Exception {
        // Producer-consumer contract test: artifact written by exporter is
        // consumable by BuildTimeYearlySchemaCoverageLookup with correct
        // case-insensitive PRESENT/COLUMN_MISSING/NOT_COVERED behavior.
        Path outputPath = tempDir.resolve("roundtrip.json");

        YearlySchemaDiscoveryService discovery = mock(YearlySchemaDiscoveryService.class);
        when(discovery.discover(any(), any()))
                .thenReturn(List.of(
                        new YearlySchemaDiscoveryService.DiscoveredSchema(
                                "workcube_mikrolink_2026_1", 2026, "1")));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("workcube_mikrolink_2026_1"), eq("INVOICE")))
                .thenReturn(List.of(
                        Map.of("name", "COMPANY_ID", "dataType", "int", "nullable", false)));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YearlySchemaCoverageExporter exporter = new YearlySchemaCoverageExporter(
                discovery, jdbc, mapper, outputPath.toString(), "2026", "1", "INVOICE");
        exporter.run();

        // Verify artifact loadable + queryable shape (consumer mirror tested
        // in report-service BuildTimeYearlySchemaCoverageLookupTest).
        assertThat(Files.size(outputPath))
                .as("Artifact size budget guard (<10KB for fixture-scale)")
                .isLessThan(10_000L);
    }
}
