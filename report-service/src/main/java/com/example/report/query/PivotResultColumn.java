package com.example.report.query;

/**
 * PR-0.4d-be (Codex thread {@code 019e2695}): backend-neutral metadata
 * describing one pivot-derived response column. The shape stays free of
 * AG Grid {@code ColDef} types so {@link SqlBuilder} doesn't depend on the
 * web DTO layer; the controller maps these records onto the
 * {@code PagedResultDto.pivotResultColumns} envelope at the HTTP boundary.
 *
 * <p>Frontend uses the metadata to render the user-facing secondary
 * column header without re-deriving it from the SQL alias or fetching
 * metadata again. The {@code field} string is identical to the matching
 * entry in {@link SqlBuilder.PivotedBuiltQuery#pivotResultFields()}; the
 * two lists share the same ordering, and that invariant is asserted in
 * {@link SqlBuilderTest}.
 *
 * @param field        SQL alias the response row carries (same value as
 *                     the matching {@code pivotResultFields[i]}).
 * @param pivotField   Source registry column the pivot was performed on
 *                     (e.g. {@code ACCOUNT_TYPE}).
 * @param pivotValue   Raw registry-declared pivot value (e.g. {@code A}).
 * @param pivotLabel   User-facing display label for the bucket (e.g.
 *                     {@code Aktif}); defaults to {@code pivotValue} when
 *                     the registry leaves it blank.
 * @param aggFunc      Aggregation function applied inside the bucket
 *                     ({@code sum / avg / min / max / count /
 *                     distinctcount / stddev / stddevp}).
 * @param valueField   Source registry column the aggregation targets
 *                     (e.g. {@code AMOUNT}).
 */
public record PivotResultColumn(
        String field,
        String pivotField,
        String pivotValue,
        String pivotLabel,
        String aggFunc,
        String valueField) {

    public PivotResultColumn {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(
                    "PivotResultColumn field must not be blank");
        }
        if (pivotField == null || pivotField.isBlank()) {
            throw new IllegalArgumentException(
                    "PivotResultColumn pivotField must not be blank");
        }
        if (pivotValue == null) {
            throw new IllegalArgumentException(
                    "PivotResultColumn pivotValue must not be null");
        }
        if (aggFunc == null || aggFunc.isBlank()) {
            throw new IllegalArgumentException(
                    "PivotResultColumn aggFunc must not be blank");
        }
        if (valueField == null || valueField.isBlank()) {
            throw new IllegalArgumentException(
                    "PivotResultColumn valueField must not be blank");
        }
        // Label defaults to the SQL value so callers that pass null
        // still get a renderable string for the secondary header.
        if (pivotLabel == null || pivotLabel.isBlank()) {
            pivotLabel = pivotValue;
        }
    }
}
