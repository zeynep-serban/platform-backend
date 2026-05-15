package com.example.report.export;

/**
 * PR-0.5b (Codex thread 019e2cd7): export column descriptor that
 * decouples the SQL alias (used to address {@code ResultSet.getObject})
 * from the user-facing header label written to CSV/Excel.
 *
 * <p>Flat export keeps the legacy {@code field == header} invariant
 * (header is the raw column name); grouped export propagates the
 * registry's display name so the exported file matches what the user
 * sees on screen; pivot export builds a composite header
 * {@code <pivotLabel> / <AGG>(<valueDisplayName>)} so the spreadsheet
 * reads naturally without the SSRM column registration round-trip.
 *
 * <p>{@code field} MUST match the {@code ResultSet} column key emitted
 * by {@link com.example.report.query.SqlBuilder} (alias on
 * {@code AS [field]}). {@code header} is free-form display text.
 */
public record ExportColumn(String field, String header) {
    public ExportColumn {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("ExportColumn.field must be non-blank");
        }
        if (header == null) {
            header = field;
        }
    }

    /**
     * Convenience factory for the legacy flat-export call sites that
     * use the SQL alias verbatim as both field key and column header.
     */
    public static ExportColumn of(String field) {
        return new ExportColumn(field, field);
    }
}
