package com.example.commonexport;

/**
 * Export column descriptor that decouples the SQL alias (used to address
 * {@code ResultSet.getObject}) from the user-facing header label written
 * to CSV/Excel.
 *
 * <p>Originally introduced in report-service (Codex thread 019e2cd7,
 * PR-0.5b); extracted to {@code common-export} so endpoint-admin-service
 * can reuse the same exporters (Codex thread 019e7e35, board #1154).
 *
 * <p>Flat export keeps the legacy {@code field == header} invariant
 * (header is the raw column name); grouped/pivot export propagates the
 * registry's display name so the exported file matches what the user sees
 * on screen.
 *
 * <p>{@code field} MUST match the {@code ResultSet} column key emitted by
 * the query (the SQL {@code AS [field]} alias). {@code header} is
 * free-form display text; when {@code null} it defaults to {@code field}.
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
     * Convenience factory for the legacy flat-export call sites that use
     * the SQL alias verbatim as both field key and column header.
     */
    public static ExportColumn of(String field) {
        return new ExportColumn(field, field);
    }
}
