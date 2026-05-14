package com.example.report.dto;

import java.util.Map;

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
 * <p>PR #6b (Codex thread 019e2695): adds optional {@code aggParams} so
 * the {@code percentilecont} aggregation can carry the requested
 * percentile rank in the request body. Backwards-compatible 4-arg
 * constructor keeps every PR-0.1 / PR-0.2 / PR #6a call site unchanged.
 *
 * @param id           AG Grid column id (typically same as field).
 * @param displayName  User-facing label (frontend-only context).
 * @param field        SQL column name (must match {@code def.columns().field}).
 * @param aggFunc      Aggregation function token (sum/avg/min/max/count/
 *                     stddev/stddevp/distinctcount/median/percentilecont).
 *                     Only populated for value columns.
 * @param aggParams    PR #6b: optional parameters for parametric
 *                     aggregations. {@code percentilecont} expects
 *                     {@code {"percentile": <double in [0,1]>}}; other
 *                     funcs ignore (or reject, at the controller layer)
 *                     a populated map.
 */
public record ColumnVO(
        String id,
        String displayName,
        String field,
        String aggFunc,
        Map<String, Object> aggParams) {

    public ColumnVO {
        // Codex 019e2695 iter-8 absorb: defensive immutability for
        // aggParams. Empty maps collapse to null so the rest of the
        // pipeline can use a single null-check; non-empty maps are
        // copied to an immutable view so a future mutation on the
        // caller side cannot leak into the canonical record.
        if (aggParams != null) {
            aggParams = aggParams.isEmpty() ? null : Map.copyOf(aggParams);
        }
    }

    /**
     * Backward-compatible 4-arg constructor for call sites that predate
     * PR #6b. Defaults {@code aggParams} to {@code null} so existing
     * PR-0.1 / PR-0.2 / PR #6a code continues to work without
     * recompilation surprises.
     */
    public ColumnVO(String id, String displayName, String field, String aggFunc) {
        this(id, displayName, field, aggFunc, null);
    }
}
