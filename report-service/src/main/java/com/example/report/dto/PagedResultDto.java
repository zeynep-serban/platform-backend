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
 * for {@code pivotMode=true}. Non-pivot responses omit the field via
 * {@code @JsonInclude(NON_EMPTY)} so the existing flat / grouped paths
 * keep their byte-for-byte response shape.
 */
public record PagedResultDto<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> pivotResultFields) {

    public PagedResultDto(List<T> items, long total, int page, int pageSize) {
        this(items, total, page, pageSize, List.of());
    }

    public PagedResultDto {
        pivotResultFields = pivotResultFields == null
                ? List.of()
                : List.copyOf(pivotResultFields);
    }
}
