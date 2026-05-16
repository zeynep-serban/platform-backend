package com.example.schema.model;

/**
 * Column metadata extracted from MSSQL {@code sys.columns} and related
 * catalog views — all {@code authoritative_mssql} truth tier.
 *
 * <p>Phase B1-1 (capability gap M2 — Codex {@code 019e2d7d}): expanded
 * from 7 to 16 fields. The new fields ({@code precision}, {@code scale},
 * {@code collation}, {@code identitySeed}, {@code identityIncrement},
 * {@code defaultExpression}, {@code computedExpression},
 * {@code computedPersisted}, {@code sparse}) are nullable / default-false
 * so the legacy 7-arg constructor stays backward-compatible — existing
 * callers, tests and {@code /snapshot} consumers are not broken (the JSON
 * grows additively only).
 *
 * <p>{@code precision} / {@code scale} carry MSSQL's raw
 * {@code sys.columns} values; they are meaningful for {@code DECIMAL} /
 * {@code NUMERIC} (and time-based types) and 0 elsewhere — consumers
 * interpret by {@code dataType}.
 *
 * <p>Note: a {@code unique} flag is intentionally NOT a field here.
 * Unique-constraint discovery is capability R2 (a later B1 PR); emitting
 * a {@code false} placeholder now would publish false truth.
 */
public record ColumnInfo(
    String name,
    String dataType,
    int maxLength,
    Integer precision,          // NEW — sys.columns.precision (DECIMAL/NUMERIC/time)
    Integer scale,              // NEW — sys.columns.scale
    String collation,           // NEW — sys.columns.collation_name (string columns; null otherwise)
    boolean nullable,
    boolean identity,
    Long identitySeed,          // NEW — sys.identity_columns.seed_value (null if not identity)
    Long identityIncrement,     // NEW — sys.identity_columns.increment_value
    boolean pk,
    String defaultExpression,   // NEW — sys.default_constraints.definition (null if none)
    String computedExpression,  // NEW — sys.computed_columns.definition (null if not computed)
    boolean computedPersisted,  // NEW — sys.computed_columns.is_persisted
    boolean sparse,             // NEW — sys.columns.is_sparse
    int ordinal
) {

    /**
     * Legacy 7-arg constructor — the pre-B1-1 column shape. Retained so
     * existing callers and test fixtures compile unchanged; the new
     * metadata fields default to {@code null} / {@code false}, meaning
     * "not extracted / not applicable".
     */
    public ColumnInfo(String name, String dataType, int maxLength,
                      boolean nullable, boolean identity, boolean pk, int ordinal) {
        this(name, dataType, maxLength,
             null, null, null,
             nullable, identity, null, null,
             pk, null, null, false, false,
             ordinal);
    }
}
