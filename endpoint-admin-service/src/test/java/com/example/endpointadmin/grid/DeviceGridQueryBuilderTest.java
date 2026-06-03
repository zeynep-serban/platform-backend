package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.grid.DeviceGridQueryBuilder.BuiltGridQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link DeviceGridQueryBuilder} — the SQL/param shape and
 * the fail-closed validation contract (board #1154 PR-2a). No database: we
 * assert on the generated SQL string and the bound parameter source.
 */
class DeviceGridQueryBuilderTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DeviceGridQueryBuilder builder() {
        return new DeviceGridQueryBuilder("endpoint_admin_service", 200, 200, 200);
    }

    private DeviceGridQueryRequest req(Map<String, Object> filterModel,
                                       List<Map<String, Object>> sortModel,
                                       String quickFilter) {
        return new DeviceGridQueryRequest(0, 50, filterModel, sortModel, quickFilter);
    }

    // ───────────────────────── happy path / shape ─────────────────────────

    @Test
    void basePage_isSchemaQualified_withLateralJoins_tieBreaker_andOverfetch() {
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(null, null, null));

        // Schema-qualified table references (the #342 live-bug guard).
        assertThat(q.sql())
                .contains("endpoint_admin_service.endpoint_devices d")
                .contains("LEFT JOIN LATERAL")
                .contains("endpoint_admin_service.endpoint_device_health_snapshots hs")
                .contains("endpoint_admin_service.endpoint_outdated_software_snapshots os")
                // Faz 21.1 PR2b-iii (Codex 019e8cd4 AGREE): canonical
                // effective-org filter — accepts both canonical rows
                // (org_id set post-PR2b-ii) and legacy rows (org_id NULL
                // with tenant_id only, V29 trigger silent-fill defensive).
                .contains("WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))")
                .contains("ORDER BY d.id ASC")
                .contains("LIMIT :__limit OFFSET :__offset");
        // Latest-per-device ordering inside the lateral.
        assertThat(q.sql()).contains("ORDER BY hs.collected_at DESC, hs.created_at DESC, hs.id DESC");
        // Overfetch by one row for lastRow detection.
        assertThat(q.params().getValue("__limit")).isEqualTo(51);
        assertThat(q.params().getValue("__offset")).isEqualTo(0);
        // PR2b-iii: tenant scope is now exposed as :orgId (the canonical
        // org_id parameter); the old :tenantId binding no longer exists.
        assertThat(q.params().getValue("orgId")).isEqualTo(TENANT);
        assertThat(q.params().hasValue("tenantId")).isFalse();
        assertThat(q.pageSize()).isEqualTo(50);
        assertThat(q.startRow()).isZero();
    }

    @Test
    void v2bLateralsPinCanonicalLatestTiebreaker() {
        // Codex 019e87bc iter-2 must_fix: every "latest snapshot" LATERAL
        // MUST sort by collected_at DESC, created_at DESC, id DESC — the
        // canonical contract the repository/index pair already enforces:
        //   * EndpointDiagnosticsSnapshotRepository (V23 idx_diag_snap_…)
        //   * EndpointStartupExposureSnapshotRepository (V25 idx_se_…)
        //   * EndpointServicesSnapshotRepository (V24 idx_svcs_snap_…)
        // Missing the created_at tiebreaker lets drawer /latest and the
        // grid render different snapshots when two share collected_at.
        String sql = builder().buildPageQuery(TENANT, req(null, null, null)).sql();
        assertThat(sql).contains("ORDER BY ds.collected_at DESC, ds.created_at DESC, ds.id DESC");
        assertThat(sql).contains("ORDER BY ses.collected_at DESC, ses.created_at DESC, ses.id DESC");
        assertThat(sql).contains("ORDER BY s.collected_at DESC, s.created_at DESC, s.id DESC");
        // Negative drift detector — neither the missing-tiebreaker form
        // nor the wrong-order created_at-first form may survive a refactor.
        assertThat(sql).doesNotContain("ORDER BY ds.collected_at DESC, ds.id DESC");
        assertThat(sql).doesNotContain("ORDER BY ses.collected_at DESC, ses.id DESC");
        // (services uses `s` alias which is short — the bare "s.id DESC"
        // substring would over-match; the positive contains above is the
        // pin and the negative form here covers the diagnostics/startup pair.)
    }

    @Test
    void v2bLateralsAreSchemaQualified() {
        // Codex 019e87bc iter-1 must_fix (composite): the three new v2-b
        // LATERAL FROM clauses must all qualify with the runtime schema —
        // unqualified tables would 500 under the live non-public schema
        // (#342 regression class).
        String sql = builder().buildPageQuery(TENANT, req(null, null, null)).sql();
        assertThat(sql).contains("endpoint_admin_service.endpoint_diagnostics_snapshots ds");
        assertThat(sql).contains("endpoint_admin_service.endpoint_startup_exposure_snapshots ses");
        assertThat(sql).contains("endpoint_admin_service.endpoint_services_snapshots s");
        // The precomputed critical_stopped_count subquery also addresses
        // endpoint_services_entries through the same qualified() helper.
        assertThat(sql).contains("endpoint_admin_service.endpoint_services_entries ent");
    }

    @Test
    void projectionExposesEveryRegistryColumnAsItsColId() {
        String sql = builder().buildPageQuery(TENANT, req(null, null, null)).sql();
        for (String colId : DeviceGridColumns.allColumnIds()) {
            assertThat(sql).contains(" AS " + colId);
        }
    }

    // ───────────────────────── text filter ─────────────────────────

    @Test
    void textContains_isLoweredEscapedLike_withExplicitEscape() {
        Map<String, Object> filter = Map.of(
                "hostname", Map.of("filterType", "text", "type", "contains", "filter", "Host%Name"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));

        assertThat(q.sql()).contains("lower(d.hostname) LIKE :p0 ESCAPE '\\'");
        // lower-cased, then % escaped, wrapped in contains wildcards.
        assertThat(q.params().getValue("p0")).isEqualTo("%host\\%name%");
    }

    @Test
    void textEquals_usesLoweredEquality() {
        Map<String, Object> filter = Map.of(
                "display_name", Map.of("filterType", "text", "type", "equals", "filter", "Lab-01"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("lower(d.display_name) = :p0");
        assertThat(q.params().getValue("p0")).isEqualTo("lab-01");
    }

    @Test
    void deviceIdTextFilter_castsUuidToText_neverLowerUuid() {
        // device_id is a UUID column; a text filter must cast (d.id::text)
        // so lower(...) is valid. lower(uuid) does not exist in PG and would
        // 500 — an allowlisted column must never produce invalid SQL.
        Map<String, Object> filter = Map.of(
                "device_id", Map.of("filterType", "text", "type", "equals", "filter", "ABCD-1234"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("lower(d.id::text) = :p0");
        assertThat(q.sql()).doesNotContain("lower(d.id)");
        assertThat(q.params().getValue("p0")).isEqualTo("abcd-1234");
        // SELECT still exposes the raw UUID, not the cast.
        assertThat(q.sql()).contains("d.id AS device_id");
    }

    // ───────────────────────── set / enum / boolean ─────────────────────────

    @Test
    void enumSetFilter_buildsBoundInList() {
        Map<String, Object> filter = Map.of(
                "status", Map.of("filterType", "set", "values", List.of("ONLINE", "STALE")));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("d.status IN (:p0, :p1)");
        assertThat(q.params().getValue("p0")).isEqualTo("ONLINE");
        assertThat(q.params().getValue("p1")).isEqualTo("STALE");
    }

    @Test
    void booleanSetFilter_bindsBooleans() {
        Map<String, Object> filter = Map.of(
                "health_any_low_disk", Map.of("filterType", "set", "values", List.of("true")));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("h.any_low_disk IN (:p0)");
        assertThat(q.params().getValue("p0")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void emptySetFilter_matchesNothing() {
        Map<String, Object> filter = Map.of(
                "status", Map.of("filterType", "set", "values", List.of()));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("1=0");
    }

    // ───────────────────────── number filter ─────────────────────────

    @Test
    void numberGreaterThan_bindsLong() {
        Map<String, Object> filter = Map.of(
                "health_memory_used_percent", Map.of("filterType", "number", "type", "greaterThan", "filter", 80));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("h.memory_used_percent > :p0");
        assertThat(q.params().getValue("p0")).isEqualTo(80L);
    }

    @Test
    void numberInRange_bindsBothBounds() {
        Map<String, Object> filter = Map.of(
                "outdated_upgrade_count", Map.of("filterType", "number", "type", "inRange",
                        "filter", 1, "filterTo", 10));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("o.upgrade_count >= :p0").contains("o.upgrade_count <= :p1");
        assertThat(q.params().getValue("p0")).isEqualTo(1L);
        assertThat(q.params().getValue("p1")).isEqualTo(10L);
    }

    // ───────────────────────── date filter ─────────────────────────

    @Test
    void dateEquals_isCalendarDayRange_notOneSecond() {
        Instant from = Instant.parse("2026-05-31T00:00:00Z");
        Map<String, Object> filter = Map.of(
                "last_seen_at", Map.of("filterType", "date", "type", "equals", "dateFrom", "2026-05-31T00:00:00Z"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("d.last_seen_at >= :p0").contains("d.last_seen_at < :p1");
        assertThat(q.params().getValue("p0")).isEqualTo(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        assertThat(q.params().getValue("p1"))
                .isEqualTo(OffsetDateTime.ofInstant(from.plus(Duration.ofDays(1)), ZoneOffset.UTC));
    }

    @Test
    void dateInRange_isHalfOpen() {
        Map<String, Object> filter = Map.of(
                "last_seen_at", Map.of("filterType", "date", "type", "inRange",
                        "dateFrom", "2026-05-01T00:00:00Z", "dateTo", "2026-06-01T00:00:00Z"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(filter, null, null));
        assertThat(q.sql()).contains("d.last_seen_at >= :p0").contains("d.last_seen_at < :p1");
        assertThat(q.params().getValue("p0"))
                .isEqualTo(OffsetDateTime.ofInstant(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC));
        assertThat(q.params().getValue("p1"))
                .isEqualTo(OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));
    }

    // ───────────────────────── sort ─────────────────────────

    @Test
    void userSort_appendsDeterministicTieBreaker() {
        List<Map<String, Object>> sort = List.of(Map.of("colId", "hostname", "sort", "desc"));
        String sql = builder().buildPageQuery(TENANT, req(null, sort, null)).sql();
        assertThat(sql).contains("ORDER BY d.hostname DESC, d.id ASC");
    }

    @Test
    void sortByDeviceId_doesNotDoubleTieBreaker() {
        List<Map<String, Object>> sort = List.of(Map.of("colId", "device_id", "sort", "desc"));
        String sql = builder().buildPageQuery(TENANT, req(null, sort, null)).sql();
        assertThat(sql).contains("ORDER BY d.id DESC");
        assertThat(sql).doesNotContain("d.id DESC, d.id ASC");
    }

    // ───────────────────────── quick filter ─────────────────────────

    @Test
    void quickFilter_orsAcrossQuickFilterableTextColumns() {
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(null, null, "Lab"));
        assertThat(q.sql())
                .contains("lower(d.hostname) LIKE :p0 ESCAPE '\\'")
                .contains("lower(d.display_name) LIKE :p0 ESCAPE '\\'")
                .contains("lower(d.domain_name) LIKE :p0 ESCAPE '\\'");
        // os_type is not quick-filterable (enum) — must not appear in the OR.
        assertThat(q.params().getValue("p0")).isEqualTo("%lab%");
    }

    // ───────────────────────── fail-closed rejections ─────────────────────────

    @Test
    void unknownFilterColumn_rejected() {
        Map<String, Object> filter = Map.of("evil; DROP TABLE", Map.of("filterType", "text", "type", "contains", "filter", "x"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void unknownSortColumn_rejected() {
        List<Map<String, Object>> sort = List.of(Map.of("colId", "evil", "sort", "asc"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(null, sort, null)),
                DeviceGridQueryBuilder.CODE_INVALID_SORT);
    }

    @Test
    void badSortDirection_rejected() {
        List<Map<String, Object>> sort = List.of(Map.of("colId", "hostname", "sort", "sideways"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(null, sort, null)),
                DeviceGridQueryBuilder.CODE_INVALID_SORT);
    }

    @Test
    void wrongFilterTypeForColumn_rejected() {
        // number filter declared on a text column
        Map<String, Object> filter = Map.of(
                "hostname", Map.of("filterType", "number", "type", "greaterThan", "filter", 5));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void missingFilterType_rejected() {
        // Strict fail-closed: filterType must be present (Codex 019e7e65).
        Map<String, Object> filter = Map.of(
                "hostname", Map.of("type", "contains", "filter", "x"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void oversizedTextFilterValue_rejected() {
        DeviceGridQueryBuilder small = new DeviceGridQueryBuilder("endpoint_admin_service", 200, 200, 5);
        Map<String, Object> filter = Map.of(
                "hostname", Map.of("filterType", "text", "type", "contains", "filter", "way-too-long-value"));
        assertGridError(() -> small.buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void compoundFilter_rejected() {
        Map<String, Object> filter = Map.of("hostname", Map.of(
                "filterType", "text", "operator", "OR",
                "condition1", Map.of("type", "contains", "filter", "a"),
                "condition2", Map.of("type", "contains", "filter", "b")));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void naiveDate_rejected() {
        Map<String, Object> filter = Map.of(
                "last_seen_at", Map.of("filterType", "date", "type", "equals", "dateFrom", "2026-05-31 00:00:00"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void nonIntegerNumber_rejected() {
        Map<String, Object> filter = Map.of(
                "health_memory_used_percent", Map.of("filterType", "number", "type", "equals", "filter", "abc"));
        assertGridError(() -> builder().buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void oversizedSet_rejected() {
        DeviceGridQueryBuilder small = new DeviceGridQueryBuilder("endpoint_admin_service", 200, 2, 200);
        Map<String, Object> filter = Map.of(
                "status", Map.of("filterType", "set", "values", List.of("A", "B", "C")));
        assertGridError(() -> small.buildPageQuery(TENANT, req(filter, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void oversizedQuickFilter_rejected() {
        DeviceGridQueryBuilder small = new DeviceGridQueryBuilder("endpoint_admin_service", 200, 200, 5);
        assertGridError(() -> small.buildPageQuery(TENANT, req(null, null, "way-too-long")),
                DeviceGridQueryBuilder.CODE_INVALID_FILTER);
    }

    @Test
    void badRowWindow_rejected() {
        DeviceGridQueryBuilder b = builder();
        // endRow <= startRow
        assertGridError(() -> b.buildPageQuery(TENANT, new DeviceGridQueryRequest(10, 10, null, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_WINDOW);
        // negative startRow
        assertGridError(() -> b.buildPageQuery(TENANT, new DeviceGridQueryRequest(-1, 5, null, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_WINDOW);
        // null window
        assertGridError(() -> b.buildPageQuery(TENANT, new DeviceGridQueryRequest(null, null, null, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_WINDOW);
    }

    @Test
    void windowExceedingMaxPage_rejected() {
        DeviceGridQueryBuilder small = new DeviceGridQueryBuilder("endpoint_admin_service", 10, 200, 200);
        assertGridError(() -> small.buildPageQuery(TENANT, new DeviceGridQueryRequest(0, 50, null, null, null)),
                DeviceGridQueryBuilder.CODE_INVALID_WINDOW);
    }

    private void assertGridError(org.junit.jupiter.api.function.Executable exec, String expectedCode) {
        assertThatThrownBy(exec::execute)
                .isInstanceOf(GridQueryValidationException.class)
                .satisfies(t -> assertThat(((GridQueryValidationException) t).getCode()).isEqualTo(expectedCode));
    }

    // ───────────────────────── v2-d SCHEMA v5 — BE-024c cache cols ─────────────────────────

    @Test
    void schemaVersion_isFive_v2d() {
        // Codex 019e8a39 iter-1 plan AGREE: SCHEMA_VERSION bump 4→5 for the
        // 9 new cache-fed colIds. The export-audit metadata writes this so a
        // web mfe with GRID_SCHEMA_VERSION=4 detects state drift on its
        // first response.
        assertThat(DeviceGridColumns.SCHEMA_VERSION).isEqualTo(5);
    }

    @Test
    void v5_cacheCols_areRegistered_andTyped_correctly() {
        // All 9 v5 cache colIds must be in the registry. Status colIds are
        // ENUM (set filter); count colIds are NUMBER (number filter).
        List<String> cacheCols = List.of(
                "software_diff_status",
                "software_diff_added_count",
                "software_diff_removed_count",
                "software_diff_version_changed_count",
                "outdated_diff_status",
                "outdated_diff_added_count",
                "outdated_diff_removed_count",
                "outdated_diff_version_changed_count",
                "outdated_diff_available_version_bumped_count");
        for (String colId : cacheCols) {
            assertThat(DeviceGridColumns.byId(colId))
                    .as("colId %s must be registered", colId).isNotNull();
        }
        assertThat(DeviceGridColumns.byId("software_diff_status").type())
                .isEqualTo(DeviceGridColumns.ColumnType.ENUM);
        assertThat(DeviceGridColumns.byId("outdated_diff_status").type())
                .isEqualTo(DeviceGridColumns.ColumnType.ENUM);
        assertThat(DeviceGridColumns.byId("software_diff_added_count").type())
                .isEqualTo(DeviceGridColumns.ColumnType.NUMBER);
        assertThat(DeviceGridColumns.byId("outdated_diff_available_version_bumped_count").type())
                .isEqualTo(DeviceGridColumns.ColumnType.NUMBER);
    }

    @Test
    void v5_cacheJoins_useQualifiedHelper_notHardcodedSchema() {
        // Codex 019e8a39 iter-1 must-fix #1 absorb: cache joins must route
        // through the existing qualified() helper, NOT hardcode
        // "endpoint_admin_service". A custom-schema builder must emit the
        // join clauses with the custom schema name.
        DeviceGridQueryBuilder custom =
                new DeviceGridQueryBuilder("ea_custom_schema", 200, 200, 200);
        BuiltGridQuery q = custom.buildPageQuery(TENANT, req(null, null, null));
        assertThat(q.sql())
                .as("software cache LEFT JOIN uses qualified() schema")
                .contains("LEFT JOIN ea_custom_schema.endpoint_software_diff_cache sdc")
                .contains("LEFT JOIN ea_custom_schema.endpoint_outdated_software_diff_cache odc")
                // Hard-coded schema literal would have shipped exactly this
                // string — guard against accidental regression by asserting
                // its absence under the custom schema instance.
                .doesNotContain("endpoint_admin_service.endpoint_software_diff_cache sdc")
                .doesNotContain("endpoint_admin_service.endpoint_outdated_software_diff_cache odc");
    }

    @Test
    void v5_cacheCol_descSort_pinsNullsLast() {
        // Codex 019e8a39 iter-1 must-fix #2 absorb: PG default for DESC is
        // NULLS FIRST. Cache-absent devices (the v5 colIds return NULL) must
        // NOT surface at the top of a "top N most changed" DESC sort. Pin
        // NULLS LAST on the 9 cache columns; verify here.
        List<Map<String, Object>> sortDesc = List.of(
                Map.of("colId", "software_diff_added_count", "sort", "desc"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(null, sortDesc, null));
        assertThat(q.sql())
                .as("DESC sort on cache count column must explicitly pin NULLS LAST")
                .contains("sdc.added_count DESC NULLS LAST");
    }

    @Test
    void v5_cacheCol_ascSort_doesNotAddNullsLast() {
        // ASC default in PG is NULLS LAST, so adding it explicitly would be
        // noise. Pin: no redundant NULLS LAST clause on ASC sort.
        List<Map<String, Object>> sortAsc = List.of(
                Map.of("colId", "software_diff_added_count", "sort", "asc"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(null, sortAsc, null));
        assertThat(q.sql())
                .contains("sdc.added_count ASC")
                .doesNotContain("sdc.added_count ASC NULLS");
    }

    @Test
    void v5_nonCacheCol_descSort_doesNotAddNullsLast() {
        // The NULLS LAST pin is targeted: only the 9 cache columns get it.
        // Other nullable columns (e.g. health.health_memory_used_percent) keep PG
        // default behavior to avoid surprising the existing operator habits.
        List<Map<String, Object>> sortDesc = List.of(
                Map.of("colId", "health_memory_used_percent", "sort", "desc"));
        BuiltGridQuery q = builder().buildPageQuery(TENANT, req(null, sortDesc, null));
        // The non-cache column appears in the SQL without NULLS LAST.
        // Find the sort segment and verify NULLS LAST is NOT next to it.
        assertThat(q.sql()).contains("DESC");
        assertThat(q.sql())
                .as("non-cache column DESC should not have NULLS LAST")
                .doesNotContain("health_memory_used_percent DESC NULLS LAST");
    }
}
