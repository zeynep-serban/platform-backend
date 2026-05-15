package com.example.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Paged response envelope returned from {@code POST /api/v1/reports/{key}/query}.
 *
 * <p>PR-0.4b (Codex thread {@code 019e2695}): the response carries an
 * optional {@code pivotResultFields} list that mirrors the SQL aliases
 * emitted by {@code SqlBuilder.buildPivotedGroupedQuery}. AG Grid SSRM
 * uses this list to register secondary columns when the request asked
 * for {@code pivotMode=true}.
 *
 * <p>PR-0.4d-be (same Codex thread): {@code pivotResultColumns} adds the
 * alias-aligned semantic metadata frontend needs to render the
 * user-facing secondary column header (pivot field/value/label, agg
 * func, value field). The list shares the same ordering as
 * {@code pivotResultFields} — entry {@code i} of both refers to the
 * same SQL alias — and the backend asserts that invariant at
 * construction.
 *
 * <p>Non-pivot responses omit both fields via
 * {@code @JsonInclude(NON_EMPTY)} so the existing flat / grouped paths
 * keep their byte-for-byte response shape.
 */
public record PagedResultDto<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> pivotResultFields,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<PivotResultColumnDto> pivotResultColumns,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        java.util.Map<String, Object> grandTotalRow) {

    public PagedResultDto(List<T> items, long total, int page, int pageSize) {
        this(items, total, page, pageSize, List.of(), List.of(), null);
    }

    public PagedResultDto(List<T> items, long total, int page, int pageSize,
                          List<String> pivotResultFields) {
        this(items, total, page, pageSize, pivotResultFields, List.of(), null);
    }

    public PagedResultDto(List<T> items, long total, int page, int pageSize,
                          List<String> pivotResultFields,
                          List<PivotResultColumnDto> pivotResultColumns) {
        this(items, total, page, pageSize, pivotResultFields, pivotResultColumns, null);
    }

    public PagedResultDto {
        pivotResultFields = pivotResultFields == null
                ? List.of()
                : List.copyOf(pivotResultFields);
        pivotResultColumns = pivotResultColumns == null
                ? List.of()
                : List.copyOf(pivotResultColumns);
        // PR-0.5a (Codex thread 019e2c61): grand total row is
        // controller-gated to root grouped requests; defensive
        // immutability + empty-collapse-to-null so the
        // @JsonInclude(NON_NULL) field stays omitted on the
        // dominant non-grouped / child-store / pivot paths.
        //
        // Codex iter post-impl §High (thread 019e2c61): JDBC
        // aggregate rows legitimately carry NULL values (empty
        // filter set → SUM/AVG/STDEV null; weightedavg denominator
        // zero → null; percentile over empty set → null). Map.copyOf
        // rejects null values → NPE in canonical constructor would
        // break the dominant grouped flow. Use null-preserving
        // immutable wrapper instead.
        grandTotalRow = immutableNullableValueMap(grandTotalRow);
    }

    /**
     * Null-preserving immutable view: {@link java.util.Map#copyOf}
     * rejects null values, but JDBC aggregate rows legitimately
     * carry nulls (empty filter set, weightedavg denominator zero,
     * percentile over empty set, etc.). We need an immutable
     * wrapper that tolerates nulls so the canonical constructor
     * cannot NPE on grand-total responses. Empty maps collapse to
     * {@code null} so the {@code @JsonInclude(NON_NULL)} annotation
     * can omit the field entirely.
     */
    private static java.util.Map<String, Object> immutableNullableValueMap(
            java.util.Map<String, Object> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(in));
    }
}
