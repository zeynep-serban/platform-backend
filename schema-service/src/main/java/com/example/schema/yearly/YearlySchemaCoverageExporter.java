package com.example.schema.yearly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 2b — Yearly schema coverage artifact exporter.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): build-time CLI
 * runner. Discovers yearly partitions via
 * {@link YearlySchemaDiscoveryService}, extracts targeted-table coverage
 * (configurable allowlist), writes a deterministic JSON artifact.
 *
 * <p>Activated only with profile {@code snapshot-builder}; not loaded in
 * normal startup. CI/build pipeline runs it explicitly:
 * <pre>{@code
 * mvn spring-boot:run -Dspring-boot.run.profiles=snapshot-builder \
 *     -Dsnapshot.yearly.output-path=workcube-schema-yearly-coverage.json
 * }</pre>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code snapshot.yearly.output-path}: artifact destination
 *       (default: {@code workcube-schema-yearly-coverage.json})</li>
 *   <li>{@code snapshot.yearly.years}: comma-separated years (empty → all)</li>
 *   <li>{@code snapshot.yearly.company-ids}: comma-separated companyIds (empty → all)</li>
 *   <li>{@code snapshot.yearly.tables}: comma-separated targeted tables
 *       (default: tenant fact tables — INVOICE, INVOICE_ROW, CARI_ROWS,
 *       CARI_ACTIONS, BANK_ACTIONS, CASH_ACTIONS, CHEQUE, COMPANY_REMAINDER, ORDERS)</li>
 * </ul>
 */
@Component
@Profile("snapshot-builder")
public class YearlySchemaCoverageExporter implements org.springframework.boot.CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(YearlySchemaCoverageExporter.class);

    public static final String SCHEMA_PATTERN = "workcube_mikrolink_{year}_{companyId}";
    public static final String CRAWL_SCOPE = "yearly";

    private final YearlySchemaDiscoveryService discovery;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String outputPath;
    private final List<Integer> yearsFilter;
    private final List<String> companyIdsFilter;
    private final List<String> tablesFilter;

    @Autowired
    public YearlySchemaCoverageExporter(
            YearlySchemaDiscoveryService discovery,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${snapshot.yearly.output-path:workcube-schema-yearly-coverage.json}") String outputPath,
            @Value("${snapshot.yearly.years:}") String yearsCsv,
            @Value("${snapshot.yearly.company-ids:}") String companyIdsCsv,
            @Value("${snapshot.yearly.tables:INVOICE,INVOICE_ROW,CARI_ROWS,CARI_ACTIONS,BANK_ACTIONS,CASH_ACTIONS,CHEQUE,COMPANY_REMAINDER,ORDERS}") String tablesCsv) {
        this.discovery = discovery;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.outputPath = outputPath;
        this.yearsFilter = parseInts(yearsCsv);
        this.companyIdsFilter = parseStrings(companyIdsCsv);
        this.tablesFilter = parseStrings(tablesCsv);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("YearlySchemaCoverageExporter starting: output={} years={} companyIds={} tables={}",
                outputPath, yearsFilter, companyIdsFilter, tablesFilter);

        List<YearlySchemaDiscoveryService.DiscoveredSchema> schemas =
                discovery.discover(yearsFilter, companyIdsFilter);

        List<Map<String, Object>> schemaEntries = new ArrayList<>();
        for (YearlySchemaDiscoveryService.DiscoveredSchema s : schemas) {
            schemaEntries.add(buildSchemaEntry(s));
        }

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifactVersion", 1);
        artifact.put("generatedAt", Instant.now().toString());
        artifact.put("crawlScope", CRAWL_SCOPE);
        artifact.put("schemaPattern", SCHEMA_PATTERN);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("years", yearsFilter);
        filters.put("companyIds", companyIdsFilter);
        artifact.put("filters", filters);
        artifact.put("schemas", schemaEntries);

        Path out = Paths.get(outputPath);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(out.toFile(), artifact);
        log.info("YearlySchemaCoverageExporter wrote {} bytes to {} (schemas={})",
                Files.size(out), outputPath, schemaEntries.size());
    }

    private Map<String, Object> buildSchemaEntry(YearlySchemaDiscoveryService.DiscoveredSchema s) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schema", s.name());
        entry.put("year", s.year());
        entry.put("companyId", s.companyId());
        entry.put("status", "covered");

        Map<String, Object> tables = new LinkedHashMap<>();
        for (String tableName : tablesFilter) {
            try {
                List<Map<String, Object>> columns = extractColumns(s.name(), tableName);
                if (columns.isEmpty()) {
                    continue;
                }
                Map<String, Object> tEntry = new LinkedHashMap<>();
                tEntry.put("columns", columns);
                tables.put(tableName, tEntry);
            } catch (Exception e) {
                log.warn("Failed to extract columns for {}.{}: {}", s.name(), tableName, e.getMessage());
            }
        }
        entry.put("tables", tables);
        return entry;
    }

    private List<Map<String, Object>> extractColumns(String schema, String tableName) {
        return jdbc.query(
                "SELECT c.name AS name, t.name AS dataType, c.is_nullable AS nullable "
                        + "FROM sys.columns c "
                        + "JOIN sys.tables tb ON c.object_id = tb.object_id "
                        + "JOIN sys.schemas s ON tb.schema_id = s.schema_id "
                        + "JOIN sys.types t ON c.user_type_id = t.user_type_id "
                        + "WHERE s.name = ? AND tb.name = ? "
                        + "ORDER BY c.column_id",
                (rs, n) -> {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("name"));
                    col.put("dataType", rs.getString("dataType"));
                    col.put("nullable", rs.getBoolean("nullable"));
                    return col;
                },
                schema, tableName);
    }

    private static List<Integer> parseInts(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(Integer.parseInt(trimmed));
        }
        return List.copyOf(out);
    }

    private static List<String> parseStrings(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
