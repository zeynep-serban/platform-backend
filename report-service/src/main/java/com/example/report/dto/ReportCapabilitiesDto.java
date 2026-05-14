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
        List<String> aggregatableFields,
        boolean serverSidePivoting,
        boolean clientPivotAllowed,
        List<String> pivotableFields) {

    /**
     * PR-0.4a (Codex 019e2695 hybrid pivot design): per-report pivot
     * capability gating. AG Grid's pivot UI is universal but the backend
     * can deliver pivoted SQL on big SSRM reports (server mode) OR the
     * frontend can run AG Grid's native client-side pivot engine on
     * small datasets (client mode). The two flags are independent so a
     * report can opt into one, both, or neither:
     *
     * <ul>
     *   <li>{@code serverSidePivoting=true} → backend's
     *       {@code POST /query} accepts {@code pivotMode=true} +
     *       {@code pivotCols[]} and returns pivot-applied rows with
     *       {@code pivotResultFields}. Required for large SSRM reports
     *       like {@code fin-muhasebe-detay} (136K rows × 33 cols)
     *       where shipping the raw dataset to the browser would blow
     *       the heap and the network.</li>
     *   <li>{@code clientPivotAllowed=true} → frontend may enable AG
     *       Grid's pivot UI even in {@code dataSourceMode='client'}
     *       (raw rows already in browser memory). Sane only when the
     *       report has a hard {@code clientModeMaxRows ≤ 10K} cap and
     *       the column count is modest.</li>
     * </ul>
     *
     * <p>{@code pivotableFields} mirrors the {@code groupableFields} /
     * {@code aggregatableFields} contract — columns the frontend may
     * expose with {@code enablePivot=true}. The backend allowlist is
     * authoritative even when {@code clientPivotAllowed} is on so
     * client-mode UX stays consistent with server-mode contract.
     *
     * @param serverSideGrouping  When true, the backend honors {@code rowGroupCols
     *                            + groupKeys + valueCols} on POST {@code /query};
     *                            when false, those fields are rejected with HTTP
     *                            400 and the frontend hides every grouping
     *                            entry-point.
     * @param groupableFields     PR-0.2: column field names the frontend may use
     *                            as a row-group dimension (matches AG Grid's
     *                            {@code enableRowGroup} surface).
     * @param aggregatableFields  PR-0.2: column field names the frontend may use
     *                            as a {@code valueCols} candidate (matches AG
     *                            Grid's {@code enableValue} surface).
     * @param serverSidePivoting  PR-0.4a: when true, backend pivot SQL path
     *                            (CASE WHEN aggregation) is wired in
     *                            {@code SqlBuilder.buildPivotedGroupedQuery}.
     *                            Required for the SSRM big-dataset pivot
     *                            scenarios.
     * @param clientPivotAllowed  PR-0.4a: when true, frontend may enable AG
     *                            Grid's client-side pivot engine even when
     *                            backend pivot is off. Use only on reports
     *                            with a {@code ≤ 10K row} client-mode cap.
     * @param pivotableFields     PR-0.4a: column field names that may be
     *                            dragged into the pivot column drop zone.
     *                            Authoritative on both modes.
     */
    /**
     * Backward-compatible single-bool constructor. Kept so PR-0.1 call
     * sites (which only knew about {@code serverSideGrouping}) continue
     * to compile.
     */
    public ReportCapabilitiesDto(boolean serverSideGrouping) {
        this(serverSideGrouping, List.of(), List.of(), false, false, List.of());
    }

    /**
     * Backward-compatible 3-arg constructor for PR-0.2 call sites that
     * predate the PR-0.4a pivot flags. Defaults {@code serverSidePivoting}
     * and {@code clientPivotAllowed} to {@code false} so existing reports
     * stay opted-out of pivot until they explicitly enable it in their
     * registry entry.
     */
    public ReportCapabilitiesDto(boolean serverSideGrouping,
                                  List<String> groupableFields,
                                  List<String> aggregatableFields) {
        this(serverSideGrouping, groupableFields, aggregatableFields,
                false, false, List.of());
    }
}
