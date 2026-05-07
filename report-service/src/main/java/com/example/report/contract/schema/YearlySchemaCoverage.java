package com.example.report.contract.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 Program 2b — Yearly schema coverage artifact (Codex iter-15
 * §2b-AGREE absorb, thread 019e0119).
 *
 * <p>Targeted-table coverage per
 * {@code workcube_mikrolink_<year>_<companyId>} schema. Build-time
 * artifact (production: schema-service exporter; test: fixture).
 *
 * <p>NOT a SchemaSnapshot extension (table name collisions across
 * yearly partitions); ayrı container model.
 *
 * @param artifactVersion v1
 * @param generatedAt     ISO-8601 instant
 * @param crawlScope      "yearly" (future: "yearly+canonical")
 * @param schemaPattern   discovery regex source pattern
 * @param filters         optional bounds (years, companyIds)
 * @param schemas         per-schema coverage entries
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YearlySchemaCoverage(
        @JsonProperty("artifactVersion") int artifactVersion,
        @JsonProperty("generatedAt") Instant generatedAt,
        @JsonProperty("crawlScope") String crawlScope,
        @JsonProperty("schemaPattern") String schemaPattern,
        @JsonProperty("filters") Filters filters,
        @JsonProperty("schemas") List<SchemaCoverage> schemas
) {

    @JsonCreator
    public YearlySchemaCoverage {
        if (schemas == null) schemas = List.of();
        schemas = List.copyOf(schemas);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filters(
            @JsonProperty("years") List<Integer> years,
            @JsonProperty("companyIds") List<String> companyIds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SchemaCoverage(
            @JsonProperty("schema") String schema,
            @JsonProperty("year") int year,
            @JsonProperty("companyId") String companyId,
            @JsonProperty("status") String status,
            @JsonProperty("tables") Map<String, TableCoverage> tables
    ) {

        @JsonCreator
        public SchemaCoverage {
            if (tables == null) tables = Map.of();
            tables = Map.copyOf(tables);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableCoverage(
            @JsonProperty("columns") List<ColumnCoverage> columns
    ) {

        @JsonCreator
        public TableCoverage {
            if (columns == null) columns = List.of();
            columns = List.copyOf(columns);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnCoverage(
            @JsonProperty("name") String name,
            @JsonProperty("dataType") String dataType,
            @JsonProperty("nullable") Boolean nullable
    ) {}
}
