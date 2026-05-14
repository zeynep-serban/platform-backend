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
        List<PivotResultColumnDto> pivotResultColumns) {

    public PagedResultDto(List<T> items, long total, int page, int pageSize) {
        this(items, total, page, pageSize, List.of(), List.of());
    }

    public PagedResultDto(List<T> items, long total, int page, int pageSize,
                          List<String> pivotResultFields) {
        this(items, total, page, pageSize, pivotResultFields, List.of());
    }

    public PagedResultDto {
        pivotResultFields = pivotResultFields == null
                ? List.of()
                : List.copyOf(pivotResultFields);
        pivotResultColumns = pivotResultColumns == null
                ? List.of()
                : List.copyOf(pivotResultColumns);
    }
}
