package com.example.endpointadmin.grid;

import com.example.endpointadmin.grid.DeviceGridQueryBuilder.BuiltGridQuery;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs the {@link DeviceGridQueryBuilder} page query for the SSRM
 * {@code /query} endpoint and shapes the AG Grid block response (board
 * #1154 PR-2a).
 *
 * <p>Deliberately NOT {@code @Transactional}: this is a single read-only
 * {@code NamedParameterJdbcTemplate} query with no lazy associations, so
 * it needs no session. Rows are plain {@code colId -> value} maps that
 * PRESERVE {@code null}s (a {@code null} health/outdated column is an
 * authoritative "no snapshot"); timestamps are normalised to
 * {@link java.time.Instant} so the JSON wire shape is a stable ISO-8601
 * string rather than a driver-specific {@code Timestamp}.
 */
@Service
public class DeviceGridQueryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceGridQueryBuilder builder;
    private final List<String> columnIds = DeviceGridColumns.allColumnIds();

    public DeviceGridQueryService(NamedParameterJdbcTemplate jdbc,
                                  DeviceGridQueryBuilder builder) {
        this.jdbc = jdbc;
        this.builder = builder;
    }

    public DeviceGridQueryResponse query(UUID tenantId, DeviceGridQueryRequest req) {
        BuiltGridQuery built = builder.buildPageQuery(tenantId, req);

        List<Map<String, Object>> rows = jdbc.query(built.sql(), built.params(), (rs, rowNum) -> {
            // LinkedHashMap preserves null values (Map.of/copyOf would NPE)
            // and keeps the canonical column order.
            Map<String, Object> row = new LinkedHashMap<>();
            for (String colId : columnIds) {
                row.put(colId, normalize(rs.getObject(colId)));
            }
            return row;
        });

        int pageSize = built.pageSize();
        long lastRow;
        if (rows.size() > pageSize) {
            // Overfetched an extra row ⇒ more blocks remain.
            rows = rows.subList(0, pageSize);
            lastRow = -1;
        } else {
            // Short (or exact) block ⇒ this is the last one; the total is
            // known precisely as startRow + returned.
            lastRow = (long) built.startRow() + rows.size();
        }
        // subList is a view over the mutable ArrayList from jdbc.query; copy
        // so the response holds a standalone list.
        return new DeviceGridQueryResponse(List.copyOf(rows), lastRow);
    }

    /** Normalise driver temporal types to Instant for a stable JSON shape. */
    private static Object normalize(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return value;
    }
}
