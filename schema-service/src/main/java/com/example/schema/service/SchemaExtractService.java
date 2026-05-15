package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SchemaExtractService {

    private static final Logger log = LoggerFactory.getLogger(SchemaExtractService.class);

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    /**
     * Phase 1 portability (Codex 019e2d14 §8 — PR #701 quick wins):
     * Schema discovery LIKE patterns config'e taşındı. Önce Workcube
     * pattern'i (geriye uyumlu default), sonra "dbo". Yeni ERP veya
     * tenant ekleyince listeye eklenir, kod değişikliği yok.
     */
    @Value("${schema.discovery.patterns:workcube_mikrolink%,dbo}")
    private String[] discoveryPatterns;

    public SchemaExtractService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(value = "tables", key = "#schema")
    public Map<String, TableInfo> extractTables(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        log.info("Extracting tables from schema '{}'...", targetSchema);

        String sql = """
            SELECT t.name AS table_name, c.name AS column_name, ty.name AS data_type,
                   c.max_length, c.is_nullable, c.is_identity,
                   CASE WHEN pk.column_id IS NOT NULL THEN 1 ELSE 0 END AS is_pk,
                   c.column_id AS ordinal
            FROM sys.tables t
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            JOIN sys.columns c ON c.object_id = t.object_id
            JOIN sys.types ty ON c.user_type_id = ty.user_type_id
            LEFT JOIN (
                SELECT ic.object_id, ic.column_id
                FROM sys.index_columns ic
                JOIN sys.indexes i ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                WHERE i.is_primary_key = 1
            ) pk ON pk.object_id = t.object_id AND pk.column_id = c.column_id
            WHERE s.name = :schema
            ORDER BY t.name, c.column_id
            """;

        Map<String, List<ColumnInfo>> tablesMap = new LinkedHashMap<>();

        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            String tableName = rs.getString("table_name");
            tablesMap.computeIfAbsent(tableName, k -> new ArrayList<>())
                .add(new ColumnInfo(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getInt("max_length"),
                    rs.getBoolean("is_nullable"),
                    rs.getBoolean("is_identity"),
                    rs.getBoolean("is_pk"),
                    rs.getInt("ordinal")
                ));
        });

        Map<String, TableInfo> result = new LinkedHashMap<>();
        tablesMap.forEach((name, cols) ->
            result.put(name, new TableInfo(name, targetSchema, cols))
        );

        log.info("Extracted {} tables, {} columns", result.size(),
            result.values().stream().mapToInt(t -> t.columns().size()).sum());
        return result;
    }

    @Cacheable(value = "rowCounts", key = "#schema")
    public Map<String, Long> getRowCounts(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT t.name, SUM(p.rows) AS row_count
            FROM sys.tables t
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            JOIN sys.partitions p ON t.object_id = p.object_id AND p.index_id IN (0, 1)
            WHERE s.name = :schema
            GROUP BY t.name
            """;

        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            counts.put(rs.getString("name"), rs.getLong("row_count"));
        });
        return counts;
    }

    @Cacheable(value = "viewDefs", key = "#schema")
    public Map<String, String> getViewDefinitions(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT o.name, m.definition
            FROM sys.sql_modules m
            JOIN sys.objects o ON m.object_id = o.object_id
            JOIN sys.schemas s ON o.schema_id = s.schema_id
            WHERE o.type = 'V' AND s.name = :schema
            """;

        Map<String, String> views = new HashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            views.put(rs.getString("name"), rs.getString("definition"));
        });
        log.info("Extracted {} view definitions", views.size());
        return views;
    }

    public Set<String> getTableNames(String schema) {
        return extractTables(schema).keySet();
    }

    /**
     * List all available schemas matching configured patterns (default:
     * Workcube — workcube_mikrolink%, dbo). Phase 1 portability:
     * `schema.discovery.patterns` config ile yeni ERP'ler eklenebilir
     * (örn. `eta_%,logo_%,workcube_mikrolink%,dbo`) kod değişikliği yok.
     */
    @Cacheable("schemas")
    public List<Map<String, Object>> listSchemas() {
        log.info("Listing available schemas matching patterns: {}", Arrays.toString(discoveryPatterns));

        // Build WHERE clause: s.name LIKE :p0 OR s.name LIKE :p1 OR ...
        // Empty pattern → safe fallback (Workcube + dbo) to avoid SQL injection or empty IN clause.
        String[] patterns = (discoveryPatterns == null || discoveryPatterns.length == 0)
            ? new String[] {"workcube_mikrolink%", "dbo"}
            : discoveryPatterns;

        StringBuilder whereClause = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < patterns.length; i++) {
            if (i > 0) whereClause.append(" OR ");
            String paramName = "p" + i;
            // Pattern with '%' → LIKE; without '%' → exact match
            if (patterns[i].contains("%")) {
                whereClause.append("s.name LIKE :").append(paramName);
            } else {
                whereClause.append("s.name = :").append(paramName);
            }
            params.put(paramName, patterns[i].trim());
        }

        String sql = """
            SELECT s.name AS schema_name, COUNT(t.object_id) AS table_count
            FROM sys.schemas s
            LEFT JOIN sys.tables t ON s.schema_id = t.schema_id
            WHERE %s
            GROUP BY s.name
            HAVING COUNT(t.object_id) > 0
            ORDER BY COUNT(t.object_id) DESC
            """.formatted(whereClause.toString());

        List<Map<String, Object>> schemas = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            schemas.add(Map.of(
                "name", rs.getString("schema_name"),
                "tableCount", rs.getInt("table_count")
            ));
        });
        log.info("Found {} schemas matching {} patterns", schemas.size(), patterns.length);
        return schemas;
    }
}
