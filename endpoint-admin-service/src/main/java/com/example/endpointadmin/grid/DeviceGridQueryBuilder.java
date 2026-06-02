package com.example.endpointadmin.grid;

import com.example.endpointadmin.grid.DeviceGridColumns.ColumnType;
import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * Builds the schema-qualified native SQL for the endpoint-device grid
 * (board #1154 PR-2a). ONE builder feeds both {@code /query} (SSRM, PR-2a)
 * and {@code /export} (PR-2b) so the two paths can never drift — exactly
 * the report-service lesson.
 *
 * <h3>Schema-qualification (live-bug guard)</h3>
 * The live testai tables live in the non-{@code public}
 * {@code endpoint_admin_service} schema and the connection search_path does
 * not include it; an unqualified native {@code FROM endpoint_devices} 500s
 * with {@code relation ... does not exist} (the #342 live bug). Every table
 * reference here is qualified via {@link #qualified(String)} from the
 * Hibernate {@code default_schema} property, and
 * {@code DeviceGridQuerySchemaQualificationPostgresIntegrationTest}
 * reproduces the live topology so a regression cannot ship.
 *
 * <h3>Latest-per-device</h3>
 * {@code LEFT JOIN LATERAL (... ORDER BY collected_at DESC, created_at DESC,
 * id DESC LIMIT 1)} rides the
 * {@code (tenant_id, device_id, collected_at, created_at, id)} composite
 * index (reverse scan) to pick each device's newest device-health /
 * outdated-software snapshot. LEFT JOIN ⇒ a device with no snapshot yields
 * NULL summary columns — an authoritative "no snapshot", never a false
 * absence (Codex thread 019e7e65).
 *
 * <h3>Injection / fail-closed contract</h3>
 * The client-supplied {@code colId} is ONLY ever a lookup key into
 * {@link DeviceGridColumns}; the SQL expression comes from that trusted
 * registry. Every value is a bound named parameter. Unknown column,
 * unknown filter/sort shape, wrong value type, oversized set/quick-filter,
 * naive date, or compound filter is rejected with a
 * {@link GridQueryValidationException} ({@code INVALID_GRID_FILTER} /
 * {@code INVALID_GRID_SORT} / {@code INVALID_ROW_WINDOW}). LIKE values are
 * wildcard-escaped ({@code \ % _}) with an explicit {@code ESCAPE '\'}.
 */
@Component
public class DeviceGridQueryBuilder {

    public static final String CODE_INVALID_FILTER = "INVALID_GRID_FILTER";
    public static final String CODE_INVALID_SORT = "INVALID_GRID_SORT";
    public static final String CODE_INVALID_WINDOW = "INVALID_ROW_WINDOW";

    private final String schema;
    private final int maxPageSize;
    private final int maxSetSize;
    private final int maxQuickFilterLength;
    private final String selectAll;
    private final String fromAndJoins;

    public DeviceGridQueryBuilder(
            @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}") String schema,
            @Value("${endpoint-admin.grid.max-page:200}") int maxPageSize,
            @Value("${endpoint-admin.grid.max-set-size:200}") int maxSetSize,
            @Value("${endpoint-admin.grid.max-quickfilter:200}") int maxQuickFilterLength) {
        this.schema = schema;
        this.maxPageSize = maxPageSize;
        this.maxSetSize = maxSetSize;
        this.maxQuickFilterLength = maxQuickFilterLength;
        this.fromAndJoins = buildFromAndJoins(schema);
        this.selectAll = selectClause(DeviceGridColumns.all());
    }

    /** SQL + bound params + paging metadata for one SSRM block. */
    public record BuiltGridQuery(String sql, MapSqlParameterSource params,
                                 int startRow, int pageSize) {}

    /** SQL + bound params for a streaming export or a preflight count. */
    public record GridSql(String sql, MapSqlParameterSource params) {}

    /** raw = full tenant dataset, canonical columns; view = current grid view. */
    public enum ExportMode { RAW, VIEW }

    /**
     * Build the page query for an SSRM block. Overfetches {@code pageSize+1}
     * rows so the service can compute {@code lastRow} without a separate
     * count (returned &gt; pageSize ⇒ more rows remain).
     */
    public BuiltGridQuery buildPageQuery(UUID tenantId, DeviceGridQueryRequest req) {
        int startRow = requireWindow(req.startRow(), req.endRow());
        int pageSize = req.endRow() - req.startRow();

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tenantId", tenantId);
        int[] seq = {0};

        StringBuilder where = new StringBuilder("d.tenant_id = :tenantId");
        appendFilterModel(req.filterModel(), where, params, seq);
        appendQuickFilter(req.quickFilterText(), where, params, seq);

        String orderBy = buildOrderBy(req.sortModel());

        // Overfetch by one row → lastRow detection without a COUNT.
        params.addValue("__limit", pageSize + 1);
        params.addValue("__offset", startRow);

        String sql = selectAll + fromAndJoins
                + " WHERE " + where
                + " ORDER BY " + orderBy
                + " LIMIT :__limit OFFSET :__offset";

        return new BuiltGridQuery(sql, params, startRow, pageSize);
    }

    /**
     * Build the full (unpaged) export query for a streaming CSV/Excel export
     * (#1154 PR-2b). RAW = whole tenant dataset + canonical columns, no
     * filter/sort; VIEW = the caller's current filter/sort/quick-filter +
     * its (server-allowlisted) visible columns. Bounded by a preflight count
     * (see {@link #buildCountPreflight}); no {@code LIMIT} here so a passing
     * preflight always streams the complete result (never a silent truncation).
     */
    public GridSql buildExportQuery(UUID tenantId, ExportMode mode,
                                    DeviceGridExportRequest req,
                                    List<GridColumn> columns) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tenantId", tenantId);
        int[] seq = {0};

        StringBuilder where = new StringBuilder("d.tenant_id = :tenantId");
        String orderBy;
        if (mode == ExportMode.VIEW) {
            appendFilterModel(req.filterModel(), where, params, seq);
            appendQuickFilter(req.quickFilterText(), where, params, seq);
            orderBy = buildOrderBy(req.sortModel());
        } else {
            // RAW = canonical, deterministic; grid view state is ignored.
            orderBy = "d.id ASC";
        }

        String sql = selectClause(columns) + fromAndJoins
                + " WHERE " + where
                + " ORDER BY " + orderBy;
        return new GridSql(sql, params);
    }

    /**
     * Bounded preflight: counts matching rows but stops at {@code cap+1} so an
     * over-cap dataset is detected cheaply (Codex 019e7e65 — never silently
     * truncate the export). The inner {@code LIMIT cap+1} caps the work; the
     * outer {@code count(*)} returns {@code min(actual, cap+1)}.
     */
    public GridSql buildCountPreflight(UUID tenantId, ExportMode mode,
                                       DeviceGridExportRequest req, int cap) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("tenantId", tenantId);
        int[] seq = {0};

        StringBuilder where = new StringBuilder("d.tenant_id = :tenantId");
        if (mode == ExportMode.VIEW) {
            appendFilterModel(req.filterModel(), where, params, seq);
            appendQuickFilter(req.quickFilterText(), where, params, seq);
        }
        params.addValue("__cap", cap + 1);

        String sql = "SELECT count(*) FROM (SELECT 1" + fromAndJoins
                + " WHERE " + where
                + " LIMIT :__cap) preflight";
        return new GridSql(sql, params);
    }

    /**
     * Resolve the export column set. RAW ignores any requested columns and
     * exports every canonical column in registry order. VIEW maps each
     * requested colId through the allowlist (unknown → fail-closed 400);
     * an empty/absent list falls back to all columns. Header labels come from
     * the registry (server-side), never from the client.
     */
    public List<GridColumn> resolveExportColumns(ExportMode mode, List<String> requested) {
        if (mode == ExportMode.RAW || requested == null || requested.isEmpty()) {
            return DeviceGridColumns.all();
        }
        List<GridColumn> resolved = new java.util.ArrayList<>(requested.size());
        for (String colId : requested) {
            GridColumn col = DeviceGridColumns.byId(colId);
            if (col == null) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Unknown export column: " + safe(colId));
            }
            resolved.add(col);
        }
        return resolved;
    }

    private String selectClause(List<GridColumn> cols) {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            GridColumn c = cols.get(i);
            select.append(c.sqlExpr()).append(" AS ").append(c.colId());
        }
        return select.toString();
    }

    // ───────────────────────── paging ─────────────────────────

    private int requireWindow(Integer startRow, Integer endRow) {
        if (startRow == null || endRow == null) {
            throw new GridQueryValidationException(CODE_INVALID_WINDOW,
                    "startRow and endRow are required");
        }
        if (startRow < 0 || endRow <= startRow) {
            throw new GridQueryValidationException(CODE_INVALID_WINDOW,
                    "Row window must satisfy 0 <= startRow < endRow");
        }
        if (endRow - startRow > maxPageSize) {
            throw new GridQueryValidationException(CODE_INVALID_WINDOW,
                    "Row window exceeds the maximum page size of " + maxPageSize);
        }
        return startRow;
    }

    // ───────────────────────── filters ─────────────────────────

    @SuppressWarnings("unchecked")
    private void appendFilterModel(Map<String, Object> filterModel,
                                   StringBuilder where,
                                   MapSqlParameterSource params,
                                   int[] seq) {
        if (filterModel == null || filterModel.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : filterModel.entrySet()) {
            GridColumn col = DeviceGridColumns.byId(entry.getKey());
            if (col == null) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Unknown filter column: " + safe(entry.getKey()));
            }
            if (!(entry.getValue() instanceof Map<?, ?> specRaw)) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Filter spec must be an object for column " + col.colId());
            }
            Map<String, Object> spec = (Map<String, Object>) specRaw;
            // Compound filters (operator + conditions / condition1+condition2)
            // are not supported in v1 — fail closed, never silently ignore.
            if (spec.containsKey("operator") || spec.containsKey("conditions")
                    || spec.containsKey("condition1") || spec.containsKey("condition2")) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Compound filters are not supported for column " + col.colId());
            }
            where.append(" AND ");
            switch (col.type()) {
                case TEXT -> appendTextFilter(col, spec, where, params, seq);
                case ENUM -> appendSetFilter(col, spec, where, params, seq, false);
                case BOOLEAN -> appendSetFilter(col, spec, where, params, seq, true);
                case NUMBER -> appendNumberFilter(col, spec, where, params, seq);
                case TIMESTAMP -> appendDateFilter(col, spec, where, params, seq);
            }
        }
    }

    private void appendTextFilter(GridColumn col, Map<String, Object> spec,
                                  StringBuilder where, MapSqlParameterSource params, int[] seq) {
        requireFilterType(col, spec, "text");
        String type = stringField(spec, "type", col);
        // Filter against filterExpr (e.g. d.id::text for the UUID device_id)
        // so lower(...) is always valid; never the raw select expression.
        String expr = col.filterExpr();
        String lowerExpr = "lower(" + expr + ")";
        switch (type) {
            case "blank" -> where.append("(").append(expr).append(" IS NULL OR ")
                    .append(expr).append(" = '')");
            case "notBlank" -> where.append("(").append(expr).append(" IS NOT NULL AND ")
                    .append(expr).append(" <> '')");
            default -> {
                String value = requireText(spec, "filter", col).toLowerCase();
                String p = nextParam(seq);
                switch (type) {
                    case "equals" -> {
                        where.append(lowerExpr).append(" = :").append(p);
                        params.addValue(p, value);
                    }
                    case "notEqual" -> {
                        where.append("(").append(lowerExpr).append(" <> :").append(p)
                                .append(" OR ").append(expr).append(" IS NULL)");
                        params.addValue(p, value);
                    }
                    case "contains" -> likeClause(where, lowerExpr, p, params, "%" + escapeLike(value) + "%");
                    case "notContains" -> {
                        where.append("(").append(lowerExpr).append(" NOT LIKE :").append(p)
                                .append(" ESCAPE '\\' OR ").append(expr).append(" IS NULL)");
                        params.addValue(p, "%" + escapeLike(value) + "%");
                    }
                    case "startsWith" -> likeClause(where, lowerExpr, p, params, escapeLike(value) + "%");
                    case "endsWith" -> likeClause(where, lowerExpr, p, params, "%" + escapeLike(value));
                    default -> throw invalidType(col, type, CODE_INVALID_FILTER);
                }
            }
        }
    }

    private void likeClause(StringBuilder where, String lowerExpr, String p,
                            MapSqlParameterSource params, String pattern) {
        where.append(lowerExpr).append(" LIKE :").append(p).append(" ESCAPE '\\'");
        params.addValue(p, pattern);
    }

    private void appendNumberFilter(GridColumn col, Map<String, Object> spec,
                                    StringBuilder where, MapSqlParameterSource params, int[] seq) {
        requireFilterType(col, spec, "number");
        String type = stringField(spec, "type", col);
        String expr = col.filterExpr();
        switch (type) {
            case "blank" -> where.append(expr).append(" IS NULL");
            case "notBlank" -> where.append(expr).append(" IS NOT NULL");
            case "inRange" -> {
                long from = requireLong(spec, "filter", col);
                long to = requireLong(spec, "filterTo", col);
                String pf = nextParam(seq);
                String pt = nextParam(seq);
                where.append("(").append(expr).append(" >= :").append(pf)
                        .append(" AND ").append(expr).append(" <= :").append(pt).append(")");
                params.addValue(pf, from);
                params.addValue(pt, to);
            }
            default -> {
                long value = requireLong(spec, "filter", col);
                String p = nextParam(seq);
                String op = switch (type) {
                    case "equals" -> " = :";
                    case "notEqual" -> " <> :";
                    case "lessThan" -> " < :";
                    case "lessThanOrEqual" -> " <= :";
                    case "greaterThan" -> " > :";
                    case "greaterThanOrEqual" -> " >= :";
                    default -> throw invalidType(col, type, CODE_INVALID_FILTER);
                };
                where.append(expr).append(op).append(p);
                params.addValue(p, value);
            }
        }
    }

    private void appendDateFilter(GridColumn col, Map<String, Object> spec,
                                  StringBuilder where, MapSqlParameterSource params, int[] seq) {
        requireFilterType(col, spec, "date");
        String type = stringField(spec, "type", col);
        String expr = col.filterExpr();
        switch (type) {
            case "blank" -> where.append(expr).append(" IS NULL");
            case "notBlank" -> where.append(expr).append(" IS NOT NULL");
            case "equals" -> {
                // Calendar-day match [from, from+1day) — NOT +1s (Codex
                // 019e7e65: +1s would silently drop all but the first second).
                Instant from = requireInstant(spec, "dateFrom", col);
                String pf = nextParam(seq);
                String pt = nextParam(seq);
                where.append("(").append(expr).append(" >= :").append(pf)
                        .append(" AND ").append(expr).append(" < :").append(pt).append(")");
                params.addValue(pf, utc(from));
                params.addValue(pt, utc(from.plus(Duration.ofDays(1))));
            }
            case "notEqual" -> {
                Instant from = requireInstant(spec, "dateFrom", col);
                String pf = nextParam(seq);
                String pt = nextParam(seq);
                where.append("(").append(expr).append(" < :").append(pf)
                        .append(" OR ").append(expr).append(" >= :").append(pt).append(")");
                params.addValue(pf, utc(from));
                params.addValue(pt, utc(from.plus(Duration.ofDays(1))));
            }
            case "inRange" -> {
                Instant from = requireInstant(spec, "dateFrom", col);
                Instant to = requireInstant(spec, "dateTo", col);
                String pf = nextParam(seq);
                String pt = nextParam(seq);
                where.append("(").append(expr).append(" >= :").append(pf)
                        .append(" AND ").append(expr).append(" < :").append(pt).append(")");
                params.addValue(pf, utc(from));
                params.addValue(pt, utc(to));
            }
            case "lessThan" -> {
                Instant from = requireInstant(spec, "dateFrom", col);
                String p = nextParam(seq);
                where.append(expr).append(" < :").append(p);
                params.addValue(p, utc(from));
            }
            case "greaterThan" -> {
                Instant from = requireInstant(spec, "dateFrom", col);
                String p = nextParam(seq);
                where.append(expr).append(" > :").append(p);
                params.addValue(p, utc(from));
            }
            default -> throw invalidType(col, type, CODE_INVALID_FILTER);
        }
    }

    /**
     * Bind an instant to a {@code timestamptz} column as a timezone-aware
     * {@link OffsetDateTime} (UTC) rather than a {@code java.sql.Timestamp},
     * so boundary comparisons are immune to the JVM/session timezone
     * (Codex 019e7e65).
     */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Set filter (IN), shared by ENUM (string values) and BOOLEAN
     * ({true,false}). An empty selection matches nothing (fail-closed
     * {@code 1=0}), mirroring AG Grid "select none".
     */
    private void appendSetFilter(GridColumn col, Map<String, Object> spec,
                                 StringBuilder where, MapSqlParameterSource params,
                                 int[] seq, boolean booleanValues) {
        requireFilterType(col, spec, "set");
        Object raw = spec.get("values");
        if (!(raw instanceof List<?> values)) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Set filter requires a 'values' array for column " + col.colId());
        }
        if (values.size() > maxSetSize) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Set filter for " + col.colId() + " exceeds the maximum of " + maxSetSize + " values");
        }
        if (values.isEmpty()) {
            where.append("1=0");
            return;
        }
        StringBuilder in = new StringBuilder("(").append(col.filterExpr()).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (!(v instanceof String s)) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Set filter values for " + col.colId() + " must be strings");
            }
            String p = nextParam(seq);
            if (i > 0) {
                in.append(", ");
            }
            in.append(":").append(p);
            if (booleanValues) {
                if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                    throw new GridQueryValidationException(CODE_INVALID_FILTER,
                            "Boolean filter for " + col.colId() + " accepts only true/false");
                }
                params.addValue(p, Boolean.parseBoolean(s));
            } else {
                params.addValue(p, s);
            }
        }
        in.append("))");
        where.append(in);
    }

    // ───────────────────────── sort ─────────────────────────

    @SuppressWarnings("unchecked")
    private String buildOrderBy(List<Map<String, Object>> sortModel) {
        StringBuilder order = new StringBuilder();
        boolean sortedByDeviceId = false;
        if (sortModel != null) {
            for (Object rawItem : sortModel) {
                if (!(rawItem instanceof Map<?, ?> itemRaw)) {
                    throw new GridQueryValidationException(CODE_INVALID_SORT,
                            "Sort item must be an object");
                }
                Map<String, Object> item = (Map<String, Object>) itemRaw;
                Object colIdObj = item.get("colId");
                if (!(colIdObj instanceof String colId)) {
                    throw new GridQueryValidationException(CODE_INVALID_SORT,
                            "Sort item requires a colId");
                }
                GridColumn col = DeviceGridColumns.byId(colId);
                if (col == null) {
                    throw new GridQueryValidationException(CODE_INVALID_SORT,
                            "Unknown sort column: " + safe(colId));
                }
                Object sortObj = item.get("sort");
                if (!(sortObj instanceof String sortDir)
                        || !("asc".equalsIgnoreCase(sortDir) || "desc".equalsIgnoreCase(sortDir))) {
                    throw new GridQueryValidationException(CODE_INVALID_SORT,
                            "Sort direction must be asc or desc for column " + col.colId());
                }
                if (order.length() > 0) {
                    order.append(", ");
                }
                order.append(col.sqlExpr()).append(" ").append(sortDir.toUpperCase(java.util.Locale.ROOT));
                // WEB-015 v2-d (Codex 019e8a39 iter-1 must-fix #2 absorb):
                // PostgreSQL's default for DESC is NULLS FIRST. Cache-absent
                // devices (the 9 v5 colIds return NULL) would otherwise
                // surface at the TOP of a DESC sort — "most changed devices"
                // would be polluted by "devices the cache hasn't computed
                // yet". Pin NULLS LAST on the v5 cache columns so the
                // operator-visible "top N" is the actually-computed
                // top N. ASC default is NULLS LAST in PG so no special
                // handling needed there.
                if ("desc".equalsIgnoreCase(sortDir) && isCacheColumn(col.colId())) {
                    order.append(" NULLS LAST");
                }
                if ("device_id".equals(col.colId())) {
                    sortedByDeviceId = true;
                }
            }
        }
        // Deterministic tie-breaker so SSRM pagination cannot duplicate/skip
        // rows that share the user's sort key (Codex 019e7e65).
        if (!sortedByDeviceId) {
            if (order.length() > 0) {
                order.append(", ");
            }
            order.append("d.id ASC");
        }
        return order.toString();
    }

    /**
     * WEB-015 v2-d (Codex 019e8a39 iter-1 must-fix #2 absorb): the SCHEMA v5
     * cache-fed colIds whose DESC sort needs an explicit {@code NULLS LAST}
     * because PG's DESC default places NULL at the top. Hardcoded set match
     * mirrors the static-constant pattern of the rest of this class — no
     * dynamic registry annotation lookup, no risk of a registry add silently
     * dropping the guard.
     */
    private static boolean isCacheColumn(String colId) {
        return switch (colId) {
            case "software_diff_status",
                 "software_diff_added_count",
                 "software_diff_removed_count",
                 "software_diff_version_changed_count",
                 "outdated_diff_status",
                 "outdated_diff_added_count",
                 "outdated_diff_removed_count",
                 "outdated_diff_version_changed_count",
                 "outdated_diff_available_version_bumped_count" -> true;
            default -> false;
        };
    }

    // ───────────────────────── quick filter ─────────────────────────

    private void appendQuickFilter(String quickFilterText, StringBuilder where,
                                   MapSqlParameterSource params, int[] seq) {
        if (quickFilterText == null || quickFilterText.isBlank()) {
            return;
        }
        if (quickFilterText.length() > maxQuickFilterLength) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "quickFilterText exceeds the maximum length of " + maxQuickFilterLength);
        }
        String p = nextParam(seq);
        params.addValue(p, "%" + escapeLike(quickFilterText.toLowerCase()) + "%");
        StringBuilder or = new StringBuilder();
        for (GridColumn col : DeviceGridColumns.all()) {
            if (!col.quickFilterable()) {
                continue;
            }
            if (or.length() > 0) {
                or.append(" OR ");
            }
            or.append("lower(").append(col.filterExpr()).append(") LIKE :").append(p).append(" ESCAPE '\\'");
        }
        where.append(" AND (").append(or).append(")");
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String nextParam(int[] seq) {
        return "p" + (seq[0]++);
    }

    private void requireFilterType(GridColumn col, Map<String, Object> spec, String expected) {
        // Strict fail-closed contract: filterType MUST be present, a string,
        // and the family this column accepts (Codex 019e7e65).
        Object ft = spec.get("filterType");
        if (!(ft instanceof String s) || !expected.equals(s)) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Column " + col.colId() + " requires filterType '" + expected + "'");
        }
    }

    private String stringField(Map<String, Object> spec, String key, GridColumn col) {
        Object v = spec.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Filter '" + key + "' is required for column " + col.colId());
        }
        return s;
    }

    private String requireText(Map<String, Object> spec, String key, GridColumn col) {
        Object v = spec.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Filter value '" + key + "' is required for column " + col.colId());
        }
        // Cap free-text filter length (quickFilter is already capped; per
        // Codex 019e7e65 per-column text values must be too).
        if (s.length() > maxQuickFilterLength) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Filter value for " + col.colId() + " exceeds the maximum length of " + maxQuickFilterLength);
        }
        return s;
    }

    private long requireLong(Map<String, Object> spec, String key, GridColumn col) {
        Object v = spec.get(key);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return n.longValue();
            }
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Filter '" + key + "' must be an integer for column " + col.colId());
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new GridQueryValidationException(CODE_INVALID_FILTER,
                        "Filter '" + key + "' must be an integer for column " + col.colId());
            }
        }
        throw new GridQueryValidationException(CODE_INVALID_FILTER,
                "Filter '" + key + "' is required for column " + col.colId());
    }

    private Instant requireInstant(Map<String, Object> spec, String key, GridColumn col) {
        Object v = spec.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Date filter '" + key + "' is required for column " + col.colId());
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException ex) {
            throw new GridQueryValidationException(CODE_INVALID_FILTER,
                    "Date filter '" + key + "' must be an ISO-8601 instant for column " + col.colId());
        }
    }

    private GridQueryValidationException invalidType(GridColumn col, String type, String code) {
        return new GridQueryValidationException(code,
                "Unsupported filter type '" + safe(type) + "' for column " + col.colId());
    }

    /** Escape LIKE wildcards so user text matches literally (ESCAPE '\'). */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** Truncate untrusted text before echoing it in an error message. */
    private static String safe(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.length() > 64 ? s.substring(0, 64) + "…" : s;
        return t.replaceAll("[\\r\\n]", " ");
    }

    private String qualified(String tableName) {
        String resolved = schema == null ? "" : schema.trim();
        if (resolved.isBlank()) {
            return tableName;
        }
        if (!resolved.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolved + "." + tableName;
    }

    private String buildFromAndJoins(String schemaName) {
        // schemaName is validated by qualified(). The constant FROM + LATERAL
        // body is shared by the page query, the export query, and the
        // preflight count (single source of truth for the join shape).
        return " FROM " + qualified("endpoint_devices") + " d"
                + " LEFT JOIN LATERAL ("
                + "   SELECT hs.supported, hs.probe_complete, hs.any_low_disk,"
                + "          hs.memory_used_percent, hs.memory_high_pressure, hs.uptime_days,"
                + "          hs.long_uptime_warning, hs.collected_at"
                + "   FROM " + qualified("endpoint_device_health_snapshots") + " hs"
                + "   WHERE hs.tenant_id = d.tenant_id AND hs.device_id = d.id"
                + "   ORDER BY hs.collected_at DESC, hs.created_at DESC, hs.id DESC"
                + "   LIMIT 1"
                + " ) h ON true"
                + " LEFT JOIN LATERAL ("
                + "   SELECT os.supported, os.probe_complete, os.upgrade_count,"
                + "          os.upgrade_truncated, os.collected_at"
                + "   FROM " + qualified("endpoint_outdated_software_snapshots") + " os"
                + "   WHERE os.tenant_id = d.tenant_id AND os.device_id = d.id"
                + "   ORDER BY os.collected_at DESC, os.created_at DESC, os.id DESC"
                + "   LIMIT 1"
                + " ) o ON true"
                // WEB-015 v2-a (schema v3) BE-025 latest compliance evaluation.
                // Codex 019e8785 iter-2: read persisted evaluation row, no live
                // recompute.
                + " LEFT JOIN LATERAL ("
                + "   SELECT ce.id, ce.decision, ce.evidence"
                + "   FROM " + qualified("endpoint_compliance_evaluations") + " ce"
                + "   WHERE ce.tenant_id = d.tenant_id AND ce.device_id = d.id"
                + "   ORDER BY ce.evaluated_at DESC, ce.id DESC"
                + "   LIMIT 1"
                + " ) pe ON true"
                // WEB-015 v2-a (schema v3) AG-041 latest app-control snapshot.
                // Codex 019e8785 iter-2: ac.id IS NULL => no-snapshot, NOT
                // synthesized to UNKNOWN; persisted UNKNOWN is operator signal.
                + " LEFT JOIN LATERAL ("
                + "   SELECT acs.id, acs.wdac_mode, acs.app_locker_app_id_svc_state"
                + "   FROM " + qualified("endpoint_app_control_snapshots") + " acs"
                + "   WHERE acs.tenant_id = d.tenant_id AND acs.device_id = d.id"
                + "   ORDER BY acs.collected_at DESC, acs.created_at DESC, acs.id DESC"
                + "   LIMIT 1"
                + " ) ac ON true"
                // WEB-015 v2-b (schema v4) AG-038 latest diagnostics snapshot.
                // Codex 019e87bc iter-1 must_fix #2: error code is NOT a closed
                // ENUM — backend emits e.g. NEXT_COMMAND_TIMEOUT / DNS_TIMEOUT /
                // UNSUPPORTED_PLATFORM constrained only by
                // `^[A-Z][A-Z0-9_]{2,64}$`. must_fix #3: DB scalar is
                // last_error_occurred_at (UI surface "last_error_at" is
                // intentional but SQL source name MUST match the V23 column).
                + " LEFT JOIN LATERAL ("
                + "   SELECT ds.id,"
                + "          ds.last_poll_latency_ms,"
                + "          ds.last_error_code,"
                + "          ds.last_error_occurred_at"
                + "   FROM " + qualified("endpoint_diagnostics_snapshots") + " ds"
                + "   WHERE ds.tenant_id = d.tenant_id AND ds.device_id = d.id"
                // Codex 019e87bc iter-2 must_fix: canonical latest contract is
                // `collected_at DESC, created_at DESC, id DESC` (the V23 index
                // shape + the repository order — see EndpointDiagnosticsSnapshotRepository).
                // Missing the created_at tiebreaker would let drawer /latest
                // and grid render different snapshots when two share collected_at.
                + "   ORDER BY ds.collected_at DESC, ds.created_at DESC, ds.id DESC"
                + "   LIMIT 1"
                + " ) dx ON true"
                // WEB-015 v2-d (schema v5) BE-024c DiffCache summary cache
                // tables. Plain LEFT JOIN (not LATERAL) — cache UNIQUE per
                // (tenant_id, device_id) already guarantees one row per
                // device. Cache-absent device → 9 cache colIds return NULL
                // (read-model "not yet computed"; distinct from 'NO_HISTORY'
                // which is a real cache row state meaning "device has 0
                // history rows"). Grid stays read-only — the canonical
                // drawer endpoint is the live truth; the AFTER_COMMIT
                // listener + 10-min DiffCacheBackfillWorker close the
                // catch-up lag.
                //
                // Codex 019e8a39 iter-1 must-fix #1 absorb: schema name is
                // routed through the existing qualified() helper, NOT
                // hardcoded; the DeviceGridQueryBuilderTest pins this with
                // a custom-schema instance.
                + " LEFT JOIN " + qualified("endpoint_software_diff_cache") + " sdc"
                + "   ON sdc.tenant_id = d.tenant_id AND sdc.device_id = d.id"
                + " LEFT JOIN " + qualified("endpoint_outdated_software_diff_cache") + " odc"
                + "   ON odc.tenant_id = d.tenant_id AND odc.device_id = d.id"
                // WEB-015 v2-b (schema v4) AG-040 latest startup-exposure
                // snapshot. Codex 019e87bc iter-1 must_fix #4: V25 boolean
                // columns are NOT NULL (fail-closed evidence), so the grid
                // CASE guards on supported/probe_complete to project NULL
                // for "no measurable evidence yet" — registry expressions
                // reference sx.{supported,probe_complete,rdp_enabled,
                // windows_firewall_event_log_enabled}.
                + " LEFT JOIN LATERAL ("
                + "   SELECT ses.id,"
                + "          ses.supported,"
                + "          ses.probe_complete,"
                + "          ses.rdp_enabled,"
                + "          ses.windows_firewall_event_log_enabled"
                + "   FROM " + qualified("endpoint_startup_exposure_snapshots") + " ses"
                + "   WHERE ses.tenant_id = d.tenant_id AND ses.device_id = d.id"
                // Codex 019e87bc iter-2 must_fix: canonical latest contract +
                // V25 index shape + EndpointStartupExposureSnapshotRepository.
                + "   ORDER BY ses.collected_at DESC, ses.created_at DESC, ses.id DESC"
                + "   LIMIT 1"
                + " ) sx ON true"
                // WEB-015 v2-b (schema v4) AG-039 latest services snapshot.
                // Codex 019e87bc iter-1 must_fix #5: surface just one
                // operational sentinel (critical_stopped_count from the
                // backend-canonical 6-allowlist running OR STOPPED). The
                // count is precomputed in this LATERAL row so the registry
                // expression can stay schema-agnostic (subquery against
                // endpoint_services_entries needs the runtime schema name).
                // The allowlist is enforced server-side at ingest time
                // (ServicesPayloadPolicy SERVICE_NAME_ALLOWLIST), so a
                // snapshot row can never carry an entry outside the 6
                // canonical names — counting state=STOPPED + present=true
                // is equivalent to counting "critical service down".
                // CASE guard on supported/probe_complete is applied at the
                // registry layer (NULL when not measurable yet).
                + " LEFT JOIN LATERAL ("
                + "   SELECT s.id,"
                + "          s.tenant_id,"
                + "          s.supported,"
                + "          s.probe_complete,"
                + "          ("
                + "            SELECT COUNT(*)::int"
                + "            FROM " + qualified("endpoint_services_entries") + " ent"
                + "            WHERE ent.tenant_id = s.tenant_id"
                + "              AND ent.snapshot_id = s.id"
                + "              AND ent.present = true"
                + "              AND ent.state = 'STOPPED'"
                + "          ) AS critical_stopped_count"
                + "   FROM " + qualified("endpoint_services_snapshots") + " s"
                + "   WHERE s.tenant_id = d.tenant_id AND s.device_id = d.id"
                // Codex 019e87bc iter-2 must_fix: canonical latest contract +
                // V24 index shape + EndpointServicesSnapshotRepository.
                + "   ORDER BY s.collected_at DESC, s.created_at DESC, s.id DESC"
                + "   LIMIT 1"
                + " ) se ON true";
    }
}
