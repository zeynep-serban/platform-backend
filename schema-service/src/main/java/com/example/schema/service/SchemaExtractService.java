package com.example.schema.service;

import com.example.schema.model.ChangeDataInfo;
import com.example.schema.model.CheckConstraintInfo;
import com.example.schema.model.ColumnInfo;
import com.example.schema.model.DefaultConstraintInfo;
import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.IndexInfo;
import com.example.schema.model.ObjectInfo;
import com.example.schema.model.StorageInfo;
import com.example.schema.model.TableInfo;
import com.example.schema.model.UniqueConstraintInfo;
import com.example.schema.model.UniqueConstraintType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

        // Phase B1-1 (capability M2 — Codex 019e2d7d): column metadata
        // expansion. precision/scale/collation/sparse from sys.columns;
        // identity seed/increment from sys.identity_columns; default
        // expression from sys.default_constraints; computed expression +
        // persisted flag from sys.computed_columns. All authoritative_mssql.
        String sql = """
            SELECT t.name AS table_name, c.name AS column_name, ty.name AS data_type,
                   c.max_length, c.precision, c.scale, c.collation_name,
                   c.is_nullable, c.is_identity, c.is_sparse,
                   CONVERT(bigint, ic.seed_value)      AS identity_seed,
                   CONVERT(bigint, ic.increment_value) AS identity_increment,
                   CASE WHEN pk.column_id IS NOT NULL THEN 1 ELSE 0 END AS is_pk,
                   dc.definition  AS default_definition,
                   cc.definition  AS computed_definition,
                   cc.is_persisted AS computed_persisted,
                   c.column_id AS ordinal
            FROM sys.tables t
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            JOIN sys.columns c ON c.object_id = t.object_id
            JOIN sys.types ty ON c.user_type_id = ty.user_type_id
            LEFT JOIN sys.identity_columns ic
                ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            LEFT JOIN sys.default_constraints dc
                ON dc.object_id = c.default_object_id
            LEFT JOIN sys.computed_columns cc
                ON cc.object_id = c.object_id AND cc.column_id = c.column_id
            LEFT JOIN (
                SELECT pic.object_id, pic.column_id
                FROM sys.index_columns pic
                JOIN sys.indexes i ON i.object_id = pic.object_id AND i.index_id = pic.index_id
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
                    rs.getInt("precision"),
                    rs.getInt("scale"),
                    rs.getString("collation_name"),
                    rs.getBoolean("is_nullable"),
                    rs.getBoolean("is_identity"),
                    nullableLong(rs, "identity_seed"),
                    nullableLong(rs, "identity_increment"),
                    rs.getBoolean("is_pk"),
                    rs.getString("default_definition"),
                    rs.getString("computed_definition"),
                    rs.getBoolean("computed_persisted"),
                    rs.getBoolean("is_sparse"),
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

    /**
     * Reads a nullable BIGINT column, distinguishing a real SQL NULL
     * (no identity → null seed/increment) from a genuine 0.
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

    /**
     * Phase B1-2 (capability R1 — Codex 019e2d7d, ADR-0020 §2.3):
     * authoritative foreign-key extraction from {@code sys.foreign_keys} +
     * {@code sys.foreign_key_columns}. A composite FK yields a single
     * {@link ForeignKeyInfo} with column lists in declared order
     * ({@code constraint_column_id}).
     */
    @Cacheable(value = "foreignKeys", key = "#schema")
    public List<ForeignKeyInfo> extractForeignKeys(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT fk.name AS fk_name,
                   sch_from.name AS from_schema, t_from.name AS from_table,
                   c_from.name AS from_column,
                   sch_to.name AS to_schema, t_to.name AS to_table,
                   c_to.name AS to_column,
                   fk.is_disabled, fk.is_not_trusted,
                   fk.delete_referential_action_desc AS delete_action,
                   fk.update_referential_action_desc AS update_action
            FROM sys.foreign_keys fk
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.tables t_from ON t_from.object_id = fk.parent_object_id
            JOIN sys.schemas sch_from ON sch_from.schema_id = t_from.schema_id
            JOIN sys.columns c_from ON c_from.object_id = fkc.parent_object_id
                AND c_from.column_id = fkc.parent_column_id
            JOIN sys.tables t_to ON t_to.object_id = fk.referenced_object_id
            JOIN sys.schemas sch_to ON sch_to.schema_id = t_to.schema_id
            JOIN sys.columns c_to ON c_to.object_id = fkc.referenced_object_id
                AND c_to.column_id = fkc.referenced_column_id
            WHERE sch_from.name = :schema
            ORDER BY fk.name, fkc.constraint_column_id
            """;

        Map<String, FkAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            // Read every column up-front: the computeIfAbsent mapping
            // function is a plain Function and cannot throw SQLException.
            String fkName = rs.getString("fk_name");
            String fromSchema = rs.getString("from_schema");
            String fromTable = rs.getString("from_table");
            String fromColumn = rs.getString("from_column");
            String toSchema = rs.getString("to_schema");
            String toTable = rs.getString("to_table");
            String toColumn = rs.getString("to_column");
            boolean disabled = rs.getBoolean("is_disabled");
            boolean notTrusted = rs.getBoolean("is_not_trusted");
            String deleteAction = rs.getString("delete_action");
            String updateAction = rs.getString("update_action");
            FkAccumulator a = acc.computeIfAbsent(fkName, k -> new FkAccumulator(
                fkName, fromSchema, fromTable, toSchema, toTable,
                disabled, notTrusted, deleteAction, updateAction));
            a.fromColumns.add(fromColumn);
            a.toColumns.add(toColumn);
        });

        List<ForeignKeyInfo> result = new ArrayList<>();
        acc.values().forEach(a -> result.add(new ForeignKeyInfo(
            a.name, a.fromSchema, a.fromTable, List.copyOf(a.fromColumns),
            a.toSchema, a.toTable, List.copyOf(a.toColumns),
            a.isDisabled, a.isNotTrusted, a.deleteAction, a.updateAction)));
        log.info("Extracted {} foreign keys from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-2 (capability R2 — Codex 019e2d7d, ADR-0020 §2.3):
     * authoritative unique-constraint / unique-index extraction from
     * {@code sys.indexes} ({@code is_unique=1}). Primary-key indexes are
     * excluded ({@code is_primary_key=0}) — PK is already on
     * {@link ColumnInfo#pk()}. Included (non-key) columns are skipped.
     */
    @Cacheable(value = "uniqueConstraints", key = "#schema")
    public List<UniqueConstraintInfo> extractUniqueConstraints(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT i.name AS index_name,
                   sch.name AS schema_name, t.name AS table_name,
                   col.name AS column_name,
                   i.is_unique_constraint, i.filter_definition
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id
                AND ic.index_id = i.index_id
            JOIN sys.columns col ON col.object_id = ic.object_id
                AND col.column_id = ic.column_id
            WHERE sch.name = :schema
              AND i.is_unique = 1
              AND i.is_primary_key = 0
              AND ic.is_included_column = 0
            ORDER BY t.name, i.name, ic.key_ordinal
            """;

        Map<String, UqAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            // Read every column up-front: the computeIfAbsent mapping
            // function is a plain Function and cannot throw SQLException.
            String table = rs.getString("table_name");
            String indexName = rs.getString("index_name");
            String schemaName = rs.getString("schema_name");
            String column = rs.getString("column_name");
            boolean isUniqueConstraint = rs.getBoolean("is_unique_constraint");
            String filter = rs.getString("filter_definition");
            UqAccumulator a = acc.computeIfAbsent(table + "." + indexName, k -> new UqAccumulator(
                indexName, schemaName, table,
                isUniqueConstraint
                    ? UniqueConstraintType.UNIQUE_CONSTRAINT
                    : UniqueConstraintType.UNIQUE_INDEX,
                filter));
            a.columns.add(column);
        });

        List<UniqueConstraintInfo> result = new ArrayList<>();
        acc.values().forEach(a -> result.add(new UniqueConstraintInfo(
            a.name, a.schema, a.table, List.copyOf(a.columns),
            a.constraintType, a.filterDefinition)));
        log.info("Extracted {} unique constraints from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-3 (capability M3 — Codex 019e2d7d, ADR-0020 §2.3):
     * authoritative {@code CHECK} constraint extraction from
     * {@code sys.check_constraints}. {@code parent_column_id = 0} →
     * table-level check ({@code columnName} null via the LEFT JOIN);
     * {@code definition} is the raw CHECK SQL, not parsed.
     */
    @Cacheable(value = "checkConstraints", key = "#schema")
    public List<CheckConstraintInfo> extractCheckConstraints(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT cc.name AS cc_name, sch.name AS schema_name, t.name AS table_name,
                   col.name AS column_name, cc.definition,
                   cc.is_disabled, cc.is_not_trusted
            FROM sys.check_constraints cc
            JOIN sys.tables t ON t.object_id = cc.parent_object_id
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            LEFT JOIN sys.columns col ON col.object_id = cc.parent_object_id
                AND col.column_id = cc.parent_column_id
            WHERE sch.name = :schema
            ORDER BY t.name, cc.name
            """;

        List<CheckConstraintInfo> result = new ArrayList<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            result.add(new CheckConstraintInfo(
                rs.getString("cc_name"),
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("definition"),
                rs.getBoolean("is_disabled"),
                rs.getBoolean("is_not_trusted")));
        });
        log.info("Extracted {} check constraints from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-3 (capability M3 — Codex 019e2d7d, ADR-0020 §2.3):
     * authoritative {@code DEFAULT} constraint extraction from
     * {@code sys.default_constraints}. Carries the constraint name (needed
     * for migration {@code DROP}) — distinct from the column-ergonomic
     * {@link ColumnInfo#defaultExpression()}.
     */
    @Cacheable(value = "defaultConstraints", key = "#schema")
    public List<DefaultConstraintInfo> extractDefaultConstraints(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT dc.name AS dc_name, sch.name AS schema_name, t.name AS table_name,
                   col.name AS column_name, dc.definition
            FROM sys.default_constraints dc
            JOIN sys.tables t ON t.object_id = dc.parent_object_id
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            JOIN sys.columns col ON col.object_id = dc.parent_object_id
                AND col.column_id = dc.parent_column_id
            WHERE sch.name = :schema
            ORDER BY t.name, dc.name
            """;

        List<DefaultConstraintInfo> result = new ArrayList<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            result.add(new DefaultConstraintInfo(
                rs.getString("dc_name"),
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("definition")));
        });
        log.info("Extracted {} default constraints from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-4 (capability M4 — Codex 019e325a, ADR-0020 §2.3):
     * authoritative physical index extraction from {@code sys.indexes} +
     * {@code sys.index_columns}. Heap ({@code index_id = 0}) and non-rowstore
     * index types are excluded ({@code type IN (1,2)} — clustered /
     * nonclustered) because the key-vs-included column split is only well
     * defined for rowstore indexes. PK- and unique-constraint-backed indexes
     * ARE included, flagged ({@code is_primary_key} / {@code is_unique_constraint})
     * so a consumer can deduplicate against {@link ColumnInfo#pk()} /
     * {@link UniqueConstraintInfo} rather than double-count (B0 §5.3 — physical
     * inventory, "ayrı işaret"). Disabled / hypothetical indexes are carried
     * with a flag, never silently dropped.
     */
    @Cacheable(value = "indexes", key = "#schema")
    public List<IndexInfo> extractIndexes(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT i.name AS index_name, sch.name AS schema_name, t.name AS table_name,
                   i.type_desc AS index_type, i.is_unique, i.is_primary_key,
                   i.is_unique_constraint, i.has_filter, i.filter_definition,
                   i.fill_factor, i.is_disabled, i.is_hypothetical,
                   col.name AS column_name,
                   ic.is_included_column, ic.is_descending_key, ic.key_ordinal
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id
                AND ic.index_id = i.index_id
            JOIN sys.columns col ON col.object_id = ic.object_id
                AND col.column_id = ic.column_id
            WHERE sch.name = :schema
              AND i.index_id > 0
              AND i.type IN (1, 2)
            ORDER BY t.name, i.name, ic.is_included_column, ic.key_ordinal,
                     ic.index_column_id
            """;

        Map<String, IndexAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            // Read every column up-front: the computeIfAbsent mapping
            // function is a plain Function and cannot throw SQLException.
            String table = rs.getString("table_name");
            String indexName = rs.getString("index_name");
            String schemaName = rs.getString("schema_name");
            String indexType = rs.getString("index_type");
            boolean isUnique = rs.getBoolean("is_unique");
            boolean isPrimaryKey = rs.getBoolean("is_primary_key");
            boolean isUniqueConstraint = rs.getBoolean("is_unique_constraint");
            boolean hasFilter = rs.getBoolean("has_filter");
            String filter = rs.getString("filter_definition");
            int fillFactor = rs.getInt("fill_factor");
            boolean isDisabled = rs.getBoolean("is_disabled");
            boolean isHypothetical = rs.getBoolean("is_hypothetical");
            String column = rs.getString("column_name");
            boolean included = rs.getBoolean("is_included_column");
            boolean descending = rs.getBoolean("is_descending_key");
            int keyOrdinal = rs.getInt("key_ordinal");
            IndexAccumulator a = acc.computeIfAbsent(
                schemaName + "." + table + "." + indexName,
                k -> new IndexAccumulator(indexName, schemaName, table, indexType,
                    isUnique, isPrimaryKey, isUniqueConstraint, hasFilter, filter,
                    fillFactor, isDisabled, isHypothetical));
            if (included) {
                a.includedColumns.add(column);
            } else if (keyOrdinal > 0) {
                a.keyColumns.add(new IndexInfo.KeyColumn(column, keyOrdinal, descending));
            }
            // key_ordinal = 0 && !included is not expected for rowstore
            // (type IN (1,2)) — defensively skipped rather than mis-mapped.
        });

        List<IndexInfo> result = new ArrayList<>();
        acc.values().forEach(a -> result.add(new IndexInfo(
            a.name, a.schema, a.table, a.indexType,
            List.copyOf(a.keyColumns), List.copyOf(a.includedColumns),
            a.isUnique, a.isPrimaryKey, a.isUniqueConstraint,
            a.hasFilter, a.filterDefinition, a.fillFactor,
            a.isDisabled, a.isHypothetical)));
        log.info("Extracted {} indexes from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-5 (capability M1 — Codex 019e3270, ADR-0020 §2.3): authoritative
     * object catalog from {@code sys.objects} + {@code sys.extended_properties}.
     * Covers user tables / views / procedures / functions / triggers / synonyms
     * ({@code is_ms_shipped = 0}) — metadata only; programmability bodies are
     * capability M8 (B2). The object owner falls back to the schema owner when
     * {@code sys.objects.principal_id} is null (the common case). ALL
     * object-level extended properties are collected (key-sorted for a
     * deterministic snapshot; a {@code NULL} property value is preserved),
     * not only {@code MS_Description}.
     */
    @Cacheable(value = "objects", key = "#schema")
    public List<ObjectInfo> extractObjects(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT o.name AS object_name, sch.name AS schema_name,
                   o.type_desc AS object_type, o.object_id,
                   o.create_date, o.modify_date,
                   prin.name AS owner_name,
                   ep.name AS ep_name,
                   CAST(ep.value AS NVARCHAR(MAX)) AS ep_value
            FROM sys.objects o
            JOIN sys.schemas sch ON sch.schema_id = o.schema_id
            LEFT JOIN sys.database_principals prin
                ON prin.principal_id = COALESCE(o.principal_id, sch.principal_id)
            LEFT JOIN sys.extended_properties ep
                ON ep.class = 1 AND ep.major_id = o.object_id AND ep.minor_id = 0
            WHERE sch.name = :schema
              AND o.is_ms_shipped = 0
              AND o.type IN ('U', 'V', 'P', 'FN', 'IF', 'TF', 'TR', 'SN')
            ORDER BY sch.name, o.name
            """;

        Map<String, ObjectAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            // Read every column up-front: the computeIfAbsent mapping
            // function is a plain Function and cannot throw SQLException.
            String objectName = rs.getString("object_name");
            String schemaName = rs.getString("schema_name");
            String objectType = rs.getString("object_type");
            int objectId = rs.getInt("object_id");
            LocalDateTime createDate = toLocalDateTime(rs, "create_date");
            LocalDateTime modifyDate = toLocalDateTime(rs, "modify_date");
            String owner = rs.getString("owner_name");
            String epName = rs.getString("ep_name");
            String epValue = rs.getString("ep_value");
            ObjectAccumulator a = acc.computeIfAbsent(
                schemaName + "." + objectName,
                k -> new ObjectAccumulator(objectName, schemaName, objectType,
                    objectId, owner, createDate, modifyDate));
            // LEFT JOIN → ep_name is null when the object has no extended
            // property; only real rows are collected.
            if (epName != null) {
                a.extendedProperties.put(epName, epValue);
            }
        });

        List<ObjectInfo> result = new ArrayList<>();
        acc.values().forEach(a -> result.add(new ObjectInfo(
            a.name, a.schema, a.objectType, a.objectId, a.owner,
            a.createDate, a.modifyDate,
            // Unmodifiable TreeMap: deterministic key order for stable
            // snapshot artifacts, and tolerates a null extended-property
            // value (sql_variant NULL) — Map.copyOf would reject it and
            // collapse the whole object inventory via the non-fatal catch.
            Collections.unmodifiableMap(new TreeMap<>(a.extendedProperties)))));
        log.info("Extracted {} objects from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /** Reads a SQL Server {@code datetime} as a timezone-free {@link LocalDateTime}. */
    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toLocalDateTime() : null;
    }

    /**
     * Phase B1-6 (capability M6 — Codex 019e329a, ADR-0020 §2.3): authoritative
     * per-table storage footprint from {@code sys.dm_db_partition_stats}. One
     * {@link StorageInfo} per table — page counts aggregated over all
     * partitions / indexes, converted to KB (page = 8 KB). {@code rowCount}
     * and {@code dataKb} count only the base heap / clustered index
     * ({@code index_id IN (0,1)}); nonclustered-index in-row pages fall into
     * {@code indexKb}. {@code lobKb} and {@code rowOverflowKb} stay distinct.
     *
     * <p>{@code sys.dm_db_partition_stats} is a DMV requiring {@code VIEW
     * DATABASE STATE}; without that grant the read fails and the caller's
     * non-fatal catch yields an empty inventory (source-ready, not live-ready).
     */
    @Cacheable(value = "storage", key = "#schema")
    public List<StorageInfo> extractStorage(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;
        String sql = """
            SELECT t.name AS table_name, sch.name AS schema_name,
                   SUM(CASE WHEN ps.index_id IN (0, 1)
                            THEN ps.row_count ELSE 0 END) AS row_count,
                   SUM(ps.reserved_page_count) * 8 AS reserved_kb,
                   SUM(ps.used_page_count) * 8 AS used_kb,
                   SUM(CASE WHEN ps.index_id IN (0, 1)
                            THEN ps.in_row_data_page_count ELSE 0 END) * 8 AS data_kb,
                   SUM(ps.lob_used_page_count) * 8 AS lob_kb,
                   SUM(ps.row_overflow_used_page_count) * 8 AS row_overflow_kb,
                   (SUM(ps.used_page_count)
                      - SUM(CASE WHEN ps.index_id IN (0, 1)
                                 THEN ps.in_row_data_page_count ELSE 0 END)
                      - SUM(ps.lob_used_page_count)
                      - SUM(ps.row_overflow_used_page_count)) * 8 AS index_kb
            FROM sys.dm_db_partition_stats ps
            JOIN sys.tables t ON t.object_id = ps.object_id
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            WHERE sch.name = :schema
            GROUP BY t.name, sch.name
            ORDER BY t.name
            """;

        List<StorageInfo> result = new ArrayList<>();
        jdbc.query(sql, Map.of("schema", targetSchema), rs -> {
            result.add(new StorageInfo(
                rs.getString("table_name"),
                rs.getString("schema_name"),
                rs.getLong("row_count"),
                rs.getLong("reserved_kb"),
                rs.getLong("used_kb"),
                rs.getLong("data_kb"),
                rs.getLong("index_kb"),
                rs.getLong("lob_kb"),
                rs.getLong("row_overflow_kb")));
        });
        log.info("Extracted storage for {} tables from schema '{}'", result.size(), targetSchema);
        return result;
    }

    /**
     * Phase B1-7 (capability M13 — Codex 019e32aa, ADR-0020 §2.3): authoritative
     * per-table change-data feature inventory — Change Data Capture, Change
     * Tracking, system-versioned temporal tables and replication. Only tables
     * bearing at least one feature are returned (a filtered, sparse inventory);
     * an empty result from a successful run means the schema uses none.
     *
     * <p>Two queries: the base read (CDC / Change Tracking / replication) uses
     * only SQL Server 2008+ catalog columns; the temporal enrichment
     * ({@code temporal_type_desc} / {@code history_table_id}) is 2016+ and runs
     * in its own try/catch, so a pre-2016 engine still yields CDC / Change
     * Tracking / replication results instead of collapsing all of M13.
     */
    @Cacheable(value = "changeData", key = "#schema")
    public List<ChangeDataInfo> extractChangeData(String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;

        // Base — CDC / Change Tracking / replication (SQL Server 2008+ catalog).
        String baseSql = """
            SELECT t.name AS table_name, sch.name AS schema_name,
                   t.is_tracked_by_cdc AS cdc_enabled,
                   t.is_replicated, t.is_merge_published,
                   t.has_replication_filter, t.is_sync_tran_subscribed,
                   CASE WHEN ctt.object_id IS NOT NULL THEN 1 ELSE 0 END AS ct_enabled,
                   ctt.is_track_columns_updated_on,
                   ctt.min_valid_version, ctt.begin_version, ctt.cleanup_version
            FROM sys.tables t
            JOIN sys.schemas sch ON sch.schema_id = t.schema_id
            LEFT JOIN sys.change_tracking_tables ctt ON ctt.object_id = t.object_id
            WHERE sch.name = :schema
            ORDER BY t.name
            """;

        Map<String, ChangeDataAccumulator> acc = new LinkedHashMap<>();
        jdbc.query(baseSql, Map.of("schema", targetSchema), rs -> {
            String table = rs.getString("table_name");
            ChangeDataAccumulator a = new ChangeDataAccumulator(table, rs.getString("schema_name"));
            a.cdcEnabled = rs.getBoolean("cdc_enabled");
            a.changeTrackingEnabled = rs.getBoolean("ct_enabled");
            a.trackColumnsUpdated = rs.getBoolean("is_track_columns_updated_on");
            a.ctMinValidVersion = nullableLong(rs, "min_valid_version");
            a.ctBeginVersion = nullableLong(rs, "begin_version");
            a.ctCleanupVersion = nullableLong(rs, "cleanup_version");
            a.transactionalReplicationEnabled = rs.getBoolean("is_replicated");
            a.mergePublished = rs.getBoolean("is_merge_published");
            a.replicationFilterEnabled = rs.getBoolean("has_replication_filter");
            a.syncTranSubscribed = rs.getBoolean("is_sync_tran_subscribed");
            acc.put(table, a);
        });

        // Temporal enrichment — SQL Server 2016+ only. Isolated so a pre-2016
        // "Invalid column name" failure preserves the base results above.
        try {
            String temporalSql = """
                SELECT t.name AS table_name,
                       t.temporal_type_desc AS temporal_type,
                       hsch.name AS history_schema, ht.name AS history_table
                FROM sys.tables t
                JOIN sys.schemas sch ON sch.schema_id = t.schema_id
                LEFT JOIN sys.tables ht ON ht.object_id = t.history_table_id
                LEFT JOIN sys.schemas hsch ON hsch.schema_id = ht.schema_id
                WHERE sch.name = :schema
                """;
            jdbc.query(temporalSql, Map.of("schema", targetSchema), rs -> {
                ChangeDataAccumulator a = acc.get(rs.getString("table_name"));
                if (a != null) {
                    a.temporalType = rs.getString("temporal_type");
                    a.historySchema = rs.getString("history_schema");
                    a.historyTable = rs.getString("history_table");
                }
            });
        } catch (Exception e) {
            log.warn("Temporal enrichment failed (SQL Server < 2016?) — CDC / "
                + "Change Tracking / replication preserved: {}", e.getMessage());
        }

        List<ChangeDataInfo> result = new ArrayList<>();
        acc.values().forEach(a -> {
            if (a.isFeatureBearing()) {
                result.add(new ChangeDataInfo(
                    a.table, a.schema, a.cdcEnabled, a.changeTrackingEnabled,
                    a.trackColumnsUpdated, a.ctMinValidVersion, a.ctBeginVersion,
                    a.ctCleanupVersion, a.temporalType, a.historySchema, a.historyTable,
                    a.transactionalReplicationEnabled, a.mergePublished,
                    a.replicationFilterEnabled, a.syncTranSubscribed));
            }
        });
        log.info("Extracted {} change-data feature tables from schema '{}' ({} scanned)",
            result.size(), targetSchema, acc.size());
        return result;
    }

    /** Mutable accumulator — groups multi-column FK rows by constraint name. */
    private static final class FkAccumulator {
        final String name;
        final String fromSchema;
        final String fromTable;
        final String toSchema;
        final String toTable;
        final boolean isDisabled;
        final boolean isNotTrusted;
        final String deleteAction;
        final String updateAction;
        final List<String> fromColumns = new ArrayList<>();
        final List<String> toColumns = new ArrayList<>();

        FkAccumulator(String name, String fromSchema, String fromTable,
                      String toSchema, String toTable, boolean isDisabled,
                      boolean isNotTrusted, String deleteAction, String updateAction) {
            this.name = name;
            this.fromSchema = fromSchema;
            this.fromTable = fromTable;
            this.toSchema = toSchema;
            this.toTable = toTable;
            this.isDisabled = isDisabled;
            this.isNotTrusted = isNotTrusted;
            this.deleteAction = deleteAction;
            this.updateAction = updateAction;
        }
    }

    /** Mutable accumulator — groups multi-column unique index rows. */
    private static final class UqAccumulator {
        final String name;
        final String schema;
        final String table;
        final UniqueConstraintType constraintType;
        final String filterDefinition;
        final List<String> columns = new ArrayList<>();

        UqAccumulator(String name, String schema, String table,
                      UniqueConstraintType constraintType, String filterDefinition) {
            this.name = name;
            this.schema = schema;
            this.table = table;
            this.constraintType = constraintType;
            this.filterDefinition = filterDefinition;
        }
    }

    /** Mutable accumulator — groups multi-column index rows by index name. */
    private static final class IndexAccumulator {
        final String name;
        final String schema;
        final String table;
        final String indexType;
        final boolean isUnique;
        final boolean isPrimaryKey;
        final boolean isUniqueConstraint;
        final boolean hasFilter;
        final String filterDefinition;
        final int fillFactor;
        final boolean isDisabled;
        final boolean isHypothetical;
        final List<IndexInfo.KeyColumn> keyColumns = new ArrayList<>();
        final List<String> includedColumns = new ArrayList<>();

        IndexAccumulator(String name, String schema, String table, String indexType,
                         boolean isUnique, boolean isPrimaryKey, boolean isUniqueConstraint,
                         boolean hasFilter, String filterDefinition, int fillFactor,
                         boolean isDisabled, boolean isHypothetical) {
            this.name = name;
            this.schema = schema;
            this.table = table;
            this.indexType = indexType;
            this.isUnique = isUnique;
            this.isPrimaryKey = isPrimaryKey;
            this.isUniqueConstraint = isUniqueConstraint;
            this.hasFilter = hasFilter;
            this.filterDefinition = filterDefinition;
            this.fillFactor = fillFactor;
            this.isDisabled = isDisabled;
            this.isHypothetical = isHypothetical;
        }
    }

    /** Mutable accumulator — collects an object's extended-property rows. */
    private static final class ObjectAccumulator {
        final String name;
        final String schema;
        final String objectType;
        final Integer objectId;
        final String owner;
        final LocalDateTime createDate;
        final LocalDateTime modifyDate;
        final Map<String, String> extendedProperties = new LinkedHashMap<>();

        ObjectAccumulator(String name, String schema, String objectType, Integer objectId,
                          String owner, LocalDateTime createDate, LocalDateTime modifyDate) {
            this.name = name;
            this.schema = schema;
            this.objectType = objectType;
            this.objectId = objectId;
            this.owner = owner;
            this.createDate = createDate;
            this.modifyDate = modifyDate;
        }
    }

    /** Mutable accumulator — one change-data feature row, base + temporal merge. */
    private static final class ChangeDataAccumulator {
        final String table;
        final String schema;
        boolean cdcEnabled;
        boolean changeTrackingEnabled;
        boolean trackColumnsUpdated;
        Long ctMinValidVersion;
        Long ctBeginVersion;
        Long ctCleanupVersion;
        String temporalType = "NON_TEMPORAL_TABLE";
        String historySchema;
        String historyTable;
        boolean transactionalReplicationEnabled;
        boolean mergePublished;
        boolean replicationFilterEnabled;
        boolean syncTranSubscribed;

        ChangeDataAccumulator(String table, String schema) {
            this.table = table;
            this.schema = schema;
        }

        boolean isFeatureBearing() {
            return cdcEnabled || changeTrackingEnabled
                || !"NON_TEMPORAL_TABLE".equals(temporalType)
                || transactionalReplicationEnabled || mergePublished
                || replicationFilterEnabled || syncTranSubscribed;
        }
    }
}
