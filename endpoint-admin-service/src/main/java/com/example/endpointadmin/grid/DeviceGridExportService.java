package com.example.endpointadmin.grid;

import com.example.commonexport.CsvStreamingExporter;
import com.example.commonexport.ExcelStreamingExporter;
import com.example.commonexport.ExportColumn;
import com.example.commonexport.ExportQuery;
import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.ExportMode;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.GridSql;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Report-style streaming export for the endpoint-device grid (#1154 PR-2b).
 * Reuses the shared {@code common-export} streaming exporters so the device
 * grid produces the exact same CSV/Excel output contract as the reporting
 * module.
 *
 * <p>{@link #prepareExport} runs the cap preflight (refuses an over-cap
 * export with {@code 422} — never a silent truncation) and writes the audit
 * event, BOTH before any byte is streamed. {@link #writeTo} then streams the
 * full result set. NOT {@code @Transactional}: the audit commits in its own
 * transaction (see {@link DeviceGridExportAuditService}) and the streaming
 * query needs no session.
 */
@Service
public class DeviceGridExportService {

    private static final String CSV = "csv";
    private static final String XLSX = "xlsx";

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceGridQueryBuilder builder;
    private final DeviceGridExportAuditService auditService;
    private final int exportMax;

    public DeviceGridExportService(NamedParameterJdbcTemplate jdbc,
                                   DeviceGridQueryBuilder builder,
                                   DeviceGridExportAuditService auditService,
                                   @Value("${endpoint-admin.export.max:50000}") int exportMax) {
        this.jdbc = jdbc;
        this.builder = builder;
        this.auditService = auditService;
        this.exportMax = exportMax;
    }

    /** Everything the controller needs to stream the response. */
    public record ExportPlan(GridSql query, List<ExportColumn> columns,
                             String format, String filename, String contentType) {}

    /**
     * Validate, preflight (cap), and audit — all before streaming. Throws
     * {@link GridQueryValidationException} (→ 400) for a bad format/mode/
     * column, or {@link ExportRowLimitExceededException} (→ 422) when the
     * dataset exceeds the cap.
     */
    public ExportPlan prepareExport(UUID tenantId, String subject, DeviceGridExportRequest req) {
        String format = normalizeFormat(req.format());
        ExportMode mode = parseMode(req.exportMode());
        List<GridColumn> cols = builder.resolveExportColumns(mode, req.columns());

        // Build (and thereby FULLY validate filter + sort + quick-filter) the
        // export query BEFORE the preflight/audit. The count preflight alone
        // does NOT validate sortModel (a count needs no ORDER BY), so an
        // invalid VIEW sort must be rejected here — never recorded as an
        // export request that was actually refused (Codex 019e7e65).
        GridSql query = builder.buildExportQuery(tenantId, mode, req, cols);

        // Bounded preflight: refuse over-cap before producing any bytes.
        GridSql preflight = builder.buildCountPreflight(tenantId, mode, req, exportMax);
        Long matched = jdbc.queryForObject(preflight.sql(), preflight.params(), Long.class);
        long rowCount = matched == null ? 0L : matched;
        if (rowCount > exportMax) {
            throw new ExportRowLimitExceededException(exportMax);
        }

        // Audit AFTER all validation + the cap check, BEFORE the stream
        // (commits in its own transaction).
        String modeLabel = mode.name().toLowerCase(Locale.ROOT);
        auditService.recordExportRequested(tenantId, subject, format, modeLabel, rowCount, cols.size());

        List<ExportColumn> exportColumns = cols.stream()
                .map(c -> new ExportColumn(c.colId(), c.header()))
                .toList();
        String filename = "endpoint-devices-" + modeLabel + "." + format;
        String contentType = CSV.equals(format)
                ? "text/csv; charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return new ExportPlan(query, exportColumns, format, filename, contentType);
    }

    /** Stream the prepared export to {@code out} via the shared exporter. */
    public void writeTo(ExportPlan plan, OutputStream out) {
        ExportQuery q = new ExportQuery(plan.query().sql(), plan.query().params());
        if (XLSX.equals(plan.format())) {
            ExcelStreamingExporter.exportWithColumns(jdbc, q, plan.columns(), "Cihazlar", out);
        } else {
            CsvStreamingExporter.exportWithColumns(jdbc, q, plan.columns(), out);
        }
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return CSV;
        }
        String f = format.trim().toLowerCase(Locale.ROOT);
        if (CSV.equals(f)) {
            return CSV;
        }
        if (XLSX.equals(f) || "excel".equals(f)) {
            return XLSX;
        }
        throw new GridQueryValidationException("INVALID_EXPORT_FORMAT",
                "format must be 'csv' or 'xlsx'");
    }

    private ExportMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new GridQueryValidationException("INVALID_EXPORT_MODE",
                    "exportMode is required ('raw' or 'view')");
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if ("raw".equals(m)) {
            return ExportMode.RAW;
        }
        if ("view".equals(m)) {
            return ExportMode.VIEW;
        }
        throw new GridQueryValidationException("INVALID_EXPORT_MODE",
                "exportMode must be 'raw' or 'view'");
    }
}
