package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Authoritative physical index extracted from MSSQL {@code sys.indexes} +
 * {@code sys.index_columns} — {@code authoritative_mssql} truth tier
 * (ADR-0020 §2.3, capability M4 — Codex 019e325a).
 *
 * <p>Phase B1-4. This is the complete <strong>physical</strong> index
 * inventory: clustered ({@code type=1}) + nonclustered ({@code type=2})
 * rowstore indexes, <em>including</em> the indexes that back a primary key
 * or a unique constraint. PK / unique-key semantics already live on
 * {@link ColumnInfo#pk()} and {@link UniqueConstraintInfo}; here the same
 * physical object is exposed for performance + migration (PostgreSQL index
 * design) carrying {@code isPrimaryKey} / {@code isUniqueConstraint} /
 * {@code isUnique} flags so a consumer can deduplicate rather than
 * double-count (B0 doc §5.3 — "ayrı işaret").
 *
 * <p>Heap ({@code index_id = 0}) and non-rowstore index types (columnstore /
 * XML / spatial) are excluded: the key-vs-included column split is only
 * well defined for rowstore indexes. {@code isDisabled} and
 * {@code isHypothetical} indexes are NOT silently dropped — they are carried
 * with a flag and the consumer decides whether to filter.
 */
public record IndexInfo(
    String name,
    String schema,
    String table,
    String indexType,
    List<KeyColumn> keyColumns,
    List<String> includedColumns,
    boolean isUnique,
    boolean isPrimaryKey,
    boolean isUniqueConstraint,
    boolean hasFilter,
    String filterDefinition,
    int fillFactor,
    boolean isDisabled,
    boolean isHypothetical
) {

    /**
     * One key column of the index in declared order. {@code ordinal} is the
     * MSSQL {@code sys.index_columns.key_ordinal} (1-based) — the wire
     * contract carries the position explicitly, not only via list order.
     * {@code descending} maps {@code sys.index_columns.is_descending_key}.
     */
    public record KeyColumn(String name, int ordinal, boolean descending) {}

    /**
     * True for a filtered index. Derived from the authoritative
     * {@code sys.indexes.has_filter} bit OR a non-blank predicate —
     * {@code filter_definition} can read {@code NULL} under restricted
     * metadata visibility even when {@code has_filter = 1}, so the bit is
     * the primary signal. Derived — {@code @JsonIgnore} keeps it out of the
     * wire contract.
     */
    @JsonIgnore
    public boolean isFiltered() {
        return hasFilter || (filterDefinition != null && !filterDefinition.isBlank());
    }

    /**
     * True when the index has more than one key column. Derived —
     * {@code @JsonIgnore} keeps it out of the wire contract.
     */
    @JsonIgnore
    public boolean isComposite() {
        return keyColumns != null && keyColumns.size() > 1;
    }
}
