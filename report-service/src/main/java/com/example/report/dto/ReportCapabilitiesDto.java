package com.example.report.dto;

import java.util.List;

/**
 * Per-report capability flags surfaced in {@link ReportMetadataDto}.
 *
 * <p>The frontend uses these flags to decide whether to expose UI affordances
 * (row group panel, drag-to-group, aggregation pickers). When a flag is
 * {@code false}, the corresponding entry-points stay hidden, mirroring the
 * stop-gap behavior shipped by platform-web PR #271.
 *
 * <p>PR-0.1 introduced {@code serverSideGrouping} with a hard-coded
 * {@code false}. PR-0.2 derives it from the report's column flags:
 * {@code serverSideGrouping=true} iff at least one column is marked
 * {@code groupable=true}. The two list fields tell the frontend which
 * columns are valid drop-targets for the row-group panel and the
 * value-aggregation picker so it doesn't need to re-derive that from
 * the column metadata.
 *
 * @param serverSideGrouping  When true, the backend honors {@code rowGroupCols
 *                            + groupKeys + valueCols} on POST {@code /query};
 *                            when false, those fields are rejected with HTTP
 *                            400 and the frontend hides every grouping
 *                            entry-point.
 * @param groupableFields     PR-0.2: column field names the frontend may use
 *                            as a row-group dimension (matches AG Grid's
 *                            {@code enableRowGroup} surface). Empty list
 *                            implies {@code serverSideGrouping=false}.
 * @param aggregatableFields  PR-0.2: column field names the frontend may use
 *                            as a {@code valueCols} candidate (matches AG
 *                            Grid's {@code enableValue} surface). Each entry
 *                            in the list also has a default aggregation
 *                            function declared on the column registry; the
 *                            frontend applies that when the user drops the
 *                            column without picking a function.
 */
public record ReportCapabilitiesDto(
        boolean serverSideGrouping,
        List<String> groupableFields,
        List<String> aggregatableFields) {

    /**
     * Backward-compatible single-bool constructor. Kept so PR-0.1 call
     * sites (which only knew about {@code serverSideGrouping}) continue
     * to compile; PR-0.2 call sites prefer the canonical 3-arg form so
     * the frontend gets the field lists too.
     */
    public ReportCapabilitiesDto(boolean serverSideGrouping) {
        this(serverSideGrouping, List.of(), List.of());
    }
}
