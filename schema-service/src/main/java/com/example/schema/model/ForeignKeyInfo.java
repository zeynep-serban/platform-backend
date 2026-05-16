package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Authoritative foreign-key constraint extracted from MSSQL
 * {@code sys.foreign_keys} + {@code sys.foreign_key_columns} —
 * {@code authoritative_mssql} truth tier (ADR-0020 §2.3).
 *
 * <p>Phase B1-2 (capability R1 — Codex 019e2d7d). Unlike the heuristic
 * {@link Relationship} (name/alias/view-parse inference), this carries a
 * real declared constraint: composite column sets in declared order,
 * {@code isDisabled} / {@code isNotTrusted} state, and the referential
 * cascade actions. Schema-qualified so a multi-schema snapshot stays
 * unambiguous.
 *
 * <p>Single-column FKs are also surfaced in {@code SchemaSnapshot.relationships}
 * as a compatibility {@link Relationship} ({@code source="fk_constraint"},
 * {@code confidence=1.0}); composite FKs stay authoritative here only and
 * are NOT flattened into the heuristic relationship list (Codex 019e2d7d
 * guardrail — the dedup key lacks {@code toColumn}).
 */
public record ForeignKeyInfo(
    String name,
    String fromSchema,
    String fromTable,
    List<String> fromColumns,
    String toSchema,
    String toTable,
    List<String> toColumns,
    boolean isDisabled,
    boolean isNotTrusted,
    String deleteAction,
    String updateAction
) {

    /**
     * True when the constraint spans more than one column. Derived —
     * {@code @JsonIgnore} keeps it out of the wire contract (the record
     * components are the serialized shape).
     */
    @JsonIgnore
    public boolean isComposite() {
        return fromColumns != null && fromColumns.size() > 1;
    }
}
