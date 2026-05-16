package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Authoritative unique-key constraint extracted from MSSQL
 * {@code sys.indexes} ({@code is_unique=1}) + {@code sys.index_columns} —
 * {@code authoritative_mssql} truth tier (ADR-0020 §2.3).
 *
 * <p>Phase B1-2 (capability R2 — Codex 019e2d7d). ERP relationships are
 * frequently anchored on a business key ({@code CODE} / {@code NO} /
 * {@code UUID}) rather than the surrogate PK; this inventory exposes
 * those keys explicitly.
 *
 * <p><strong>Primary-key indexes are excluded</strong> — PK is already
 * carried by {@link ColumnInfo#pk()}; re-publishing it here would
 * double-count (acceptance criterion, B0 doc §5.2).
 */
public record UniqueConstraintInfo(
    String name,
    String schema,
    String table,
    List<String> columns,
    UniqueConstraintType constraintType,
    String filterDefinition
) {

    /**
     * True when the unique key spans more than one column. Derived —
     * {@code @JsonIgnore} keeps it out of the wire contract.
     */
    @JsonIgnore
    public boolean isComposite() {
        return columns != null && columns.size() > 1;
    }

    /**
     * True for a filtered unique index ({@code WHERE} predicate present).
     * Derived — {@code @JsonIgnore} keeps it out of the wire contract.
     */
    @JsonIgnore
    public boolean isFiltered() {
        return filterDefinition != null && !filterDefinition.isBlank();
    }
}
