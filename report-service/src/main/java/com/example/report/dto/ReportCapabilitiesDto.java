package com.example.report.dto;

/**
 * Per-report capability flags surfaced in {@link ReportMetadataDto}.
 *
 * <p>The frontend uses these flags to decide whether to expose UI affordances
 * (row group panel, drag-to-group, aggregation pickers). When a flag is
 * {@code false}, the corresponding entry-points stay hidden, mirroring the
 * stop-gap behavior shipped by platform-web PR #271.
 *
 * <p><b>PR-0.1</b>: only {@code serverSideGrouping} is defined; defaults to
 * {@code false} for every report (the metadata builder hard-codes false).
 * <br><b>PR-0.2+</b>: the report registry will own the flag (per-report
 * opt-in) once SQL GROUP BY + tested aggregation lands.
 *
 * @param serverSideGrouping  When true, the backend honors {@code rowGroupCols
 *                            + groupKeys + valueCols} on POST {@code /query};
 *                            when false, those fields are rejected with HTTP
 *                            400 and the frontend hides every grouping
 *                            entry-point.
 */
public record ReportCapabilitiesDto(boolean serverSideGrouping) {}
