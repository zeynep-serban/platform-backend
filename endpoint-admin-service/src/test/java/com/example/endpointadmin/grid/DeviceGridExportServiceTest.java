package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.grid.DeviceGridExportService.ExportPlan;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Service-logic coverage for {@link DeviceGridExportService} (#1154 PR-2b):
 * format/mode validation, the cap preflight (422, no silent truncation), the
 * audit-before-stream ordering, and the CSV header path — all with a mocked
 * JDBC + audit so no database is needed.
 */
@ExtendWith(MockitoExtension.class)
class DeviceGridExportServiceTest {

    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SUBJECT = "admin@acik.com";

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private DeviceGridExportAuditService audit;

    private DeviceGridExportService service(int cap) {
        DeviceGridQueryBuilder builder =
                new DeviceGridQueryBuilder("endpoint_admin_service", 200, 200, 200);
        return new DeviceGridExportService(jdbc, builder, audit, cap);
    }

    private DeviceGridExportRequest raw(String format) {
        return new DeviceGridExportRequest(format, "raw", null, null, null, null);
    }

    // ───────────────────────── validation ─────────────────────────

    @Test
    void badFormat_rejected_noPreflightNoAudit() {
        assertThatThrownBy(() -> service(100).prepareExport(TENANT, SUBJECT, raw("pdf")))
                .isInstanceOf(GridQueryValidationException.class)
                .satisfies(t -> assertThat(((GridQueryValidationException) t).getCode())
                        .isEqualTo("INVALID_EXPORT_FORMAT"));
        verify(audit, never()).recordExportRequested(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void missingMode_rejected() {
        DeviceGridExportRequest req = new DeviceGridExportRequest("csv", null, null, null, null, null);
        assertThatThrownBy(() -> service(100).prepareExport(TENANT, SUBJECT, req))
                .isInstanceOf(GridQueryValidationException.class)
                .satisfies(t -> assertThat(((GridQueryValidationException) t).getCode())
                        .isEqualTo("INVALID_EXPORT_MODE"));
    }

    @Test
    void invalidViewSort_rejected_beforePreflightAndAudit() {
        // VIEW + invalid sort direction: the export query is built (and fully
        // validated) before the preflight/audit, so this is a 400 that is
        // NEVER recorded as an export request (Codex 019e7e65).
        DeviceGridExportRequest req = new DeviceGridExportRequest("csv", "view",
                null,
                List.of(Map.of("colId", "hostname", "sort", "sideways")),
                null, null);
        assertThatThrownBy(() -> service(50000).prepareExport(TENANT, SUBJECT, req))
                .isInstanceOf(GridQueryValidationException.class)
                .satisfies(t -> assertThat(((GridQueryValidationException) t).getCode())
                        .isEqualTo(DeviceGridQueryBuilder.CODE_INVALID_SORT));
        // Neither the preflight count nor the audit ran.
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
        verify(audit, never()).recordExportRequested(any(), any(), any(), any(), anyLong(), anyInt());
    }

    // ───────────────────────── cap preflight ─────────────────────────

    @Test
    void overCap_throws422_andDoesNotAudit() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(101L); // > cap 100
        assertThatThrownBy(() -> service(100).prepareExport(TENANT, SUBJECT, raw("csv")))
                .isInstanceOf(ExportRowLimitExceededException.class)
                .satisfies(t -> assertThat(((ExportRowLimitExceededException) t).getLimit()).isEqualTo(100));
        // No silent truncation AND no audit when refused.
        verify(audit, never()).recordExportRequested(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void atCap_isAllowed() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(100L); // == cap, allowed (only > cap is refused)
        ExportPlan plan = service(100).prepareExport(TENANT, SUBJECT, raw("csv"));
        assertThat(plan).isNotNull();
    }

    // ───────────────────────── audit + plan ─────────────────────────

    @Test
    void happyPath_auditsBeforeStream_andBuildsCsvPlan() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(7L);
        ExportPlan plan = service(50000).prepareExport(TENANT, SUBJECT, raw("csv"));

        // Audit recorded the request with the resolved metadata (rowCount from
        // the preflight, columnCount = canonical set) — before any streaming.
        verify(audit).recordExportRequested(TENANT, SUBJECT, "csv", "raw", 7L,
                DeviceGridColumns.all().size());
        assertThat(plan.format()).isEqualTo("csv");
        assertThat(plan.filename()).isEqualTo("endpoint-devices-raw.csv");
        assertThat(plan.contentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(plan.columns()).hasSize(DeviceGridColumns.all().size());
    }

    @Test
    void xlsxPlan_hasWorkbookContentType() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ExportPlan plan = service(50000).prepareExport(TENANT, SUBJECT, raw("xlsx"));
        assertThat(plan.filename()).isEqualTo("endpoint-devices-raw.xlsx");
        assertThat(plan.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    // ───────────────────────── csv header path ─────────────────────────

    @Test
    void writeTo_csv_emitsBomAndTurkishHeaders() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        ExportPlan plan = service(50000).prepareExport(TENANT, SUBJECT, raw("csv"));

        // jdbc.query (the streaming cursor) is unstubbed → no rows; the
        // exporter still writes the BOM + the server-resolved TR header row.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service(50000).writeTo(plan, out);

        byte[] bytes = out.toByteArray();
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        // Turkish headers come from the registry, never from the client.
        assertThat(csv).contains("Bilgisayar Adı").contains("Bellek %")
                .contains("Güncellenebilir Yazılım");
    }
}
