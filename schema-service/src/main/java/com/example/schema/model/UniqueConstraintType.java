package com.example.schema.model;

/**
 * Phase B1-2 (capability R2 — Codex 019e2d7d): origin of a unique-key
 * guarantee.
 *
 * <ul>
 *   <li>{@link #UNIQUE_CONSTRAINT} — a declared {@code UNIQUE} constraint
 *       (backed by a unique index whose {@code is_unique_constraint=1});</li>
 *   <li>{@link #UNIQUE_INDEX} — a plain unique index ({@code is_unique=1})
 *       that is not a declared constraint.</li>
 * </ul>
 *
 * <p>Primary-key indexes are excluded entirely — PK is already exposed via
 * {@link ColumnInfo#pk()}.
 */
public enum UniqueConstraintType {
    UNIQUE_CONSTRAINT,
    UNIQUE_INDEX
}
