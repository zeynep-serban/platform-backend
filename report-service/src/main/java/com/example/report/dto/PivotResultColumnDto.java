package com.example.report.dto;

/**
 * PR-0.4d-be (Codex thread {@code 019e2695}): HTTP-boundary projection of
 * {@link com.example.report.query.PivotResultColumn}. Carries every piece
 * of metadata the frontend needs to materialise an AG Grid SSRM secondary
 * column without re-fetching report metadata or re-parsing the SQL alias.
 *
 * <p>Kept separate from the query-layer record so {@code SqlBuilder} stays
 * free of any DTO/web dependency; the controller maps domain →
 * {@code PivotResultColumnDto} at the HTTP boundary.
 *
 * @param field        SQL alias the row data carries (same as the
 *                     adjacent entry in
 *                     {@link PagedResultDto#pivotResultFields()}).
 * @param pivotField   Registry column the pivot was performed on.
 * @param pivotValue   Raw pivot value bound into the {@code CASE WHEN}.
 * @param pivotLabel   Display label rendered as the secondary header.
 * @param aggFunc      Aggregation token applied inside the bucket.
 * @param valueField   Registry column the aggregation targets.
 */
public record PivotResultColumnDto(
        String field,
        String pivotField,
        String pivotValue,
        String pivotLabel,
        String aggFunc,
        String valueField) {
}
