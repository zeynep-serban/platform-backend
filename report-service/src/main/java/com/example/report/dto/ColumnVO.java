package com.example.report.dto;

/**
 * AG Grid SSRM ColumnVO — used inside {@link ReportQueryRequestDto}.
 *
 * <p>Mirrors AG Grid's {@code ColumnVO} shape passed in
 * {@code IServerSideGetRowsRequest} (rowGroupCols, valueCols, pivotCols).
 * Frontend serializes its column metadata into this DTO so the backend can
 * route grouping / aggregation / pivot decisions without re-deriving the
 * column model.
 *
 * <p>PR-0.1 scope: backend ACCEPTS this DTO on the new POST query contract
 * but does not yet act on rowGroupCols / pivotMode (capability flag stays
 * false; rejected at request validation). PR-0.2+ will fill in GROUP BY
 * + aggregation + pivot expansion handling.
 *
 * @param id           AG Grid column id (typically same as field).
 * @param displayName  User-facing label (frontend-only context).
 * @param field        SQL column name (must match {@code def.columns().field}).
 * @param aggFunc      Aggregation function (sum/avg/min/max/count) — only
 *                     populated for value columns.
 */
public record ColumnVO(
        String id,
        String displayName,
        String field,
        String aggFunc) {}
