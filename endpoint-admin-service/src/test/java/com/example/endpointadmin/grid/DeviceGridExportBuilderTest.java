package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.ExportMode;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.GridSql;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the export/preflight SQL builders (#1154 PR-2b): the
 * raw vs view query shape, the bounded count preflight, and the export
 * column resolution. No database — assert on the generated SQL/params.
 */
class DeviceGridExportBuilderTest {

    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DeviceGridQueryBuilder builder() {
        return new DeviceGridQueryBuilder("endpoint_admin_service", 200, 200, 200);
    }

    // ───────────────────────── column resolution ─────────────────────────

    @Test
    void rawResolvesAllCanonicalColumns() {
        List<GridColumn> cols = builder().resolveExportColumns(ExportMode.RAW, List.of("hostname"));
        // RAW ignores requested columns; exports every canonical column.
        assertThat(cols).hasSize(DeviceGridColumns.all().size());
    }

    @Test
    void viewResolvesRequestedAllowlistedColumns_inOrder() {
        List<GridColumn> cols = builder().resolveExportColumns(
                ExportMode.VIEW, List.of("status", "hostname"));
        assertThat(cols).extracting(GridColumn::colId).containsExactly("status", "hostname");
    }

    @Test
    void viewWithEmptyColumns_fallsBackToAll() {
        List<GridColumn> cols = builder().resolveExportColumns(ExportMode.VIEW, List.of());
        assertThat(cols).hasSize(DeviceGridColumns.all().size());
    }

    @Test
    void viewWithUnknownColumn_rejected() {
        assertThatThrownBy(() -> builder().resolveExportColumns(ExportMode.VIEW, List.of("evil")))
                .isInstanceOf(GridQueryValidationException.class)
                .satisfies(t -> assertThat(((GridQueryValidationException) t).getCode())
                        .isEqualTo(DeviceGridQueryBuilder.CODE_INVALID_FILTER));
    }

    // ───────────────────────── export query ─────────────────────────

    @Test
    void rawExportQuery_isTenantOnly_canonicalOrder_noLimit() {
        DeviceGridQueryBuilder b = builder();
        GridSql q = b.buildExportQuery(TENANT, ExportMode.RAW,
                new DeviceGridExportRequest("csv", "raw", null, null, null, null),
                DeviceGridColumns.all());
        assertThat(q.sql())
                .contains("endpoint_admin_service.endpoint_devices d")
                .contains("WHERE d.tenant_id = :tenantId")
                .contains("ORDER BY d.id ASC")
                // No pagination on an export (the LATERAL subqueries still
                // carry their own LIMIT 1, so only OFFSET/:__limit are absent).
                .doesNotContain("OFFSET")
                .doesNotContain(":__limit");
        assertThat(q.params().getValue("tenantId")).isEqualTo(TENANT);
        // Canonical projection exposes every column id.
        for (String colId : DeviceGridColumns.allColumnIds()) {
            assertThat(q.sql()).contains(" AS " + colId);
        }
    }

    @Test
    void viewExportQuery_appliesFilterSort_subsetColumns_noLimit() {
        DeviceGridQueryBuilder b = builder();
        DeviceGridExportRequest req = new DeviceGridExportRequest("xlsx", "view",
                Map.of("status", Map.of("filterType", "set", "values", List.of("ONLINE"))),
                List.of(Map.of("colId", "hostname", "sort", "desc")),
                null, List.of("hostname", "status"));
        GridSql q = b.buildExportQuery(TENANT, ExportMode.VIEW, req,
                b.resolveExportColumns(ExportMode.VIEW, req.columns()));

        assertThat(q.sql())
                .contains("d.status IN (:p0)")
                .contains("ORDER BY d.hostname DESC, d.id ASC")
                .doesNotContain("OFFSET")
                .doesNotContain(":__limit");
        // Only the requested columns are projected.
        assertThat(q.sql()).contains("d.hostname AS hostname").contains("d.status AS status");
        assertThat(q.sql()).doesNotContain(" AS agent_version");
        assertThat(q.params().getValue("p0")).isEqualTo("ONLINE");
    }

    // ───────────────────────── preflight ─────────────────────────

    @Test
    void rawPreflight_isBoundedCount_capPlusOne() {
        GridSql q = builder().buildCountPreflight(TENANT, ExportMode.RAW,
                new DeviceGridExportRequest("csv", "raw", null, null, null, null), 50000);
        assertThat(q.sql())
                .contains("SELECT count(*) FROM (SELECT 1")
                .contains("endpoint_admin_service.endpoint_devices d")
                .contains("WHERE d.tenant_id = :tenantId")
                .contains("LIMIT :__cap) preflight");
        assertThat(q.params().getValue("__cap")).isEqualTo(50001);
    }

    @Test
    void viewPreflight_includesFilter() {
        DeviceGridExportRequest req = new DeviceGridExportRequest("csv", "view",
                Map.of("hostname", Map.of("filterType", "text", "type", "contains", "filter", "lab")),
                null, "lab", null);
        GridSql q = builder().buildCountPreflight(TENANT, ExportMode.VIEW, req, 10);
        assertThat(q.sql())
                .contains("SELECT count(*) FROM (SELECT 1")
                .contains("lower(d.hostname) LIKE :p0 ESCAPE '\\'")
                .contains("LIMIT :__cap) preflight");
        assertThat(q.params().getValue("__cap")).isEqualTo(11);
    }
}
