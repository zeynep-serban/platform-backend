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
                .contains("WHERE d.tenant_id = :tenantId")
                .contains("ORDER BY d.id ASC")
                .contains("LIMIT :__limit OFFSET :__offset");
        // Latest-per-device ordering inside the lateral.
        assertThat(q.sql()).contains("ORDER BY hs.collected_at DESC, hs.created_at DESC, hs.id DESC");
        // Overfetch by one row for lastRow detection.
        assertThat(q.params().getValue("__limit")).isEqualTo(51);
        assertThat(q.params().getValue("__offset")).isEqualTo(0);
        assertThat(q.params().getValue("tenantId")).isEqualTo(TENANT);
        assertThat(q.pageSize()).isEqualTo(50);
        assertThat(q.startRow()).isZero();
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
}
