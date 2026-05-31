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
    private final String baseSelectFrom;

    public DeviceGridQueryBuilder(
            @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}") String schema,
            @Value("${endpoint-admin.grid.max-page:200}") int maxPageSize,
            @Value("${endpoint-admin.grid.max-set-size:200}") int maxSetSize,
            @Value("${endpoint-admin.grid.max-quickfilter:200}") int maxQuickFilterLength) {
        this.schema = schema;
        this.maxPageSize = maxPageSize;
        this.maxSetSize = maxSetSize;
        this.maxQuickFilterLength = maxQuickFilterLength;
        this.baseSelectFrom = buildBaseSelectFrom(schema);
    }

    /** SQL + bound params + paging metadata for one SSRM block. */
    public record BuiltGridQuery(String sql, MapSqlParameterSource params,
                                 int startRow, int pageSize) {}

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

        String sql = baseSelectFrom
                + " WHERE " + where
                + " ORDER BY " + orderBy
                + " LIMIT :__limit OFFSET :__offset";

        return new BuiltGridQuery(sql, params, startRow, pageSize);
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

    private String buildBaseSelectFrom(String schemaName) {
        // schemaName is validated by qualified(); build the constant
        // projection from the trusted column registry.
        StringBuilder select = new StringBuilder("SELECT ");
        List<GridColumn> cols = DeviceGridColumns.all();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            GridColumn c = cols.get(i);
            select.append(c.sqlExpr()).append(" AS ").append(c.colId());
        }
        return select
                + " FROM " + qualified("endpoint_devices") + " d"
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
                + " ) o ON true";
    }
}
