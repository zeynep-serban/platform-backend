package com.example.report.schema;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 Program 8 — schema-service `/api/v1/schema/snapshot` response mirror
 * (lightweight subset).
 *
 * <p>Schema-service'in tam {@code SchemaSnapshot} record'u {@code metadata},
 * {@code relationships}, {@code domains}, {@code analysis} alanlarını da
 * içeriyor; bu mirror sadece SchemaTruthService consumer'larının kullandığı
 * {@code tables} alt-küme'ini deserialize eder.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} → schema-service
 * field eklemeleri runtime'ı kırmasın.
 *
 * <p>Spec: §2.1 (top-level POJO).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaSnapshot(
        @JsonProperty("tables") Map<String, TableInfo> tables
) {

    @JsonCreator
    public SchemaSnapshot {
        // tables null gelirse boş map'e indir (defensive)
        if (tables == null) {
            tables = Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableInfo(
            @JsonProperty("name") String name,
            @JsonProperty("schema") String schema,
            @JsonProperty("columns") List<ColumnInfo> columns
    ) {
        @JsonCreator
        public TableInfo {
            if (columns == null) {
                columns = List.of();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnInfo(
            @JsonProperty("name") String name,
            // Schema-service ColumnInfo uses field name `dataType`. We keep
            // `@JsonAlias({"type"})` for backward compat with Tier 2 committed
            // snapshot variants that may have used `type` historically;
            // production Tier 1 fetches return `dataType`. Codex iter-1 §2 absorb.
            @JsonProperty("dataType") @JsonAlias({"type"}) String dataType,
            @JsonProperty("nullable") Boolean nullable
    ) {}
}
